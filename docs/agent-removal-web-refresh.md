# Agent removal and renewable web sessions

Deploy the updated server and web together. Install an APK built from this change
before attempting removal; existing APKs do not implement `device.uninstallAgent`.
The first supporting release is **1.11.13 / build 1044** (catalog release 62).
The new action appears in a device's Remote Actions when its advertised capabilities
include `device.uninstallAgent`. No new database migration is required beyond the
existing `agent-admin-refresh-sessions-v1` migration.

## Remove an Agent

Choose **Remove MDM Agent**, type **REMOVE**, and confirm. The server requires device
edit permission and rejects this command in bulk. The API request is:

```json
{"type":"device.uninstallAgent","requiresCapability":"device.uninstallAgent"}
```

The Agent exits kiosk, releases Device Owner/admin privileges, clears its saved
configuration, and requests Android's uninstall screen. The user must confirm on
the device. From **1.11.14 / build 1045**, the notification includes **Mở xác nhận gỡ**.
Tap it (or the notification body), then select **Gỡ ứng dụng** in the foreground
removal screen. This opens Android’s uninstall confirmation with an app-settings
fallback if the uninstaller cannot be launched. If Android blocks opening the
screen from the background, use the notification or uninstall from Android app settings. Notifications
require permission. The command's `done` result confirms the removal request,
not that the APK was removed. Management cannot be restored remotely once owner
privileges have been released, even if uninstall is cancelled. A work-profile
owner is rejected without deleting the profile. This operation does not factory reset.

Android documents `clearDeviceOwnerApp` as deprecated and intended for testing;
this path therefore needs validation on each supported OEM/Android release:
https://developer.android.com/reference/android/app/admin/DevicePolicyManager#clearDeviceOwnerApp(java.lang.String)

## Web sessions

The SPA registers a renewable session after a completed password login. The
15-minute idle servlet session is renewed using a rotating opaque credential with
an absolute 30-day lifetime. Credentials are stored hashed in the existing refresh
session tables. Web credentials are kept in an HttpOnly, SameSite=Strict cookie
scoped to `/rest/public/web-session`, with Secure enabled for HTTPS (including
HTTPS reverse proxies that set X-Forwarded-Proto). Serve the SPA and API from the
same origin. Refresh credentials never enter localStorage or a JSON response.

On a protected 401/403 the client checks session status, renews an expired session,
and retries once. Valid-session permission denials do not rotate credentials.
Concurrent requests share renewal; Web Locks serialize renewal between browser
tabs where supported. Browsers without Web Locks coordinate within one tab only.
Expired/revoked refresh credentials return the UI to login. Logout revokes the
refresh family even if the access session has expired. Password/auth-token and
2FA-setting changes invalidate the family. Accounts with read-only roles can
renew web sessions without acquiring Agent admin permissions.

Existing signed-in sessions gain renewal after signing in again. The current SPA
has no completed 2FA UI; a pending 2FA or forced password reset cannot register a
refresh family. The server registration endpoint can be called after completion.

Validation:

```sh
npm run build --prefix web
node --test web/tests/session.test.mjs
mvn -pl server -am test -Dtest=WebSessionResourceTest,AgentAdminSessionResourceTest,AgentAdminSessionDAOTest -Dsurefire.failIfNoSpecifiedTests=false
bash scripts/android-docker.sh :app:compileDebugKotlin :core:testDebugUnitTest :proto:test
```

Before production rollout, verify removal on a Device Owner test device (including
kiosk, locked screen, blocked notifications, and cancelled uninstall), then verify
expired-session renewal and explicit logout through the deployed HTTPS proxy.
