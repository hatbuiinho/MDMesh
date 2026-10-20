# Network access policies and unblock requests

This feature manages network access only. Application launch, daily limits and rest schedules remain in App Usage.

## Admin workflow

In an existing configuration, **Truy cập mạng theo ứng dụng / nhóm** adds named policies. Each policy selects all managed apps or a named set of apps through App Library / Play Store metadata search. Saving does not download or install packages. Missing library values remain visible and removable. These rules are saved separately from the configuration's legacy Network fields; both apply simultaneously. Any matching deny wins across overlapping policies.

The Agent's **Truy cập mạng → Xem truy cập bị chặn / gửi yêu cầu** lists DNS denials grouped by app. A row contains the hostname, matching policy names, last occurrence and count. It does not collect browsing paths, query strings or chat contents. Events stay on the device (at most 300; debug builds also log host/owner/decision locally); only an explicit request sends hostname, app identity and reason to the server. A missing owner is shown as **Chưa xác định ứng dụng**, never inferred from the foreground app.

Users supply a reason. Requests queue durably while offline; WorkManager retries network failures. Duplicate pending requests are merged by a database unique index. Submission is bounded to 30 requests/hour/device. Invalid or obsolete queued submissions are shown as failed and are not silently expanded to another scope.

Dashboard **Truy cập mạng** filters requests by status and device/app/hostname search. Administrators with `edit_devices` can approve/reject/revoke only for accessible devices. Configuration edits additionally require configuration access. The same private endpoints power the Agent's admin login. Admin authentication is shared throughout the Agent, including the home screen. Passwords are never persisted. The server-bound session cookie is encrypted using Android Keystore and survives screen navigation and process restart. Explicit logout clears it across all screens; HTTP 401/403 from an authenticated API clears the rejected session. Logout invalidation on the server is best effort if offline. Accounts requiring two-factor/password reset must complete that flow in the dashboard.

Approval defaults to the identified requesting app and exact hostname on this device. Admins may explicitly choose a current named policy group containing that app, or all managed apps on this device. Unknown ownership cannot be approved as a specific app/group; the admin must deliberately select the broader device scope or reject. This restriction also applies server-side.

Durations: 15 minutes, 1 hour, end of day in the explicitly shown timezone, or **Vô thời hạn — đến khi admin thu hồi**. Null expiry means indefinite. Group membership is snapshotted at approval, not expanded when new packages join a group. Grants never edit the shared configuration. The history preserves approval/rejection/revocation actor, scope, expiry and note.

## Application and policy identity

Server payload `webAccess` includes legacy mode/domains/scope/packages, additional `rules`, a device-specific `policyHash` and active `grants`. `device.networkAccess` capability is required whenever rules or grants are delivered, and old agents cannot receive an approval. Legacy-only filtering continues to require `device.webFilter`.

Grants bind to the device, configuration and current network policy generation. Unrelated settings and normal sync/reboot preserve indefinite grants. A network policy change, disable, or observed configuration change advances the generation; older requests/grants become stale. Restoring previous policy content after a recorded change does not resurrect old approvals. Policy names are part of policy identity. Admins must create a new approval under the new policy.

An exception permits exactly its hostname, not subdomains, for its snapshotted package set. `packages: ["*"]` is reserved for an explicitly approved device-wide exception. An active exception overrides all network denials for that exact host within its package scope; other hostnames and App Usage are unaffected. Expiry is checked on every DNS query, including offline. Revocation needs a device sync. The Agent acknowledges `webAccess: applied` only once the tunnel reader is serving the requested policy (or no selected packages are installed). Dashboard compares the desired revision to the Agent's fully applied revision; local UI additionally checks live tunnel state.

## DNS limits and attribution

This remains a DNS-only split tunnel. It does not inspect HTTPS contents or stop existing TCP connections. External DNS/DoH or direct IP connections may bypass it. DNS decisions are enforced for new queries; closing/reopening an app may be needed after expiry/revocation. The feature does not promise full firewall enforcement.

Realme investigation (2026-10-20): after revoking the TikTok exceptions, the desired and applied configuration revisions matched and the latest command completed with `webAccess: applied`. Direct queries to the running VPN DNS for `tiktok.com`, `www.tiktok.com` and `im-ws-sg.tiktok.com` returned NXDOMAIN; a control hostname resolved. A warm Chrome process could still render TikTok. After force-stopping only Chrome, a fresh TikTok navigation did not load content while a control page loaded normally. This supports a retained browser connection/cache explanation; it does not distinguish the exact cache or protocol. A successful revocation acknowledgement confirms the DNS policy was installed, not that existing HTTPS/QUIC flows were terminated. Immediate session termination requires additional connection-level enforcement, not repeated configuration syncs.

On Android 10+, the active VPN calls `ConnectivityManager.getConnectionOwnerUid` on the UDP tuple and resolves an unambiguous package. System-owned sockets, shared UIDs, older Android versions or failed lookups remain unknown. For unknown owners, the Agent conservatively applies potentially matching denials; app/group-specific grants cannot bypass them. This can restrict more apps than intended on a device whose system resolver cannot be attributed. Confirm behavior on target Android/OEM versions before enabling distinct per-app policies broadly.

