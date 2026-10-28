# MDM Agent (Android)

A from-scratch, self-hosted **custom DPC** (Device Owner) Android MDM agent.
Modern Kotlin: coroutines/Flow, Hilt, Room, DataStore, WorkManager,
kotlinx.serialization + Retrofit.

- `applicationId` / base namespace: `com.mdmesh.agent`
- `minSdk 24`, `targetSdk 35`, `compileSdk 35`
- Identity is a **server-issued device id** (DataStore). The agent never uses
  IMEI/IMSI/serial as identity (restricted post-Android 10).

> This scaffold will not build on a box without the Android SDK/Gradle. It is
> structured to compile cleanly in CI or a provisioned dev box once an SDK is
> present.

## Module map

| Module | Type | Responsibility |
|--------|------|----------------|
| `:proto` | Kotlin/JVM lib | `@Serializable` wire contract mirroring `../proto/` (`CapabilityMatrix`, `CommandEnvelope`, `CommandResult`, `ProtocolJson`). No Android deps. |
| `:policy` | android-lib | **Capability-abstraction layer.** `DeviceControl`, `PolicyStrategy`, SDK-gated `WifiPolicy` (modern/legacy strategies + factory), `CapabilityRegistry`. All `DevicePolicyManager` calls stay behind interfaces. |
| `:core` | android-lib | Sync/check-in: Retrofit `MdmApi`, `CapabilityCollector`, `CommandDispatcher` (+ handlers), `DeviceIdStore` (DataStore), `CheckInCoordinator`/`CheckInWorker`. Base URL via `BuildConfig`. |
| `:kiosk` | android-lib | COSU skeleton: `KioskController` (+ stub), `CrashLoopGuard`. |
| `:remote` | android-lib | Remote view/control skeleton: `RemoteControlSession`, `RemoteControlTierDetector`, and the **only** Accessibility surface (`InputInjectionService`). |
| `:oem` | android-lib | `OemAdapter` + `GenericOemAdapter` (no-op) + `KnoxAdapter` (PARKED, no Knox dep). |
| `:app` | android-app | Hilt `Application`, `AdminReceiver`, provisioning activities, `MainActivity` launcher/home stub, `CheckInService`, manifest with the minimal permission set. |

### The capability-abstraction intent

Android is a permanent version treadmill — every OS release adds, removes, or
restricts a policy API. Rather than scatter `Build.VERSION.SDK_INT` branches through
feature code, each policy area defines an interface (`WifiPolicy`) with multiple
`PolicyStrategy` implementations; a factory picks the supported one **once**. The
`CapabilityRegistry` reports only the keys that have a working strategy, and that
set becomes the `capabilities` advertised in the `CapabilityMatrix`. The server is
contractually forbidden from sending a command whose `requiresCapability` isn't
advertised, so a 3-year-old agent on Android 9 and a fresh agent on Android 16 talk
to the same server without special-casing. Unknown command types degrade to
`status=unsupported` instead of crashing.

### Permission minimization (Play Protect)

- Base `:app` manifest has **no** `READ_SMS` and **no** `QUERY_ALL_PACKAGES`;
  package visibility is scoped via `<queries>`.
- **Accessibility / input injection lives only in `:remote`** (and merges in only
  for builds that include it). Its `AccessibilityService` ships
  `android:enabled="false"` and is toggled on only for an authorised control session.
- No phone-state / device-identifier permissions — identity is server-issued.

## Build

### Docker (Linux build host)

From the repository root, run:

```bash
bash scripts/android-docker.sh
bash scripts/android-docker.sh lintDebug testDebugUnitTest :proto:test
```

The first invocation builds a local image with JDK 17, Android Platform 35,
Build Tools 34/35 and command-line tools 19.0, accepting Google's Android SDK
licenses. No host JDK, SDK, Android Studio or emulator is required.
Docker needs network access for the first image build and dependency downloads.

The debug APK is exported to
`.android-docker/output/app/build/outputs/apk/debug/app-debug.apk`.
Reports are exported under the corresponding module's `build/reports` directory.
Outputs from previous invocations remain there; check the build result before
using them. Gradle caches and the debug signing key persist in Docker volumes
named `mdmesh-android-gradle-<uid>-<gid>` and `mdmesh-android-home-<uid>-<gid>`.
Deleting the latter changes the debug signing key on the next build.

Source is mounted read-only and copied to a temporary container workspace,
excluding existing build directories and local SDK settings. The container runs
with your UID/GID, up to two CPUs, 4 GiB memory and two Gradle workers.
Rebuild the image after changing its Dockerfile or entrypoint:

```bash
bash scripts/android-docker.sh --build-image
```

For an interactive signed release build from the repository root:

```bash
bash scripts/build-android-release-docker.sh
```

The release helper asks for an existing keystore, alias, reference production APK,
version name, server URL and hidden passwords. It selects the next version code automatically and verifies that the keystore
certificate matches the reference APK before building, requires a higher version
code, and verifies the resulting APK again. The version code must also exceed
every installed version you intend to update. It never creates a new keystore,
overwrites an existing version output, or deploys the APK. Outputs are
`.android-docker/releases/<versionCode>/mdmesh-custom.apk`, `SHA256SUMS`,
`web-build.env` and `release-info.txt`.

