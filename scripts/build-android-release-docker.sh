#!/usr/bin/env bash
# Signed release build from environment or interactive prompts. Requires an existing production key; never creates one.
set -euo pipefail
set +x
umask 077

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
IMAGE="mdmesh-android-build:local-$(id -u)-$(id -g)"
WORK=""
RESERVED=""
fail() { printf 'Lỗi: %s\n' "$*" >&2; exit 1; }
cleanup() {
  unset MDM_RELEASE_STORE_PASSWORD MDM_RELEASE_KEY_PASSWORD
  if [[ -n "$WORK" ]]; then rm -rf -- "$WORK"; fi
  if [[ -n "$RESERVED" ]]; then rmdir -- "$RESERVED" 2>/dev/null || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "${1:-}" == --help ]]; then
  cat <<'HELP'
Usage: bash scripts/build-android-release-docker.sh [--non-interactive] [--env-file PATH]

Hỏi đường dẫn keystore cũ, alias, APK production để đối chiếu,
versionName, URL server và mật khẩu (nhập ẩn). Chỉ cần Docker, không cần Java/SDK host.
Script kiểm tra chữ ký/versionCode với APK tham chiếu trước khi build; không tạo key mới.
Output: .android-docker/releases/<versionCode>/mdmesh-custom.apk
Kèm SHA256SUMS, web-build.env và release-info.txt. Không tự deploy hoặc cập nhật thiết bị.

Tự đọc .env ở root repo; --env-file chọn file khác. Environment ưu tiên hơn file.
Không có TTY sẽ chạy tự động; --non-interactive buộc không hỏi.
Giá trị có thể đặt qua .env hoặc environment:
  MDM_RELEASE_STORE_FILE, MDM_RELEASE_KEY_ALIAS, MDM_REFERENCE_APK,
  MDM_RELEASE_VERSION_CODE, MDM_RELEASE_VERSION_NAME, MDM_BASE_URL,
  MDM_APK_URL, MDM_RELEASE_OUTPUT_ROOT,
  MDM_RELEASE_STORE_PASSWORD, MDM_RELEASE_KEY_PASSWORD.
Key password mặc định bằng store password khi chạy tự động.
versionCode tự tăng: max(APK tham chiếu, release cục bộ, Gradle mặc định) + 1.
MDM_RELEASE_VERSION_CODE chỉ dùng khi cần ghi đè; phải lớn hơn mọi bản đã biết.
Không in mật khẩu hoặc truyền giá trị mật khẩu qua tham số lệnh.
HELP
  exit 0
fi
NON_INTERACTIVE=0
ENV_FILE="$ROOT/.env"
ENV_EXPLICIT=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --env-file) [[ $# -ge 2 ]] || fail 'Thiếu đường dẫn --env-file.'; ENV_FILE=$2; ENV_EXPLICIT=1; shift 2 ;;
    *) fail 'Tham số không hợp lệ; dùng --help.' ;;
  esac
done
source "$ROOT/scripts/lib/release-env.sh"
if [[ -f "$ENV_FILE" ]]; then load_release_env "$ENV_FILE" || fail 'Không đọc được cấu hình release.'
elif [[ $ENV_EXPLICIT == 1 ]]; then fail 'Không tìm thấy file --env-file.'; fi
OUTPUT_ROOT="${MDM_RELEASE_OUTPUT_ROOT:-$ROOT/.android-docker/releases}"
[[ -t 0 && -t 1 ]] || NON_INTERACTIVE=1
command -v docker >/dev/null || fail 'Chưa có Docker.'
command -v python3 >/dev/null || fail 'Cần python3 để kiểm tra URL và xuất checksum.'
docker info >/dev/null 2>&1 || fail 'Không kết nối được Docker daemon.'
if [[ $NON_INTERACTIVE == 0 ]]; then exec 3<> /dev/tty; fi

prompt() {
  local answer
  if [[ $NON_INTERACTIVE == 1 ]]; then REPLY=$2; return; fi
  printf '%s [%s]: ' "$1" "$2" >&3
  IFS= read -r answer <&3 || exit 130
  REPLY=${answer:-$2}
}
retry_or_fail() {
  if [[ $NON_INTERACTIVE == 1 ]]; then fail "$1"; fi
  printf '%s\n' "$1"
}
existing_file() {
  local candidate=$1
  case "$candidate" in '~') candidate=$HOME ;; '~/'*) candidate="$HOME/${candidate:2}" ;; esac
  [[ -f "$candidate" && -r "$candidate" ]] || return 1
  REPLY=$(realpath -- "$candidate")
  # Docker --mount uses commas as delimiters.
  [[ "$REPLY" != *','* && "$REPLY" != *$'\n'* && "$REPLY" != *$'\r'* ]]
}

