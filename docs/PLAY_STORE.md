# Play Store integration

MDMesh can import free Google Play applications through the optional Play
Bridge and bundled private dispenser. Imported APKs are copied into MDMesh
storage, so devices never depend on Google's short-lived download URLs.

This is an unofficial integration. Google can change its private protocol,
rate-limit requests, or restrict the account. Use a dedicated account and a
self-hosted gplaydl dispenser where possible. Paid applications and license
bypass are intentionally unsupported.

## Enable with Docker Compose

Run `./setup.sh` once after updating so it creates stable dispenser secrets.
Then enable the profile in `.env` (preserve any existing profile such as
`cloudflare` by using a comma-separated value):

```dotenv
PLAY_STORE_ENABLED=true
COMPOSE_PROFILES=playstore
# or: COMPOSE_PROFILES=cloudflare,playstore
```

Start or recreate the stack with the Play Store profile:

```sh
docker compose --profile playstore up -d --build
```

The Play Bridge, dispenser database, and dispenser container are internal-only.
Caddy exposes only `/play-dispenser/` over the existing MDMesh HTTPS origin for
the Android Authenticator. Do not publish bridge or database ports.

## Link the private account

1. Install [gplaydl Authenticator](https://github.com/rehmatworks/gplaydl-authenticator/releases)
   on an Android phone and use a dedicated Google account.
2. Open Apps > Play Store. Copy the private dispenser URL shown by MDMesh into
   Authenticator Settings.
3. Add the account in Authenticator and open **Link gplaydl**.
4. Enter the displayed eight-character code in MDMesh.

The code is exchanged server-to-server for a revocable API key which is stored
with mode `0600` in the Play Bridge volume. Google password and 2FA data never
enter MDMesh. AAS tokens are encrypted at rest by the dispenser with AES-256-GCM.

## Device profile

The MVP imports one artifact set for `arm64-v8a`. Configure
`PLAY_DEVICE_ARCH=arm64` and `PLAY_DEVICE_LOCALE=en-US` on the bridge. A fleet
requiring armv7, x86, TV, or per-device density selection needs separate
profiles and is outside this first version.

## Security and storage

- Google AAS tokens exist only encrypted in the private dispenser database.
- The bridge's revocable dispenser key exists only in its private volume.
- Browser clients can submit a package name only, never an artifact URL/path.
- MDMesh derives package/version from the downloaded APK and hashes every part.
- Each imported release is cached by the bridge and then hosted under the
  current MDMesh customer's files directory.
- Disable immediately with `PLAY_STORE_ENABLED=false`; existing imported apps
  remain deployable from Library.
