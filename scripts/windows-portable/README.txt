MDMesh Enroll - Windows portable (Windows 10/11 x64)

1. Extract the whole ZIP to a writable folder (do not run inside the ZIP).
2. Open .env in Notepad. Set MDM_ADMIN_USERNAME and MDM_ADMIN_PASSWORD.
   A blank password enables a hidden password prompt. Do not use shell escapes.
3. Enable USB debugging on the phone, connect a data-capable cable, unlock it,
   and accept the computer's RSA authorization prompt.
4. Double-click Enroll.cmd and choose your device.
5. Keep MDMesh Agent visible and the phone unlocked until enrollment completes.

The bundle includes Python and ADB; no Python, Android Studio, Git Bash, pip,
PATH changes or administrator privileges are required for normal enrollment.
Launchers verify tool checksums. Missing/corrupt tools are downloaded by
Setup.ps1 from the pinned python.org / Google HTTPS URLs in tools-manifest.json.
Internet access to MDMesh is required to download the published APK and mint a
fresh enrollment token. The highest published release versionCode is selected;
local APKs are never used. APK SHA256 is verified before installation.
Downloads of the APK are temporary and removed on exit.

Grant-Permissions.cmd: grant and verify Usage Access and Alarms & reminders
for an already installed Agent, without API login or another enrollment.
Setup.cmd: verify/restore the portable tools only.

A device can only be set as Device Owner when Android allows it. Remove all
accounts first. The script never removes accounts or factory-resets the phone.
If ADB cannot see your phone, install its manufacturer's Windows USB driver:
https://developer.android.com/studio/run/oem-usb
Driver installation can require Administrator rights. Reconnect the phone,
check Device Manager, and accept USB debugging authorization on the phone.

Console account needs edit_devices. RSA login and two-factor flows are not
supported by the helper. The server's default new-device configuration is used.
An already enrolled device does not need another enrollment token.

CMD usage (optional):
  Enroll.cmd --serial DEVICE_SERIAL
  Enroll.cmd --base-url https://mdm.example.com
  Grant-Permissions.cmd --serial DEVICE_SERIAL

The .env may contain the console password. Only enrollment settings are copied
by the builder; database, SMTP and signing credentials are excluded. Keep this
folder with the administrators who should have access to that console account.
No password/token is intentionally printed or written to logs by this helper.

Sources and licenses: Python LICENSE.txt is under tools/python; Android
Platform Tools license/NOTICE files are retained under tools/platform-tools.
Downloaded original archives are pinned by SHA256 in tools-manifest.json.