printf '\nBuild Android release bằng Docker\nDùng đúng keystore đã ký agent production. Script không tự tạo keystore.\n\n'
while true; do
  prompt 'Đường dẫn keystore cũ' "${MDM_RELEASE_STORE_FILE:-$HOME/.mdmesh-signing/release.jks}"
  if existing_file "$REPLY"; then KEYSTORE=$REPLY; break; fi
  retry_or_fail 'Keystore không tồn tại/không đọc được, hoặc đường dẫn chứa dấu phẩy.'
done
while true; do
  prompt 'Key alias' "${MDM_RELEASE_KEY_ALIAS:-mdmesh}"
  if [[ -n "$REPLY" ]]; then export MDM_RELEASE_KEY_ALIAS=$REPLY; break; fi
  retry_or_fail 'Key alias không được để trống.'
done
while true; do
  prompt 'APK production cũ để đối chiếu chữ ký và versionCode' "${MDM_REFERENCE_APK:-$ROOT/releases/android/1000/mdmesh-custom.apk}"
  if existing_file "$REPLY"; then REFERENCE=$REPLY; break; fi
  retry_or_fail 'Cần file APK production cũ có thể đọc được (MDM_REFERENCE_APK).'
done

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  printf '\nĐang tạo image JDK 17 / SDK 35 (chấp nhận license Android SDK)...\n'
  bash "$ROOT/scripts/android-docker.sh" --build-image
