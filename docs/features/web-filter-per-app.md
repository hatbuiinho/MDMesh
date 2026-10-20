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

## Managed VPN settings

Agent 1.11.5 locks user VPN configuration with the Device Owner
`DISALLOW_CONFIG_VPN` restriction whenever web filtering is active. The agent
sets its always-on VPN first, then applies and verifies the restriction. Turning
web filtering off or removing the policy stops the managed VPN and removes the
restriction. Saved configuration is reapplied after reboot or an agent update.

On realme Android 12, this restriction blocks the VPN Settings page but does not
block the disconnect popup opened from the system VPN notification. Agent 1.11.6
also suspends the separate system VPN management package (`com.android.vpndialogs`)
while filtering is enabled. The VPN notification remains visible. Turning filtering
off releases this suspension reason; other app policies keep their own suspension
reasons. Android must confirm suspension before the agent reports the web policy
as applied. OEM notification behavior must be tested on deployed devices.

This does not enable VPN lockdown: the DNS-only split tunnel still needs ordinary
traffic to use the underlying network. It also does not prevent the encrypted-DNS
and direct-IP bypasses described above.

## DNS reliability

Agent 1.11.4 refreshes the current Internet network's DNS list for each forwarded
query, prefers IPv4 resolvers when both families are advertised, and tries the
remaining resolvers after an error or timeout. IPv6-only resolver lists remain
supported. If every resolver fails, the agent returns DNS SERVFAIL rather than
silently dropping the query. Blocked domains continue to receive NXDOMAIN.
Repeated starts with the same policy keep the current tunnel. Tunnel replacement
uses nonblocking reads with bounded polling so an old reader cannot strand the
replacement tunnel on devices whose kernel retains a blocking read after close.

This fixes allowed websites loading indefinitely on mobile networks that advertise
an unreachable IPv6 resolver first. To diagnose this case, compare a query sent to
the VPN DNS address with direct queries to each carrier resolver on the underlying
network. A blocked domain returning NXDOMAIN while allowed domains time out can
indicate an upstream resolver failure rather than a domain matching error.
