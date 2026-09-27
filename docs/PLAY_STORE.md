# Play Store integration

MDMesh can import free Google Play applications through the optional Play
Bridge. Imported APKs are copied into MDMesh storage, so devices never depend
on Google's short-lived download URLs.

This is an unofficial integration. Google can change its private protocol,
rate-limit requests, or restrict the account. Use a dedicated account and a
self-hosted gplaydl dispenser where possible. Paid applications and license
bypass are intentionally unsupported.

## Enable with Docker Compose

Link a dedicated account as described in `play-bridge/README.md`, then add to
`.env`:

```dotenv
PLAY_STORE_ENABLED=true
PLAY_BRIDGE_API_KEY=<a-long-random-shared-secret>
GPLAYDL_API_KEY=<key-issued-by-your-dispenser>
GPLAYDL_DISPENSER_URL=https://your-dispenser.example
COMPOSE_PROFILES=playstore
```

Start or recreate the stack with the Play Store profile:

```sh
docker compose --profile playstore up -d --build
```

The Play Bridge is internal-only. Do not publish port 8787. MDMesh presents an
explicit setup/unavailable state when the integration is disabled or unhealthy.

## Device profile

The MVP imports one artifact set for `arm64-v8a`. Configure
`PLAY_DEVICE_ARCH=arm64` and `PLAY_DEVICE_LOCALE=en-US` on the bridge. A fleet
requiring armv7, x86, TV, or per-device density selection needs separate
profiles and is outside this first version.

## Security and storage

- Google and dispenser credentials exist only in the bridge environment/cache.
- Browser clients can submit a package name only, never an artifact URL/path.
- MDMesh derives package/version from the downloaded APK and hashes every part.
- Each imported release is cached by the bridge and then hosted under the
  current MDMesh customer's files directory.
- Disable immediately with `PLAY_STORE_ENABLED=false`; existing imported apps
  remain deployable from Library.
