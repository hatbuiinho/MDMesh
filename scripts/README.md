# ADB enrollment diagnostics

Run without arguments to open the main menu:

```sh
bash scripts/enroll-device-adb.sh
```

Choose installation/enrollment, account diagnostics, guided account removal, or
Agent permission grants/GPS. Each completed or failed action returns to the menu;
choose `0` to exit. Ctrl+C cancels the script. You can pass `--serial` to reuse a
specific device across actions. The menu does not require a server login; only
installation/enrollment uses the server.

Existing action flags still run directly. Use `--enroll` to run installation and
enrollment directly without the menu. `--help` lists all options.

If Android blocks Device Owner because accounts remain during enrollment, the
helper offers guided account removal immediately. Choose `1` to select an account
and handle it on the phone, `r` to retry Device Owner, or `q` to stop enrollment.
After the account list is empty it retries Device Owner and verifies ownership
before continuing permissions and registration. It keeps the installed APK and
does not repeat download/installation during this recovery. If an app recreates
an account, the same choices appear again. Other Device Owner errors are reported
without treating them as account-removal problems.

To inspect Android accounts without server login, APK installation, or enrollment:

```sh
bash scripts/enroll-device-adb.sh --diagnose-accounts
# Select one device explicitly when needed:
bash scripts/enroll-device-adb.sh --serial DEVICE_SERIAL --diagnose-accounts
```

For guided removal with confirmation on the phone:

```sh
bash scripts/enroll-device-adb.sh --manage-accounts
```

Select an account by its displayed provider/name/profile. The script opens Android
account settings, or the manufacturer's account app when it has a launcher. If
that screen is unavailable, it falls back to general Settings and prints directions.
It targets the selected Android user without switching or deleting profiles. Unlock
the phone/profile if necessary. Sign out or remove the sync account on the phone,
then press Enter in the terminal to read the account list again. Choose `r` to refresh
or `q` to exit. Masked names are checked by account type and profile counts; the script
does not claim it can identify a particular masked login. Accounts can reappear later.
This mode does not log in to the server, install an APK, or attempt enrollment.

The same report appears automatically when Android rejects `set-device-owner`
because accounts remain. It lists current accounts by provider/type and Android
user/profile, not historical accounts or merely registered authenticator services.
Android may mask login names; the report explains this rather than treating the
mask as a real login name. Unknown providers retain their account type for identification.
An unreadable or empty result does not guarantee Device Owner eligibility.

If Android Settings shows no accounts, check the manufacturer's account entry
(for example HONOR ID at the top of Settings), the corresponding app, and other
Android profiles. Remove/sign out through the appropriate account settings, then
rerun diagnostics. Apps can recreate sync accounts. For WhatsApp, do not use
Delete my account as an enrollment workaround. If uninstalling is necessary,
first back up chats and verify how to restore them. The script never removes
accounts, apps, profiles, or device data.
