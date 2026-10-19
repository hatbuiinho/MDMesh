#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
IMAGE="mdmesh-android-build:local-$(id -u)-$(id -g)"
CACHE="mdmesh-android-gradle-$(id -u)-$(id -g)"
ANDROID_CACHE="mdmesh-android-home-$(id -u)-$(id -g)"

if [[ "${1:-}" == --help ]]; then
  cat <<'EOF'
Usage: bash scripts/android-docker.sh [--build-image | Gradle tasks/options...]
Default: :app:assembleDebug
Examples:
  bash scripts/android-docker.sh --build-image
  bash scripts/android-docker.sh :app:assembleDebug
  bash scripts/android-docker.sh lintDebug testDebugUnitTest :proto:test
Outputs: .android-docker/output/ (module/variant subdirectories)
Requires Docker Engine. First image build accepts Google's Android SDK licenses.
EOF
  exit 0
fi

if [[ "${1:-}" == --build-image ]] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  docker build --build-arg "BUILD_UID=$(id -u)" --build-arg "BUILD_GID=$(id -g)" \
    -f "$ROOT/docker/android-build.Dockerfile" -t "$IMAGE" "$ROOT/docker"
fi
[[ "${1:-}" != --build-image ]] || exit 0
mkdir -p "$ROOT/.android-docker/output"
[[ $# -gt 0 ]] || set -- :app:assembleDebug
docker run --rm --init --memory=4g --cpus=2 \
  --mount "type=bind,src=$ROOT/agent-android,dst=/source,readonly" \
  --mount "type=bind,src=$ROOT/.android-docker/output,dst=/output" \
  --mount "type=volume,src=$CACHE,dst=/home/builder/.gradle" \
  --mount "type=volume,src=$ANDROID_CACHE,dst=/home/builder/.android" \
  "$IMAGE" "$@"
