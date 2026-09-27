# MDMesh Play Bridge

This optional service wraps the unofficial, MIT-licensed `gplaydl` client and
keeps Google/dispenser credentials outside MDMesh. It downloads and verifies
the base APK and device-specific splits before MDMesh imports them.

The pinned upstream source is used instead of Aurora's Android-only AAR because
the bridge runs on a server JVM/Linux host. The protocol and operational risk
are the same Aurora-style reverse-engineered Google Play access.

## Account setup

Use a dedicated Google account. The MDMesh `playstore` Compose profile includes
a private dispenser. Add the account with gplaydl Authenticator and enter its
pairing code in Apps > Play Store. Do not use a personal Google account.

Required environment:

- `PLAY_BRIDGE_API_KEY`: shared secret used only between MDMesh and this service.
- `GPLAYDL_DISPENSER_URL`: internal dispenser URL; Compose sets it automatically.

Optional environment:

- `PLAY_DEVICE_ARCH=arm64`
- `PLAY_DEVICE_LOCALE=en-US`
- `PLAY_MAX_ARTIFACT_BYTES=524288000`

The claimed dispenser key and bridge cache are stored in `/data`. No bridge
port needs to be exposed publicly. `GPLAYDL_API_KEY` remains an optional
headless override, but normal MDMesh setup does not require it.
