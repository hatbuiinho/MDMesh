#!/usr/bin/env python3
"""Publish the newest locally built Android APK to the MDMesh agent release catalog."""
import argparse
import getpass
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def select_apk(roots):
    candidates = []
    for root in roots:
        if root.is_dir():
            for entry in root.iterdir():
                apk = entry / 'mdmesh-custom.apk'
                if entry.is_dir() and re.fullmatch(r'[1-9][0-9]*', entry.name) and apk.is_file():
                    candidates.append((int(entry.name), apk))
    if not candidates:
        raise ValueError('No release APK found. Run bash scripts/build-android-release-docker.sh first, or pass --apk PATH.')
    highest = max(code for code, _ in candidates)
    newest = sorted({apk.resolve() for code, apk in candidates if code == highest})
    if len({hashlib.sha256(p.read_bytes()).hexdigest() for p in newest}) != 1:
        raise ValueError('Different APKs share the highest versionCode. Select one explicitly with --apk PATH.')
    return newest[0]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Server redirected the request. Use its final base URL.')


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(
            NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, data=None, content_type='application/json'):
        req = urllib.request.Request(self.base + '/rest/' + path, data=data,
                                     headers={'Content-Type': content_type, 'Accept': 'application/json'})
        with self.opener.open(req, timeout=180) as response:
            result = json.load(response)
        if not isinstance(result, dict) or result.get('status') != 'OK':
            raise ValueError('MDMesh rejected the request. Check credentials, permissions and APK validity.')
        return result.get('data')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, help='explicit APK; default: highest numeric local release directory')
    parser.add_argument('--base-url', help='MDMesh URL; default: MDM_BASE_URL or BASE_URL')
    parser.add_argument('--username', help='default: MDM_PUBLISH_USERNAME or admin')
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env', help='literal release dotenv settings (default: root .env)')
    parser.add_argument('--dry-run', action='store_true', help='select and checksum APK without contacting server')
    args = parser.parse_args()
    try:
        settings = {}
        if args.env_file.is_file():
            spec = importlib.util.spec_from_file_location('release_env', ROOT / 'scripts/lib/release-env.py')
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            settings = module.read_settings(args.env_file)
        elif args.env_file != ROOT / '.env':
            raise ValueError('Env file does not exist.')
        def setting(key):
            return os.environ.get(key, settings.get(key, ''))
        roots = [ROOT / '.android-docker/releases', ROOT / 'releases/android',
                 ROOT / 'agent-android/app/build/distributions']
        if setting('MDM_RELEASE_OUTPUT_ROOT'):
            roots.append(Path(setting('MDM_RELEASE_OUTPUT_ROOT')))
        apk = args.apk.resolve() if args.apk else select_apk(roots)
        if not apk.is_file() or not 0 < apk.stat().st_size <= 128 * 1024 * 1024:
            raise ValueError('APK must exist and be between 1 byte and 128 MiB.')
        payload = apk.read_bytes()
        checksum = hashlib.sha256(payload).hexdigest()
        sums = apk.parent / 'SHA256SUMS'
        if sums.is_file():
            expected = [line.split()[0] for line in sums.read_text().splitlines()
                        if len(line.split()) == 2 and line.split()[1].lstrip('*') == apk.name]
            if expected != [checksum]:
                raise ValueError('APK does not match its SHA256SUMS file.')
        print(f'APK: {apk}\nSHA256: {checksum}', flush=True)
        if args.dry_run:
            print('Dry run: no server contacted, no APK uploaded.')
            return 0
        base = args.base_url or setting('MDM_BASE_URL') or os.environ.get('BASE_URL', '')
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('Provide a valid --base-url or MDM_BASE_URL.')
        if parsed.scheme == 'http' and parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
            raise ValueError('Use HTTPS for a remote server.')
        username = args.username or setting('MDM_PUBLISH_USERNAME') or 'admin'
        password = setting('MDM_PUBLISH_PASSWORD') or None
        if password is None:
            if not sys.stdin.isatty():
                raise ValueError('Set MDM_PUBLISH_PASSWORD for non-interactive publishing.')
            password = getpass.getpass(f'MDMesh password ({username}): ')
        client = Client(base)
        options = client.request('public/auth/options') or {}
        if options.get('publicKey'):
            raise ValueError('RSA login mode is not supported by this helper.')
        user = client.request('public/auth/login', json.dumps({
            'login': username, 'password': hashlib.md5(password.encode()).hexdigest().upper(),
        }).encode()) or {}
        if user.get('passwordReset') or user.get('twoFactor'):
            raise ValueError('Complete password reset / two-factor login in the console first.')
        releases = client.request('private/agent/v1/releases') or []
        for release in releases:
            if release.get('sha256') == checksum:
                print(f"Already published: release {release['id']} ({release.get('url', '')})")
                return 0
        boundary = 'mdmesh-' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="mdmesh-custom.apk"\r\n'
                'Content-Type: application/vnd.android.package-archive\r\n\r\n').encode()
        body += payload + f'\r\n--{boundary}--\r\n'.encode()
        result = client.request('private/agent/v1/releases', body, f'multipart/form-data; boundary={boundary}')
        if not isinstance(result, dict) or result.get('sha256') != checksum:
            raise ValueError('Upload response checksum mismatch. Inspect the catalog before retrying.')
        print(f"Published release {result['id']}: {result.get('versionName')} (code {result.get('versionCode')})\n{result.get('url', '')}")
        print('APK is available in Agent releases & rollout. Create a rollout in the console to update devices.')
        return 0
    except urllib.error.HTTPError as error:
        print(f'Publish failed: server returned HTTP {error.code}. Check the URL and permissions.', file=sys.stderr)
    except (OSError, ValueError, KeyError) as error:
        print(f'Publish failed: {error}', file=sys.stderr)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
