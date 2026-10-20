import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_release', Path(__file__).resolve().parents[1] / 'publish-release.py')
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class PublishTests(unittest.TestCase):
    def test_select_highest_and_conflicts(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for code in ('9', '10'):
                (root / code).mkdir()
                (root / code / 'mdmesh-custom.apk').write_bytes(code.encode())
            self.assertEqual(publish.select_apk([root]).parent.name, '10')
            other = root / 'other'
            (other / '10').mkdir(parents=True)
            (other / '10/mdmesh-custom.apk').write_bytes(b'conflict')
            with self.assertRaises(ValueError):
                publish.select_apk([root, other])

    def test_upload_and_duplicate_with_cookie(self):
        payload = b'test apk bytes'
        sha = hashlib.sha256(payload).hexdigest()
        uploads = []
        catalog = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, data, cookie=False):
                self.send_response(200)
                if cookie:
                    self.send_header('Set-Cookie', 'session=test; Path=/')
                self.end_headers()
                self.wfile.write(json.dumps({'status': 'OK', 'data': data}).encode())

            def do_GET(self):
                if self.path.endswith('/options'):
                    self.respond({})
                else:
                    assert self.headers.get('Cookie') == 'session=test'
                    self.respond(catalog)

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                if self.path.endswith('/login'):
                    assert json.loads(body)['login'] == 'release-admin'
                    assert json.loads(body)['password'] == hashlib.md5(b'secret').hexdigest().upper()
                    self.respond({}, cookie=True)
                else:
                    assert self.headers.get('Cookie') == 'session=test'
                    assert b'name="file"' in body and payload in body
                    uploads.append(body)
                    result = {'id': 1, 'sha256': sha, 'versionName': '1.0', 'versionCode': 10, 'url': '/files/test.apk'}
                    catalog.append(result)
                    self.respond(result)

        server = HTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory() as folder:
                apk = Path(folder) / 'agent.apk'
                apk.write_bytes(payload)
                env_file = Path(folder) / '.env'
                env_file.write_text("MDM_PUBLISH_USERNAME=release-admin\nMDM_PUBLISH_PASSWORD='secret'\n")
                args = ['publish-release.py', '--env-file', str(env_file), '--apk', str(apk), '--base-url', f'http://127.0.0.1:{server.server_port}']
                with patch.dict(os.environ, {k: v for k, v in os.environ.items() if not k.startswith('MDM_PUBLISH_')}, clear=True), patch.object(sys, 'argv', args), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(publish.main(), 0)
                    self.assertEqual(publish.main(), 0)
                self.assertEqual(len(uploads), 1)
                (apk.parent / 'SHA256SUMS').write_text('bad  agent.apk\n')
                with patch.object(sys, 'argv', args + ['--dry-run']), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(publish.main(), 1)
        finally:
            server.shutdown()
            worker.join()
            server.server_close()


if __name__ == '__main__':
    unittest.main()