For an unattended build, put release settings in the repository root `.env`
(already ignored by Git), or select a file with `--env-file`:

```dotenv
MDM_RELEASE_STORE_FILE=/absolute/path/to/release.jks
MDM_RELEASE_KEY_ALIAS=mdmesh
MDM_RELEASE_STORE_PASSWORD='your-keystore-password'
MDM_RELEASE_KEY_PASSWORD='your-key-password'
MDM_REFERENCE_APK=/absolute/path/to/current-production.apk
MDM_RELEASE_VERSION_CODE=auto
MDM_RELEASE_VERSION_NAME=1.0.23-custom
MDM_BASE_URL=https://mdm.example.com/
```

```bash
bash scripts/build-android-release-docker.sh --non-interactive
# Or use another dotenv file:
bash scripts/build-android-release-docker.sh --non-interactive --env-file /path/to/release.env
```

Exported environment variables take precedence over file settings. Only release
settings are read; dotenv values are literal, including `$`, backticks and `#`
inside quotes. Shell commands and variable interpolation are never evaluated.
In automatic mode, an omitted key password uses the store password. Missing
passwords or invalid settings fail immediately. Version codes default to `auto`:
both helpers select the highest code among the reference APK (when supplied),
Gradle's default, and numeric version directories in `releases/android/`,
`.android-docker/releases/`, the configured output root, and native distributions,
then add exactly one. Repeated releases increase the code even when using the
same reference APK. An explicit numeric override must exceed every known code.
Use a reference APK at least as recent as the newest installed version when that
version is not present in local release history. Existing outputs are
never overwritten. Passwords are not printed, copied into release artifacts, or
passed as command-line literals. Gradle configuration caching is disabled for
signed builds.

The native `build-android-release.sh` helper also reads `.env` and accepts
`--env-file` and `--non-interactive`; versionCode is automatic, and versionName
comes from its argument or `MDM_RELEASE_VERSION_NAME`.
Automatic native builds require an existing keystore. Use `--help` for details.

### Enroll an ADB Device Owner

From the repository root, prepare a USB-debugging device with:

```bash
bash scripts/enroll-device-adb.sh
# Select a device explicitly:
bash scripts/enroll-device-adb.sh --serial DEVICE_SERIAL
```

The interactive script defaults to `https://mdm.hatbuinho.me` (or `MDM_BASE_URL`
from root `.env`). It logs into the API and selects the highest `versionCode`
for `com.mdmesh.agent` from the server's published release catalog
(`GET /rest/private/agent/v1/releases`). It downloads that APK over HTTPS to a
temporary directory and verifies its SHA256 against the catalog before installing.
The temporary download is removed when the script exits. An empty catalog or
checksum failure stops enrollment; local build artifacts are not used.
It lets you choose the connected device, installs the APK, verifies Device Owner,
grants and verifies Usage Access plus Alarms & reminders (Android 12+), and creates a fresh single-use enrollment token. It fills the server URL/token through the agent UI and submits enrollment,
then waits up to 60 seconds for the agent to confirm management and connection.
Keep the device unlocked while the script runs. Initial Device Owner setup requires
a device without accounts; the script never resets it.

To grant only these two permissions to an already installed agent, without
logging into the server or enrolling again:

```bash
bash scripts/enroll-device-adb.sh --permissions-only
# Or choose a device explicitly:
bash scripts/enroll-device-adb.sh --permissions-only --serial DEVICE_SERIAL
```

Put console credentials in the ignored root `.env` (literal values; no shell expansion):

```dotenv
MDM_BASE_URL=https://mdm.hatbuinho.me/
MDM_ADMIN_USERNAME=admin
MDM_ADMIN_PASSWORD='your-console-password'
```

Exported environment values take precedence. Missing credentials are prompted;
password input is hidden. Credentials and tokens are not printed or saved in logs.
Use `--env-file PATH` for another dotenv file, or `--base-url URL` to change servers.
The API account needs `edit_devices`; RSA login and two-factor flows are not
supported. Tokens use the server's default new-device configuration. No APK rebuild
is required for this helper (the enrollment UI is available in release 1026).

For a device already set as Device Owner with ADB, install an agent build that has
the **ADB enrollment** section on its status screen. Open the web console's
**Enroll → Token** tab and generate a fresh, single-use token. On the device, open
MDMesh Agent, enter that token and the HTTPS server URL, then tap **Enroll this
device**. The agent saves the token privately, prompts for Usage access if needed,
and checks in immediately. The device ID appears on the agent screen after success.
An existing Device Owner installation can be updated with `adb install -r` using an
APK signed by the same release key and a higher versionCode; setting Device Owner
again is unnecessary.

### Local JDK / SDK

```bash
cd agent-android
./gradlew assembleDebug
./gradlew test          # :proto + :core JVM unit tests
```

The repository includes the Gradle wrapper JAR and scripts. Install JDK 17 and
Android SDK Platform 35, then set `JAVA_HOME` and `ANDROID_HOME` before building.

