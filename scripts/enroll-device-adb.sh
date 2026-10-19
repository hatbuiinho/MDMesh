#!/usr/bin/env bash
# macOS / Linux entry point; Python standard library only.
set -euo pipefail
set +x
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$ROOT/scripts/enroll-device-adb.py" "$@"