For per-app rules/grants, forwarded DNS answers carry zero cache TTL (EDNS flags preserved), so a resolver cache shared by the VPN cannot carry one app's permitted answer into another or beyond a grant's expiry. Replacing a policy establishes the replacement tunnel before closing its predecessor, avoiding file-descriptor reuse / always-on recovery races. Bounded parallel forwarding prevents a slow permitted lookup from holding the DNS reader; overload returns SERVFAIL. This does not remove app-internal caches or existing connections. Legacy-only filtering retains upstream TTLs.

## REST

All responses use the normal `{status,message,data}` envelope.

- `GET/PUT /rest/private/network-access/configuration/{id}`: policy rules.
- `GET /rest/private/network-access/requests?device={number}`: 500 rows per page (`page=1` by default), tenant- and device-access-filtered requests, effective status and application support/sync flags.
- `POST /rest/private/network-access/requests/{id}/decision`: `action` approve/reject/revoke; approval carries `duration` 15M/1H/TODAY/FOREVER, `timezone` for TODAY, `scope` APP/GROUP/ALL, optional `ruleId`, and `note`.
- `GET /rest/private/network-access/requests/{id}/history`: decision audit.
- `GET/POST /rest/public/agent/v1/network-access/{deviceNumber}`: device-owned request history/submission authenticated by the enrollment Bearer secret. Submission contains `domain`, `packageName` (empty if unknown), `policyHash`, `reason`.

## Validation and deployment

Run PostgreSQL-backed tests using a separate disposable database:

```bash
NETWORK_ACCESS_TEST_JDBC=jdbc:postgresql://127.0.0.1:55437/postgres \
REST_SCHEDULE_TEST_JDBC=jdbc:postgresql://127.0.0.1:55437/postgres \
mvn -pl server -am test
```

The database fixtures use `postgres` / `rest-test` and create/drop isolated schemas. They execute the real migration, mapper and DAO, including indefinite grants, expiry, conditional decisions/audit, tenant/device scoping and policy generation changes.

Android JVM tests exercise overlapping rules, exact-host grants, unknown owners, indefinite/expired scopes, DNS TTL rewriting and EDNS preservation. An opt-in real VPN test uses two separate helper APK UIDs on an isolated Device Owner emulator:

```bash
ANDROID_SERIAL=emulator-5580 bash scripts/tests/network-access-device.sh
```

The runner refuses physical devices, installs test-only helpers and the debug Agent, provisions that emulator as Device Owner, and checks actual DNS packets, app isolation and expiry. Use a disposable emulator with no accounts. It does not modify release provisioning or physical devices.

Deployment order: back up the database, deploy server with Liquibase migration `26.10.20-network-access-requests`, deploy dashboard, then roll out a consistently signed Agent advertising `device.networkAccess`. Validate a limited device group first, including both system DNS and direct DNS app traffic. Do not enable new per-app rules on the fleet before compatible agents and OEM attribution behavior are verified. This repository implementation does not automatically deploy to production or update an enrolled phone.

### Agent inbox and screen-on admin notifications

The network access screen has personal tabs (blocked traffic and submitted requests) and admin tabs (pending, active grants, history). Rows show the installed application icon and name, hostname and short status. Details, request reasons, login and approval forms open in bottom sheets. System bars, display cutouts and the keyboard contribute to screen insets. Admin requests span devices visible to the signed-in account; grant groups are loaded from the request's configuration rather than the phone's local policy. Application names fall back to the MDMesh application library where the account can read it, then to the preserved package name.

The home screen and management screens open the same account screen. Admin sessions are encrypted and shared across screens; navigating away no longer logs the account out. The server controls session validity (the current web.xml session timeout is 1440 minutes of inactivity). Changing the configured server clears the local session; request cookies are checked against the bound server before transmission. Receiving notifications is a separate explicit opt-in from the admin account menu. It requires Android notification permission. The backend issues a 30-day receive-only credential, stored encrypted using Android Keystore. The credential cannot approve requests or authenticate a device. Password changes, removal of the management permission, expiry and explicit logout invalidate reception. The notification API returns only a pending count and newest request ID after device visibility and policy checks; details and decisions require admin login.

A foreground service checks when the screen turns on/unlocks, listens for WebSocket wake hints while interactive, and falls back to a 30-second interactive poll. It closes the socket and cancels polling when the screen turns off. It uses no FCM, wake lock or scheduled sleep-time polling. Android displays an ongoing low-priority service notification. Network availability and OS/vendor restrictions determine when checks can complete; force-stopping the app requires reopening it. Duplicate alerts are suppressed for already notified request IDs. Lock-screen previews contain no hostname, app or device details.

Deploy the backend (including Liquibase change `26.10.20-network-admin-notifications`) before distributing the updated Agent. New endpoints: authenticated `POST /private/network-access/notifications/register`, receive-only `GET /public/network-access-notifications` and `POST /public/network-access-notifications/revoke`, and WebSocket `/agent/ws/admin-notifications`. Existing enrollment credentials and request/decision APIs remain separate. No Firebase configuration is needed.
