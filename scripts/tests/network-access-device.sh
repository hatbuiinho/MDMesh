#!/usr/bin/env bash
set -euo pipefail
# This runner is deliberately limited to an isolated emulator. It never provisions a physical device.
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the isolated emulator, e.g. emulator-5580}"
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo 'Refusing to provision a physical device.' >&2; exit 2 ;; esac
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
sdk_root="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
build_tools="$sdk_root/build-tools/35.0.0"
android_jar="$sdk_root/platforms/android-35/android.jar"
probe_root="$(mktemp -d /tmp/mdmesh-network-probe.XXXXXX)"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$sdk_root/platform-tools:$PATH"
"$repo_root/agent-android/gradlew" -p "$repo_root/agent-android" :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
mkdir -p "$probe_root/classes" "$probe_root/dex"
javac -source 8 -target 8 -classpath "$android_jar" -d "$probe_root/classes" "$repo_root/scripts/tests/fixtures/ProbeActivity.java"
"$build_tools/d8" --lib "$android_jar" --output "$probe_root/dex" "$probe_root/classes/com/mdmesh/probe/ProbeActivity.class"
for role in chat browser; do
  package="com.mdmesh.probe.$role"
  cat > "$probe_root/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.INTERNET"/><application android:label="MDMesh test $role" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name="com.mdmesh.probe.ProbeActivity" android:exported="true"/></application></manifest>
EOF
  "$build_tools/aapt2" link -I "$android_jar" --manifest "$probe_root/AndroidManifest.xml" -o "$probe_root/$role-unsigned.apk"
  python3 - "$probe_root/$role-unsigned.apk" "$probe_root/dex/classes.dex" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'a') as apk:apk.write(sys.argv[2],'classes.dex')
PY
  "$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$probe_root/$role.apk" "$probe_root/$role-unsigned.apk"
  adb -s "$ANDROID_SERIAL" install -r "$probe_root/$role.apk"
done
adb -s "$ANDROID_SERIAL" install -r "$repo_root/agent-android/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$ANDROID_SERIAL" install -r "$repo_root/agent-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ! adb -s "$ANDROID_SERIAL" shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver; then
  adb -s "$ANDROID_SERIAL" shell dumpsys device_policy | rg -q 'com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver'
fi
result_file="$probe_root/instrumentation.txt"
adb -s "$ANDROID_SERIAL" shell am instrument -w -e class com.mdmesh.agent.web.NetworkAccessDeviceTest com.mdmesh.agent.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_file"
rg -q 'OK \(2 tests\)' "$result_file"