Release signing reads from env vars (`MDM_RELEASE_STORE_FILE`,
`MDM_RELEASE_STORE_PASSWORD`, `MDM_RELEASE_KEY_ALIAS`, `MDM_RELEASE_KEY_PASSWORD`);
see the `// TODO(keystore custody)` note in `app/build.gradle.kts`. The DO binding
is tied to the signing certificate — re-signing a deployed DPC with a different key
forces a factory reset of every enrolled device, so guard the release key carefully.

## ADB Device-Owner dev enrollment loop

Device Owner can only be set on a device/emulator with **no accounts** (fresh or
factory-reset). Then:

```bash
# 1. Install the agent.
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Bind it as Device Owner (note the .debug applicationIdSuffix on debug builds).
adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver
#   release build would be:
#   adb shell dpm set-device-owner com.mdmesh.agent/com.mdmesh.agent.admin.AdminReceiver

# 3. Confirm.
adb shell dumpsys device_policy | grep -i "Device Owner"
```

If `set-device-owner` fails with "Not allowed to set the device owner because
there are already some accounts" — remove all accounts, or factory reset.

### Getting OFF Device Owner (dev cycle)

A Device Owner cannot simply be uninstalled. To clear it:

```bash
# Clears the DO binding (works on debug/userdebug builds).
adb shell dpm remove-active-admin com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver

# If that is blocked, factory reset:
adb shell am broadcast -a android.intent.action.MASTER_CLEAR   # or wipe via Settings / recovery
```

On an emulator, the fastest reset is **Wipe Data** (cold boot) from the AVD
manager, then re-run the enrollment loop.

## Real provisioning (production)

Production enrollment is via QR / NFC / zero-touch using the
`GET_PROVISIONING_MODE` + `ADMIN_POLICY_COMPLIANCE` activities (already wired). The
ADB loop above is for the dev inner loop only.

### Publish the newest APK to MDMesh

After building a signed release, preview the selected APK, then upload it:

```bash
python3 scripts/publish-release.py --dry-run
python3 scripts/publish-release.py --base-url https://mdm.example.com --username admin
# Or select an APK explicitly:
python3 scripts/publish-release.py --base-url https://mdm.example.com --apk /path/to/mdmesh-custom.apk
```

Requires Python 3.9+. The script asks for your MDMesh password without displaying it.
For unattended use, supply `MDM_PUBLISH_PASSWORD` through the environment; optionally
set `MDM_PUBLISH_USERNAME` and `MDM_BASE_URL`. It reads release settings from root `.env`
(or `--env-file PATH`); exported settings override that file. Credentials are not read
from dotenv. Remote servers require HTTPS; local HTTP is supported for development.

The default APK is the highest numeric version directory containing `mdmesh-custom.apk`
in `.android-docker/releases`, `releases/android`, native build distributions, or
`MDM_RELEASE_OUTPUT_ROOT`. Conflicting APKs at the highest version require `--apk`.
The script checks `SHA256SUMS` when present, logs in, skips an identical SHA-256 already
in the catalog, and uploads to `/rest/private/agent/v1/releases`. The server verifies
the APK signature and metadata. The account needs `edit_devices` permission; RSA login
and two-factor flows are not supported by this helper.

Publication adds the APK to **Agent releases & rollout**. Start a rollout in the console
to update devices. This command does not change the enrollment APK URL or rebuild the web image.

### Windows portable ADB enrollment

The portable distribution runs on Windows 10/11 x64 without installing Python,
Android Studio or modifying PATH. Extract the whole ZIP and double-click
`Enroll.cmd` to choose enrollment, account diagnostics, guided account removal,
or Agent permission grants from the main menu. It uses the published server APK
and the same API enrollment flow as the shell helper. `Grant-Permissions.cmd`
grants Usage Access, Alarms & reminders, precise/background location and enables
GPS. `Diagnose-Accounts.cmd` and `Manage-Accounts.cmd` open the account functions
directly without server login. Tools are pinned by SHA256 and included in the full bundle;
`Setup.cmd` verifies/restores them from official Python/Google HTTPS downloads.
Some phones require the manufacturer's Windows USB driver.

Build the folder and ZIP on macOS, Linux or Windows with Python 3.10+:

```bash
python3 scripts/build-windows-enroll-portable.py
# Copy only console URL/username/password from the project's .env:
python3 scripts/build-windows-enroll-portable.py --env-file .env --include-credentials
# Small bootstrap bundle: tools are downloaded on first launch in Windows:
python3 scripts/build-windows-enroll-portable.py --without-tools --output releases/portable-bootstrap
```

Output defaults to `releases/portable-windows/MDMesh-Enroll/` and
`releases/portable-windows/MDMesh-Enroll-Windows.zip`, plus `SHA256SUMS`.
Existing outputs are not overwritten; choose another `--output` directory for a
new bundle. Without `--include-credentials`, the password is blank and prompted
on use. Database/SMTP/signing settings are never copied. Tool cache location can
be selected with `--cache PATH`. Source launchers and download pins live under
`scripts/windows-portable/`. PowerShell 5.1 downloads tools only when required;
no pip packages are needed. Enrollment still requires internet access to MDMesh.
