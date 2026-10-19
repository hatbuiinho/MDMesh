#!/usr/bin/env bash
set -euo pipefail

# Work from a clean copy: old host build files can belong to root or reference
# SDK paths from another machine. Gradle's dependency/build cache persists.
work=$(mktemp -d /tmp/mdmesh-android.XXXXXX)
trap 'rm -rf "$work"' EXIT
tar -C /source --exclude=.gradle --exclude=.kotlin --exclude=build \
  --exclude=local.properties --exclude='*.jks' --exclude='*.keystore' -cf - . \
  | tar -C "$work" -xf -
cd "$work"
bash ./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process "$@"

# Preserve the module/variant layout when exporting APKs, bundles and reports.
find . -type f \( -path '*/build/outputs/*' -o -path '*/build/reports/*' \) \
  -exec cp --parents '{}' /output/ \;
