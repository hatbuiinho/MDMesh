#!/usr/bin/env bash
# macOS Bash 3.2 compatible. Reads release settings from dotenv/environment.
set -euo pipefail
set +x
umask 077

ROOT=$(cd "$(dirname "$0")/.." && pwd)
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

if [ "${1:-}" = --help ]; then
  printf '%s\n' \
    'Usage: bash scripts/build-android-release.sh [--non-interactive] [--env-file PATH] [versionCode] [versionName]' \
    'Example: bash scripts/build-android-release.sh 1000 1.0.0' \
    'Requires Android Studio, Android SDK Platform 35, Build Tools and JDK 17.' \
    'Default keystore: ~/.mdmesh-signing/release.jks (never overwritten).' \
    'Reads root .env by default; environment overrides file values.' \
    'Supports MDM_RELEASE_STORE_PASSWORD, MDM_RELEASE_KEY_PASSWORD,' \
    'MDM_RELEASE_VERSION_NAME; versionCode defaults to automatic +1.' \
    'Override: MDM_RELEASE_STORE_FILE, MDM_RELEASE_KEY_ALIAS, JAVA_HOME,' \
    'ANDROID_HOME, APKSIGNER, MDM_APK_URL.' \
    'Output: agent-android/app/build/distributions/<versionCode>/'
  exit 0
fi
NON_INTERACTIVE=0
ENV_FILE="$ROOT/.env"
ENV_EXPLICIT=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --env-file) [ "$#" -ge 2 ] || fail 'Missing --env-file path.'; ENV_FILE=$2; ENV_EXPLICIT=1; shift 2 ;;
    --*) fail 'Unknown option; use --help.' ;;
    *) break ;;
  esac
done
source "$ROOT/scripts/lib/release-env.sh"
if [ -f "$ENV_FILE" ]; then load_release_env "$ENV_FILE" || fail 'Cannot read release settings.'
elif [ "$ENV_EXPLICIT" = 1 ]; then fail 'Env file does not exist.'; fi
[ -t 0 ] && [ -t 1 ] || NON_INTERACTIVE=1
[ "$#" -le 2 ] || fail 'Too many arguments; use --help.'
VERSION_CODE=${1:-${MDM_RELEASE_VERSION_CODE:-}}
VERSION_NAME=${2:-${MDM_RELEASE_VERSION_NAME:-}}
[ -n "$VERSION_NAME" ] || [ "$NON_INTERACTIVE" = 0 ] || fail 'Set MDM_RELEASE_VERSION_NAME or pass versionName.'
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

REFERENCE_CODE=0
if [ -n "${MDM_REFERENCE_APK:-}" ]; then
  [ -r "$MDM_REFERENCE_APK" ] || fail 'Cannot read MDM_REFERENCE_APK.'
  AAPT="$(dirname "$APKSIGNER")/aapt"
  [ -x "$AAPT" ] || fail 'Install SDK Build Tools (aapt) to read the reference APK version.'
  REFERENCE_INFO=$("$AAPT" dump badging "$MDM_REFERENCE_APK") || fail 'Cannot read reference APK.'
  REFERENCE_CODE=$(printf '%s\n' "$REFERENCE_INFO" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
  [ -n "$REFERENCE_CODE" ] || fail 'Cannot read reference APK versionCode.'
fi
command -v python3 >/dev/null || fail 'python3 is required to select the release version.'
VERSION_CODE=$(python3 "$ROOT/scripts/lib/release-version.py" "$REFERENCE_CODE" "$VERSION_CODE" \
  "$ROOT/agent-android/app/build.gradle.kts" "${MDM_RELEASE_OUTPUT_ROOT:-$ROOT/.android-docker/releases}" \
  "$ROOT/.android-docker/releases" "$ROOT/releases/android" "$ROOT/agent-android/app/build/distributions") \
  || fail 'Cannot select the next versionCode.'
printf 'Release versionCode: %s\n' "$VERSION_CODE"

export MDM_RELEASE_STORE_FILE="${MDM_RELEASE_STORE_FILE:-$HOME/.mdmesh-signing/release.jks}"
export MDM_RELEASE_KEY_ALIAS="${MDM_RELEASE_KEY_ALIAS:-mdmesh}"
case "$MDM_RELEASE_STORE_FILE" in /*) ;; *) fail 'MDM_RELEASE_STORE_FILE must be an absolute path.' ;; esac
OUT="$ROOT/agent-android/app/build/distributions/$VERSION_CODE"
[ ! -e "$OUT" ] || fail "Output already exists: $OUT. Choose a new versionCode."

trap 'unset MDM_RELEASE_STORE_PASSWORD MDM_RELEASE_KEY_PASSWORD confirmation' EXIT
if [ -z "${MDM_RELEASE_STORE_PASSWORD:-}" ]; then
  [ "$NON_INTERACTIVE" = 0 ] || fail 'Set MDM_RELEASE_STORE_PASSWORD in dotenv or environment.'
  read -r -s -p 'Keystore password (at least 6 characters): ' MDM_RELEASE_STORE_PASSWORD
  printf '\n'
fi
[ "${#MDM_RELEASE_STORE_PASSWORD}" -ge 6 ] || fail 'Password must contain at least 6 characters.'
export MDM_RELEASE_STORE_PASSWORD

if [ ! -e "$MDM_RELEASE_STORE_FILE" ]; then
  [ "$NON_INTERACTIVE" = 0 ] || fail 'Automatic releases require an existing keystore; refusing to create a new signing identity.'
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
  if [ -z "${MDM_RELEASE_KEY_PASSWORD:-}" ] && [ "$NON_INTERACTIVE" = 0 ]; then
    read -r -s -p 'Key password (Enter if same as keystore password): ' MDM_RELEASE_KEY_PASSWORD
    printf '\n'
    MDM_RELEASE_KEY_PASSWORD=${MDM_RELEASE_KEY_PASSWORD:-$MDM_RELEASE_STORE_PASSWORD}
  fi
  export MDM_RELEASE_KEY_PASSWORD="${MDM_RELEASE_KEY_PASSWORD:-$MDM_RELEASE_STORE_PASSWORD}"
fi
keytool -list -keystore "$MDM_RELEASE_STORE_FILE" -alias "$MDM_RELEASE_KEY_ALIAS" \
  -storepass:env MDM_RELEASE_STORE_PASSWORD >/dev/null

cd "$ROOT/agent-android"
bash ./gradlew --no-daemon --no-configuration-cache :app:assembleRelease \
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
