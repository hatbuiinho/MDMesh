import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('portable', ROOT / 'scripts/build-windows-enroll-portable.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PortableTests(unittest.TestCase):
    def test_packaging_only_copies_enrollment_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            env = root / 'source.env'
            env.write_text("MDM_BASE_URL=https://example.com/\nMDM_ADMIN_USERNAME=operator\n"
                           "MDM_ADMIN_PASSWORD='literal$`password#'\nDB_PASSWORD=database-secret\n"
                           "MDM_RELEASE_STORE_PASSWORD=signing-secret\n")
            with patch('builtins.print'):
                folder, archive = module.build(root / 'output', root / 'cache', env, True, True)
            config = (folder / '.env').read_text()
            self.assertIn("MDM_ADMIN_PASSWORD='literal$`password#'", config)
            self.assertNotIn('database-secret', config)
            self.assertNotIn('signing-secret', config)
            self.assertEqual((folder / 'app/scripts/enroll-device-adb.py').read_bytes(),
                             (ROOT / 'scripts/enroll-device-adb.py').read_bytes())
            with zipfile.ZipFile(archive) as z:
                self.assertIn('MDMesh-Enroll/Enroll.cmd', z.namelist())
                self.assertEqual(z.read('MDMesh-Enroll/.env').decode(), config)
                self.assertFalse(any('keystore' in name for name in z.namelist()))
            self.assertIn(module.checksum(archive), (archive.parent / 'SHA256SUMS').read_text())
            with self.assertRaisesRegex(ValueError, 'already exists'):
                module.build(root / 'output', root / 'cache')

    def test_password_is_excluded_unless_requested(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = Path(tmp) / 'source.env'
            env.write_text('MDM_ADMIN_USERNAME=admin\nMDM_ADMIN_PASSWORD=secret-value\n')
            config = module.enrollment_env(env, False)
            self.assertNotIn('secret-value', config)
            self.assertIn("MDM_ADMIN_PASSWORD=''", config)

    def test_tools_are_verified_and_extracted_from_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'python.zip'
            payload = b'MZ-test-portable-executable'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('python.exe', payload)
            package = {'name': 'test', 'directory': 'python', 'archiveDirectory': '',
                       'sha256': module.checksum(archive),
                       'files': {'python.exe': hashlib.sha256(payload).hexdigest()}}
            with patch.object(module.urllib.request, 'urlopen', side_effect=AssertionError('Should use verified cache')):
                module.prepare_tools(package, root, root / 'bundle')
            self.assertEqual((root / 'bundle/tools/python/python.exe').read_bytes(), payload)
            package['files']['python.exe'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                module.prepare_tools(package, root, root / 'bundle2')

    def test_tool_manifest_is_pinned_to_https_official_sources(self):
        manifest = json.loads((module.TEMPLATE / 'tools-manifest.json').read_text())
        for package in manifest['packages']:
            self.assertRegex(package['url'], r'^https://(www.python.org|dl.google.com)/')
            self.assertRegex(package['sha256'], r'^[0-9a-f]{64}$')
            self.assertTrue(package['files'])
        adb = next(p for p in manifest['packages'] if p['directory'] == 'platform-tools')
        for name in ('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll'):
            self.assertIn(name, adb['files'])


if __name__ == '__main__':
    unittest.main()