fi
signer() {
  docker run --rm --entrypoint /opt/android-sdk/build-tools/35.0.0/apksigner \
    --mount "type=bind,src=$1,dst=/input.apk,readonly" "$IMAGE" verify --print-certs /input.apk
}
REFERENCE_INFO=$(signer "$REFERENCE") || fail 'APK tham chiếu có chữ ký không hợp lệ.'
REFERENCE_CERT=$(printf '%s\n' "$REFERENCE_INFO" | awk '/Signer #1 certificate SHA-256 digest:/{print $NF; exit}')
[[ ${#REFERENCE_CERT} -eq 64 ]] || fail 'Không đọc được certificate SHA-256 của APK tham chiếu.'
REFERENCE_PACKAGE=$(docker run --rm --entrypoint /opt/android-sdk/build-tools/35.0.0/aapt \
  --mount "type=bind,src=$REFERENCE,dst=/input.apk,readonly" "$IMAGE" dump badging /input.apk)
REFERENCE_CODE=$(printf '%s\n' "$REFERENCE_PACKAGE" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
REFERENCE_ID=$(printf '%s\n' "$REFERENCE_PACKAGE" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[[ "$REFERENCE_ID" == com.mdmesh.agent ]] || fail "APK tham chiếu phải có package com.mdmesh.agent (đang là $REFERENCE_ID)."
[[ "$REFERENCE_CODE" =~ ^[0-9]+$ ]] || fail 'Không đọc được versionCode của APK tham chiếu.'
printf '\nAPK tham chiếu: versionCode=%s\n' "$REFERENCE_CODE"
VERSION_CODE=$(python3 "$ROOT/scripts/lib/release-version.py" "$REFERENCE_CODE" "${MDM_RELEASE_VERSION_CODE:-}" \
  "$ROOT/agent-android/app/build.gradle.kts" "$OUTPUT_ROOT" \
  "$ROOT/.android-docker/releases" "$ROOT/releases/android" "$ROOT/agent-android/app/build/distributions") \
  || fail 'Không chọn được versionCode tiếp theo.'
printf 'versionCode mới: %s\n' "$VERSION_CODE"
while true; do
  prompt 'versionName' "${MDM_RELEASE_VERSION_NAME:-1.0.1-custom}"
  if [[ "$REPLY" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]]; then VERSION_NAME=$REPLY; break; fi
  retry_or_fail 'versionName chỉ dùng chữ, số, dấu chấm, gạch ngang hoặc gạch dưới.'
done
while true; do
  prompt 'URL server HTTPS' "${MDM_BASE_URL:-https://mdm.hatbuinho.me/}"
  if python3 - "$REPLY" <<'PY'
import sys
from urllib.parse import urlsplit
try:
    u=urlsplit(sys.argv[1])
    assert u.scheme == 'https' and u.hostname and u.username is None and u.password is None
    assert not u.query and not u.fragment and not any(c.isspace() or c in '\\"' or ord(c)<32 for c in sys.argv[1])
except (AssertionError, ValueError):
    sys.exit(1)
PY
  then BASE_URL="${REPLY%/}/"; break; fi
  retry_or_fail 'Nhập URL https:// hợp lệ, không chứa credentials/query/fragment.'
done

mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT=$(realpath -- "$OUTPUT_ROOT")
[[ "$OUTPUT_ROOT" != *','* ]] || fail 'Đường dẫn output không được chứa dấu phẩy.'
OUT="$OUTPUT_ROOT/$VERSION_CODE"
[[ ! -e "$OUT" ]] || fail "Output đã tồn tại: $OUT. Chọn versionCode mới hoặc thư mục output khác."
printf '\nVersion: %s (%s)\nServer: %s\nOutput: %s\n' "$VERSION_NAME" "$VERSION_CODE" "$BASE_URL" "$OUT"

# Passwords from dotenv/environment bypass prompts and are forwarded by variable name.
if [[ -z "${MDM_RELEASE_STORE_PASSWORD:-}" ]]; then
  [[ $NON_INTERACTIVE == 0 ]] || fail 'Thiếu MDM_RELEASE_STORE_PASSWORD trong .env hoặc environment.'
  printf 'Keystore password (nhập ẩn): ' >&3
  IFS= read -r -s MDM_RELEASE_STORE_PASSWORD <&3 || exit 130
  printf '\n' >&3
fi
[[ -n "$MDM_RELEASE_STORE_PASSWORD" ]] || fail 'Mật khẩu không được để trống.'
if [[ -z "${MDM_RELEASE_KEY_PASSWORD:-}" && $NON_INTERACTIVE == 0 ]]; then
  printf 'Key password (Enter nếu giống keystore password): ' >&3
  IFS= read -r -s MDM_RELEASE_KEY_PASSWORD <&3 || exit 130
  printf '\n' >&3
fi
export MDM_RELEASE_STORE_PASSWORD
export MDM_RELEASE_KEY_PASSWORD="${MDM_RELEASE_KEY_PASSWORD:-$MDM_RELEASE_STORE_PASSWORD}"

KEY_CERT=$(docker run --rm --entrypoint bash \
  --mount "type=bind,src=$KEYSTORE,dst=/signing/release.jks,readonly" \
  -e MDM_RELEASE_STORE_PASSWORD -e MDM_RELEASE_KEY_ALIAS "$IMAGE" -c \
  'set -euo pipefail; keytool -exportcert -keystore /signing/release.jks -alias "$MDM_RELEASE_KEY_ALIAS" -storepass:env MDM_RELEASE_STORE_PASSWORD | sha256sum' \
  | awk '{print $1}') || fail 'Không mở được keystore/alias. Kiểm tra mật khẩu và quyền đọc file.'
[[ "$KEY_CERT" == "$REFERENCE_CERT" ]] || fail 'Keystore không khớp chữ ký APK production. Đã dừng trước khi build.'

mkdir "$OUT"
RESERVED=$OUT
WORK=$(mktemp -d "$OUTPUT_ROOT/.build-$VERSION_CODE.XXXXXX")
printf '\nChữ ký keystore khớp APK production. Đang build release...\n'
docker run --rm --init --memory=4g --cpus=2 \
  --mount "type=bind,src=$ROOT/agent-android,dst=/source,readonly" \
  --mount "type=bind,src=$WORK,dst=/output" \
  --mount "type=bind,src=$KEYSTORE,dst=/signing/release.jks,readonly" \
  --mount "type=volume,src=mdmesh-android-gradle-$(id -u)-$(id -g),dst=/home/builder/.gradle" \
  -e MDM_RELEASE_STORE_FILE=/signing/release.jks \
  -e MDM_RELEASE_STORE_PASSWORD -e MDM_RELEASE_KEY_ALIAS -e MDM_RELEASE_KEY_PASSWORD \
  "$IMAGE" --no-configuration-cache :app:assembleRelease "-PversionCode=$VERSION_CODE" "-PversionName=$VERSION_NAME" "-PmdmBaseUrl=$BASE_URL"
unset MDM_RELEASE_STORE_PASSWORD MDM_RELEASE_KEY_PASSWORD
APK="$WORK/app/build/outputs/apk/release/app-release.apk"
[[ -s "$APK" ]] || fail 'Không có APK release đã ký.'
INFO=$(signer "$APK") || fail 'APK mới không vượt qua kiểm tra chữ ký.'
CERT=$(printf '%s\n' "$INFO" | awk '/Signer #1 certificate SHA-256 digest:/{print $NF; exit}')
[[ "$CERT" == "$REFERENCE_CERT" ]] || fail 'Chữ ký APK mới không khớp APK production.'

# Prepare all deliverables before moving them into the reserved version directory.
cp "$APK" "$WORK/mdmesh-custom.apk"
python3 - "$WORK" "$VERSION_CODE" "$VERSION_NAME" "$CERT" "$BASE_URL" "${MDM_APK_URL:-${BASE_URL}files/mdmesh-custom.apk}" <<'PY'
import base64,hashlib,sys
from pathlib import Path
p=Path(sys.argv[1]); code,name,cert,server,url=sys.argv[2:]
checksum=base64.urlsafe_b64encode(bytes.fromhex(cert)).decode().rstrip('=')
sha=hashlib.sha256((p/'mdmesh-custom.apk').read_bytes()).hexdigest()
(p/'SHA256SUMS').write_text(sha+'  mdmesh-custom.apk\n')
(p/'web-build.env').write_text('VITE_AGENT_PACKAGE=com.mdmesh.agent\nVITE_AGENT_CHECKSUM='+checksum+'\nVITE_AGENT_APK_URL='+url+'\n')
(p/'release-info.txt').write_text(f'versionCode={code}\nversionName={name}\ncertificateSHA256={cert}\napkSHA256={sha}\nserverBaseUrl={server}\n')
PY
for name in mdmesh-custom.apk SHA256SUMS web-build.env release-info.txt; do mv "$WORK/$name" "$OUT/$name"; done
RESERVED=""
printf '\nBuild thành công; chữ ký APK khớp production.\nAPK: %s/mdmesh-custom.apk\nChecksum: %s/SHA256SUMS\nKhông tự upload APK hoặc cập nhật thiết bị.\n' "$OUT" "$OUT"
