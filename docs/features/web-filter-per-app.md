# Web filter by application

In Configuration → Network, set **Web access** to an allowlist or blocklist,
enter domains (one per line), and choose **Selected apps** under **Apply web filter
to**. Use **Select apps from Library or Play Store** to search and add Android apps by name.
Choosing a Play Store result saves package metadata to App Library without downloading
or installing an APK, so the app can be selected before it exists on the device.
The server stores their package IDs; existing package IDs missing from the Library
remain visible until an administrator removes them.
The selected apps must run MDMesh Agent 1.11.3 (versionCode 1025) or newer.

The agent applies the domain rule through a DNS-only local VPN. Apps outside the
selected package list keep their normal network route. A package that is not yet
installed is ignored until its installation triggers configuration reapplication.
If none of the selected packages is installed, the agent leaves the VPN tunnel
down so Android cannot accidentally apply the filter to all apps.

The rule covers DNS requests sent through Android's configured resolver. Apps
using encrypted DNS or connecting directly to an IP address can bypass it.
Only one Android VPN can be active at a time, so this feature conflicts with an
existing user VPN.
