#!/usr/bin/env bash
# macOS Bash 3.2 compatible. Secrets are read silently, never saved to disk.
set -euo pipefail
umask 077

ROOT=$(cd "$(dirname "$0")/.." && pwd)
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

if [ "${1:-}" = --help ]; then
  printf '%s\n' \
    'Usage: bash scripts/build-android-release.sh [versionCode] [versionName]' \
    'Example: bash scripts/build-android-release.sh 1000 1.0.0' \
    'Requires Android Studio, Android SDK Platform 35, Build Tools and JDK 17.' \
    'Default keystore: ~/.mdmesh-signing/release.jks (never overwritten).' \
    'Override: MDM_RELEASE_STORE_FILE, MDM_RELEASE_KEY_ALIAS, JAVA_HOME,' \
    'ANDROID_HOME, APKSIGNER, MDM_APK_URL.' \
    'Output: agent-android/app/build/distributions/<versionCode>/'
  exit 0
fi
[ "$#" -le 2 ] || fail 'Too many arguments; use --help.'
VERSION_CODE=${1:-}
VERSION_NAME=${2:-}
[ -n "$VERSION_CODE" ] || read -r -p 'versionCode (first build: 1000; increment for each update): ' VERSION_CODE
case "$VERSION_CODE" in ''|*[!0-9]*|0*) fail 'versionCode must be a positive integer without leading zeroes.' ;; esac
[ "${#VERSION_CODE}" -le 10 ] && [ "$VERSION_CODE" -le 2100000000 ] || fail 'versionCode is too large.'
[ -n "$VERSION_NAME" ] || read -r -p 'versionName (example: 1.0.0): ' VERSION_NAME
case "$VERSION_NAME" in ''|*[!a-zA-Z0-9._-]*) fail 'Use letters, digits, dots, underscores or hyphens for versionName.' ;; esac

# Prefer a locally installed JDK 17, falling back to Android Studio's JBR.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v java >/dev/null || fail 'Install JDK 17 and set JAVA_HOME.'
command -v keytool >/dev/null || fail 'keytool is missing; install JDK 17.'
command -v openssl >/dev/null || fail 'openssl is required.'
java -version
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$ANDROID_HOME/platforms/android-35" ] || fail "Install Android SDK Platform 35 in Android Studio (SDK: $ANDROID_HOME)."
if [ -z "${APKSIGNER:-}" ]; then
  APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
  if [ ! -x "$APKSIGNER" ]; then
    for candidate in "$ANDROID_HOME"/build-tools/*/apksigner; do
      [ ! -x "$candidate" ] || APKSIGNER="$candidate"
    done
  fi
fi
[ -x "$APKSIGNER" ] || fail 'Install SDK Build Tools or set APKSIGNER to its absolute path.'

export MDM_RELEASE_STORE_FILE="${MDM_RELEASE_STORE_FILE:-$HOME/.mdmesh-signing/release.jks}"
export MDM_RELEASE_KEY_ALIAS="${MDM_RELEASE_KEY_ALIAS:-mdmesh}"
case "$MDM_RELEASE_STORE_FILE" in /*) ;; *) fail 'MDM_RELEASE_STORE_FILE must be an absolute path.' ;; esac
OUT="$ROOT/agent-android/app/build/distributions/$VERSION_CODE"
[ ! -e "$OUT" ] || fail "Output already exists: $OUT. Choose a new versionCode."

trap 'unset MDM_RELEASE_STORE_PASSWORD MDM_RELEASE_KEY_PASSWORD confirmation' EXIT
if [ -z "${MDM_RELEASE_STORE_PASSWORD:-}" ]; then
  read -r -s -p 'Keystore password (at least 6 characters): ' MDM_RELEASE_STORE_PASSWORD
  printf '\n'
fi
[ "${#MDM_RELEASE_STORE_PASSWORD}" -ge 6 ] || fail 'Password must contain at least 6 characters.'
export MDM_RELEASE_STORE_PASSWORD

if [ ! -e "$MDM_RELEASE_STORE_FILE" ]; then
  read -r -s -p 'Confirm password for NEW keystore: ' confirmation
  printf '\n'
  [ "$confirmation" = "$MDM_RELEASE_STORE_PASSWORD" ] || fail 'Passwords do not match.'
  export MDM_RELEASE_KEY_PASSWORD="$MDM_RELEASE_STORE_PASSWORD"
  mkdir -p "$(dirname "$MDM_RELEASE_STORE_FILE")"
  keytool -genkeypair -storetype JKS -keystore "$MDM_RELEASE_STORE_FILE" \
    -alias "$MDM_RELEASE_KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=MDMesh Private Release, O=MDMesh' \
    -storepass:env MDM_RELEASE_STORE_PASSWORD -keypass:env MDM_RELEASE_KEY_PASSWORD
  chmod 600 "$MDM_RELEASE_STORE_FILE"
  printf 'Created keystore: %s\nBack it up securely with its password; reuse it for all updates.\n' "$MDM_RELEASE_STORE_FILE"
else
  printf 'Reusing keystore: %s\n' "$MDM_RELEASE_STORE_FILE"
  if [ -z "${MDM_RELEASE_KEY_PASSWORD:-}" ]; then
    read -r -s -p 'Key password (Enter if same as keystore password): ' MDM_RELEASE_KEY_PASSWORD
    printf '\n'
    MDM_RELEASE_KEY_PASSWORD=${MDM_RELEASE_KEY_PASSWORD:-$MDM_RELEASE_STORE_PASSWORD}
  fi
  export MDM_RELEASE_KEY_PASSWORD
fi
keytool -list -keystore "$MDM_RELEASE_STORE_FILE" -alias "$MDM_RELEASE_KEY_ALIAS" \
  -storepass:env MDM_RELEASE_STORE_PASSWORD >/dev/null

cd "$ROOT/agent-android"
bash ./gradlew --no-daemon :app:assembleRelease \
  "-PversionCode=$VERSION_CODE" "-PversionName=$VERSION_NAME"
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || fail 'Signed release APK was not produced.'
CERT_INFO=$("$APKSIGNER" verify --print-certs "$APK")
HEX=$(printf '%s\n' "$CERT_INFO" | awk '/Signer #1 certificate SHA-256 digest:/{print $NF; exit}')
[ "${#HEX}" -eq 64 ] || fail 'Could not read the signing certificate checksum.'
command -v xxd >/dev/null || fail 'xxd is required to encode the certificate checksum.'
CHECKSUM=$(printf '%s' "$HEX" | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '=')
APK_SHA=$(openssl dgst -sha256 "$APK" | awk '{print $NF}')
mkdir -p "$OUT"
cp "$APK" "$OUT/mdmesh-custom.apk"
printf '%s  mdmesh-custom.apk\n' "$APK_SHA" > "$OUT/SHA256SUMS"
printf '%s\n' \
  'VITE_AGENT_PACKAGE=com.mdmesh.agent' \
  "VITE_AGENT_CHECKSUM=$CHECKSUM" \
  "VITE_AGENT_APK_URL=${MDM_APK_URL:-https://mdm.hatbuinho.me/files/mdmesh-custom.apk}" \
  > "$OUT/web-build.env"
printf 'versionCode=%s\nversionName=%s\ncertificateSHA256=%s\n' \
  "$VERSION_CODE" "$VERSION_NAME" "$HEX" > "$OUT/release-info.txt"
printf '\nRelease verified. Upload the contents of:\n%s\n' "$OUT"
printf 'Keystore stays on your Mac: %s\n' "$MDM_RELEASE_STORE_FILE"
printf 'Server must host this APK and rebuild the web image using web-build.env before QR enrollment.\n'
