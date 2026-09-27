# MDMesh Play Bridge

This optional service wraps the unofficial, MIT-licensed `gplaydl` client and
keeps Google/dispenser credentials outside MDMesh. It downloads and verifies
the base APK and device-specific splits before MDMesh imports them.

The pinned upstream source is used instead of Aurora's Android-only AAR because
the bridge runs on a server JVM/Linux host. The protocol and operational risk
are the same Aurora-style reverse-engineered Google Play access.

## Account setup

Use a dedicated Google account. Link it through a gplaydl-compatible dispenser
(self-hosting is recommended), then place the resulting key in
`GPLAYDL_API_KEY`. Do not use a personal Google account.

Required environment:

- `PLAY_BRIDGE_API_KEY`: shared secret used only between MDMesh and this service.
- `GPLAYDL_API_KEY`: key produced by linking gplaydl to its dispenser.
- `GPLAYDL_DISPENSER_URL`: dispenser base URL; defaults to the upstream service.

Optional environment:

- `PLAY_DEVICE_ARCH=arm64`
- `PLAY_DEVICE_LOCALE=en-US`
- `PLAY_MAX_ARTIFACT_BYTES=524288000`

The bridge cache is stored in `/data`. No port needs to be exposed publicly.
