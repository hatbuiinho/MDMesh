"""Small HTTP boundary around gplaydl for MDMesh.

This service owns Google/dispenser credentials. MDMesh only receives catalogue
metadata and streams already verified APK artifacts by opaque IDs.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import tempfile
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
from gplaydl.api import get_delivery, get_details, purchase, search_apps
from gplaydl.auth import ensure_auth

DATA = Path(os.getenv("PLAY_BRIDGE_DATA", "/data"))
API_KEY = os.getenv("PLAY_BRIDGE_API_KEY", "")
DISPENSER = os.getenv("GPLAYDL_DISPENSER_URL") or None
ARCH = os.getenv("PLAY_DEVICE_ARCH", "arm64")
LOCALE = os.getenv("PLAY_DEVICE_LOCALE", "en-US")
MAX_BYTES = int(os.getenv("PLAY_MAX_ARTIFACT_BYTES", str(500 * 1024 * 1024)))
PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")
locks: dict[str, threading.Lock] = {}
locks_guard = threading.Lock()


def package_lock(package: str) -> threading.Lock:
    with locks_guard:
        return locks.setdefault(package, threading.Lock())


def auth() -> dict:
    result = ensure_auth(arch=ARCH, dispenser_url=DISPENSER)
    if not result:
        raise RuntimeError("No Google Play account is linked to the dispenser")
    return result


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return base64.urlsafe_b64encode(value.digest()).decode().rstrip("=")


def fetch(url: str, destination: Path, expected: str, cookies: list[dict], compressed: bool) -> None:
    headers = {}
    if cookies:
        headers["Cookie"] = "; ".join(f"{item['name']}={item['value']}" for item in cookies)
    total = 0
    hasher = hashlib.sha256()
    with httpx.stream("GET", url, headers=headers, follow_redirects=True, timeout=300) as response:
        response.raise_for_status()
        inflater = zlib.decompressobj(zlib.MAX_WBITS | 16) if compressed else None
        with destination.open("wb") as output:
            for wire_chunk in response.iter_raw(64 * 1024):
                chunk = inflater.decompress(wire_chunk) if inflater else wire_chunk
                if chunk:
                    total += len(chunk)
                    if total > MAX_BYTES:
                        raise ValueError("Artifact exceeds configured size limit")
                    output.write(chunk)
                    hasher.update(chunk)
            if inflater:
                chunk = inflater.flush()
                total += len(chunk)
                if total > MAX_BYTES:
                    raise ValueError("Artifact exceeds configured size limit")
                output.write(chunk)
                hasher.update(chunk)
    actual = base64.urlsafe_b64encode(hasher.digest()).decode().rstrip("=")
    if expected and actual != expected.rstrip("="):
        raise ValueError("Google Play artifact hash mismatch")


def ensure_release(package: str) -> dict:
    target = DATA / package
    manifest_path = target / "manifest.json"
    with package_lock(package):
        if manifest_path.exists():
            return json.loads(manifest_path.read_text())
        token = auth()
        details = get_details(package, token)
        delivery_token = purchase(package, details.version_code, token)
        delivery = get_delivery(package, details.version_code, token, delivery_token, [LOCALE])
        staging = Path(tempfile.mkdtemp(prefix="play-", dir=str(DATA)))
        try:
            artifacts = []
            base_gzip = bool(delivery.gzipped_url and delivery.gzipped_size)
            base_path = staging / "base.apk"
            fetch(delivery.gzipped_url if base_gzip else delivery.download_url, base_path,
                  delivery.sha256, delivery.cookies, base_gzip)
            artifacts.append({"id": "base", "name": "base.apk", "size": base_path.stat().st_size})
            for index, split in enumerate(delivery.splits):
                safe = re.sub(r"[^A-Za-z0-9._-]", "_", split.name)[:120] or f"split-{index}"
                artifact_id = f"split-{index}-{safe}"
                path = staging / f"{artifact_id}.apk"
                compressed = bool(split.gzipped_url and split.gzipped_size)
                fetch(split.gzipped_url if compressed else split.url, path, split.sha256, [], compressed)
                artifacts.append({"id": artifact_id, "name": path.name, "size": path.stat().st_size})
            manifest = {
                "name": details.title or package,
                "packageName": package,
                "version": details.version_string,
                "versionCode": details.version_code,
                "paid": False,
                "artifacts": artifacts,
                "createdAt": int(time.time()),
            }
            (staging / "manifest.json").write_text(json.dumps(manifest))
            if target.exists():
                shutil.rmtree(target)
            staging.rename(target)
            return manifest
        except Exception:
            shutil.rmtree(staging, ignore_errors=True)
            raise


class Handler(BaseHTTPRequestHandler):
    server_version = "MDMeshPlayBridge/1"

    def do_GET(self) -> None:
        if not API_KEY or self.headers.get("Authorization") != f"Bearer {API_KEY}":
            return self.json(401, {"error": "unauthorized"})
        parsed = urllib.parse.urlparse(self.path)
        parts = [urllib.parse.unquote(item) for item in parsed.path.split("/") if item]
        try:
            if parts == ["v1", "health"]:
                return self.json(200, {"ok": True, "message": "Play Bridge is ready."})
            if parts == ["v1", "search"]:
                query = urllib.parse.parse_qs(parsed.query).get("q", [""])[0].strip()
                limit = min(60, max(1, int(urllib.parse.parse_qs(parsed.query).get("limit", [30])[0])))
                if len(query) < 2:
                    return self.json(400, {"error": "query_too_short"})
                rows = search_apps(query, auth(), limit)
                return self.json(200, [{"packageName": row["package"], "name": row["title"],
                                        "summary": row.get("creator", ""), "paid": False,
                                        "compatible": True} for row in rows])
            if len(parts) == 4 and parts[:2] == ["v1", "apps"] and parts[3] == "release":
                self.validate_package(parts[2])
                return self.json(200, ensure_release(parts[2]))
            if len(parts) == 5 and parts[:2] == ["v1", "apps"] and parts[3] == "artifacts":
                package, artifact_id = parts[2], parts[4]
                self.validate_package(package)
                if not re.fullmatch(r"[A-Za-z0-9._-]{1,160}", artifact_id):
                    return self.json(400, {"error": "invalid_artifact"})
                manifest = ensure_release(package)
                item = next((x for x in manifest["artifacts"] if x["id"] == artifact_id), None)
                if not item:
                    return self.json(404, {"error": "not_found"})
                path = DATA / package / item["name"]
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(path.stat().st_size))
                self.end_headers()
                with path.open("rb") as stream:
                    shutil.copyfileobj(stream, self.wfile, 64 * 1024)
                return
            return self.json(404, {"error": "not_found"})
        except ValueError as error:
            return self.json(400, {"error": str(error)})
        except Exception as error:
            return self.json(503, {"error": type(error).__name__, "message": str(error)[:300]})

    def validate_package(self, package: str) -> None:
        if not PACKAGE.fullmatch(package):
            raise ValueError("Invalid package name")

    def json(self, status: int, value) -> None:
        payload = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt: str, *args) -> None:
        print("play-bridge", self.address_string(), fmt % args, flush=True)


if __name__ == "__main__":
    if not API_KEY:
        raise SystemExit("PLAY_BRIDGE_API_KEY is required")
    DATA.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "8787"))), Handler).serve_forever()
