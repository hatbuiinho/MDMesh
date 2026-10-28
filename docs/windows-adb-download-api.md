# Latest Windows ADB enrollment package

`GET /rest/public/tools/adb/windows/latest` downloads `MDMesh-Enroll-Windows.zip`.
No login is required. The package contains the latest enrollment helper from the
running server image, Python and ADB (verified against the pinned tools manifest).
Its `.env` contains no saved admin password; users enter their console credentials
when selecting enrollment in the `Enroll.cmd` main menu. The menu also offers
account diagnostics, guided account removal with confirmation on the phone, and
Agent permission grants. Account functions do not require server login.

```sh
curl --fail --location --output MDMesh-Enroll-Windows.zip \
  https://mdm.hatbuinho.me/rest/public/tools/adb/windows/latest
```

A successful response is `application/zip` with attachment filename,
`Content-Length`, `X-Checksum-SHA256` and an ETag containing the SHA-256. Send
`If-None-Match` to check whether a previous download is still current (304).
`Cache-Control: no-cache` requires revalidation, so the stable URL serves the current
package after a server deployment. A missing package returns 404 with a JSON error.

The Docker server build generates this archive from `scripts/enroll-device-adb.py`
and `scripts/windows-portable` using `scripts/build-windows-enroll-portable.py`.
No deployment `.env` or credentials enter that build stage. Publishing a new helper
requires rebuilding/deploying the server image; no URL changes are needed.
For a native WAR deployment, install the output ZIP at
`/usr/local/share/mdmesh/enrollment-tools/MDMesh-Enroll-Windows.zip`, readable by Tomcat.
