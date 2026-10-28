MDMesh Enroll - Windows portable (Windows 10/11 x64)

1. Extract the whole ZIP to a writable folder (do not run inside the ZIP).
2. For enrollment, optionally set MDM_ADMIN_USERNAME in .env using Notepad.
   Leave MDM_ADMIN_PASSWORD blank to enter it at a hidden prompt. Do not use shell escapes.
3. Enable USB debugging on the phone, connect a data-capable cable, unlock it,
   and accept the computer's RSA authorization prompt.
4. Double-click Enroll.cmd to open the main menu:
     1. Install the latest APK and enroll a device.
     2. Inspect Android accounts and users/profiles.
     3. Guided account removal (you confirm on the phone).
     4. Grant/verify Agent permissions and enable GPS.
     0. Exit.
   Choose the function, then the device. Completed/failed actions return to the menu.
   Only enrollment requires MDMesh server credentials. Ctrl+C cancels the script.
5. Keep MDMesh Agent visible and the phone unlocked until enrollment completes.
   Enrollment automatically grants precise/background location and enables GPS,
   alongside Usage Access and Alarms & reminders. Each permission is verified.
   After enrollment, the Android phone name becomes the default MDMesh display
   name (model is the fallback). An existing MDMesh display name is preserved.

The bundle includes Python and ADB; no Python, Android Studio, Git Bash, pip,
PATH changes or administrator privileges are required for normal enrollment.
Launchers verify tool checksums. Missing/corrupt tools are downloaded by
Setup.ps1 from the pinned python.org / Google HTTPS URLs in tools-manifest.json.
Internet access to MDMesh is required to download the published APK and mint a
fresh enrollment token. The highest published release versionCode is selected;
local APKs are never used. APK SHA256 is verified before installation.
The helper reports download/checksum and ADB installation as separate steps.
Installation can take up to 180 seconds; keep the phone unlocked and check
any installation or Play Protect prompts. Temporary empty UI Automator dumps
are retried up to six times while the Agent screen becomes ready.
Downloads of the APK are temporary and removed on exit.
New enrollment is confirmed by the Agent's saved device ID/server, the matching
server device record, and Device Owner. It does not wait for the next periodic
check-in or depend on the visible connection-success label. An already registered
device with valid Device Owner is confirmed without minting another token.

Grant-Permissions.cmd: grant and verify Usage Access, Alarms & reminders, precise/background location, and enable GPS
for an already installed Agent, without API login or another enrollment.
Diagnose-Accounts.cmd: inspect current Android accounts and users/profiles only.
Manage-Accounts.cmd: choose an account by provider/name/profile, open settings,
then press Enter after removing/signing out on the phone to check again.
HONOR/HUAWEI ID opens its account app when available, otherwise general Settings;
use the manufacturer's account entry to sign out. Other providers open Android
sync/account settings. If unavailable, the helper falls back to general Settings.
Android can hide login names; the helper explains this and checks provider/profile
counts. Apps can recreate accounts. Never use WhatsApp's Delete my account to
resolve enrollment; back up/verify chat recovery before considering uninstallation.
Setup.cmd: verify/restore the portable tools only.

A device can only be set as Device Owner when Android allows it. Remove all
accounts first. The script never removes accounts or factory-resets the phone.
If ADB cannot see your phone, install its manufacturer's Windows USB driver:
https://developer.android.com/studio/run/oem-usb
Driver installation can require Administrator rights. Reconnect the phone,
check Device Manager, and accept USB debugging authorization on the phone.

Console account needs edit_devices. RSA login and two-factor flows are not
supported by the helper. The server's default new-device configuration is used.
An already enrolled device does not need another enrollment token. After installing
with adb install -r and restoring Device Owner, the helper opens the Overview tab
and reads the saved device/server from Support information. It reports recovery
only after the selected server receives a NEW check-in and Device Owner is verified.
It does not treat a previous successful check-in as proof of current connectivity.

If you only removed management on the web but cancelled Android's uninstall prompt,
the Agent still has its previous registration. The helper reuses that registration
when valid, instead of failing to find an enrollment form or creating a duplicate.
If you need a completely new registration, complete Android's uninstall first.
The helper never clears app data, overwrites another server, or factory-resets a device.

CMD usage (optional):
  Enroll.cmd --serial DEVICE_SERIAL
  Enroll.cmd --base-url https://mdm.example.com
  Enroll.cmd --enroll
  Enroll.cmd --diagnose-accounts
  Enroll.cmd --manage-accounts
  Grant-Permissions.cmd --serial DEVICE_SERIAL

The .env may contain the console password. Only enrollment settings are copied
by the builder; database, SMTP and signing credentials are excluded. Keep this
folder with the administrators who should have access to that console account.
No password/token is intentionally printed or written to logs by this helper.

Sources and licenses: Python LICENSE.txt is under tools/python; Android
Platform Tools license/NOTICE files are retained under tools/platform-tools.
Downloaded original archives are pinned by SHA256 in tools-manifest.json.

Enrollment prompts and messages use Vietnamese without accents for Windows
console compatibility. Android UI labels are still matched in their original
Vietnamese form. Authentication values and tokens are not transliterated.
