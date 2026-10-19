import importlib.util
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess

SCRIPT = Path(__file__).resolve().parents[1] / 'enroll-device-adb.py'
spec = importlib.util.spec_from_file_location('enroll_adb', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EnrollmentTests(unittest.TestCase):
    def test_dotenv_is_literal_and_only_reads_console_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / '.env'
            path.write_text("MDM_ADMIN_USERNAME=admin\nMDM_ADMIN_PASSWORD='$(touch /tmp/no) `$HOME` # secret'\nDB_PASSWORD=unrelated\n")
            settings = module.read_env(path)
            self.assertEqual(settings['MDM_ADMIN_PASSWORD'], '$(touch /tmp/no) `$HOME` # secret')
            self.assertNotIn('DB_PASSWORD', settings)

    def test_rejects_bad_quoted_password_without_exposing_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / '.env'
            path.write_text("MDM_ADMIN_PASSWORD='secret' trailing")
            with self.assertRaises(ValueError) as error:
                module.read_env(path)
            self.assertNotIn('secret', str(error.exception))

    def test_url_validation(self):
        self.assertEqual(module.validate_url('https://mdm.hatbuinho.me/'), 'https://mdm.hatbuinho.me')
        for url in ('http://example.com', 'https://admin:password@example.com', 'https://example.com/?x=1', 'https://example.com/%s'):
            with self.assertRaises(ValueError):
                module.validate_url(url)

    def test_login_then_mint_new_token(self):
        calls = []
        token = '01234567-1234-1234-1234-012345678901'
        class Client:
            def request(self, path, data=None):
                calls.append((path, data))
                return {'token': token} if path.endswith('/token') else {}
        client = Client()
        module.login(client, 'admin', 'password')
        self.assertEqual(module.mint_token(client), token)
        self.assertEqual([c[0] for c in calls], ['public/auth/options', 'public/auth/login', 'private/agent/v1/token'])
        self.assertEqual(calls[-1][1], b'{}')
        self.assertNotIn(b'password"', calls[1][1].split(b':')[-1])

    def test_two_factor_does_not_mint(self):
        calls = []
        class Client:
            def request(self, path, data=None):
                calls.append(path)
                return {'twoFactor': True} if path.endswith('/login') else {}
        with self.assertRaises(ValueError):
            module.login(Client(), 'admin', 'password')
        self.assertNotIn('private/agent/v1/token', calls)

    def test_owner_is_not_profile_owner(self):
        device = module.Device('adb', 'test')
        device.shell = lambda *args: 'Profile Owner:\n admin=ComponentInfo{' + module.ADMIN + '}'
        self.assertFalse(device.is_owner())
        device.shell = lambda *args: 'Device Owner:\n admin=ComponentInfo{' + module.ADMIN + '}\n User ID: 0'
        self.assertTrue(device.is_owner())

    def test_selects_highest_server_release_and_excludes_debug(self):
        class Client:
            def request(self, path):
                self.path = path
                return [
                    {'packageName': module.PACKAGE, 'versionCode': 1025, 'sha256': 'a' * 64},
                    {'packageName': module.PACKAGE + '.debug', 'versionCode': 9999, 'sha256': 'b' * 64},
                    {'packageName': module.PACKAGE, 'versionCode': 1026, 'sha256': 'c' * 64},
                ]
        client = Client()
        self.assertEqual(module.newest_published(client)['versionCode'], 1026)
        self.assertEqual(client.path, 'private/agent/v1/releases')

    def test_no_published_release_fails(self):
        class Client:
            def request(self, path):
                return []
        with self.assertRaises(ValueError):
            module.newest_published(Client())

    def test_conflicting_latest_releases_fail(self):
        class Client:
            def request(self, path):
                return [{'packageName': module.PACKAGE, 'versionCode': 1026, 'sha256': sha * 64}
                        for sha in ('a', 'b')]
        with self.assertRaises(ValueError):
            module.newest_published(Client())

    def test_download_checks_checksum_before_install(self):
        payload = b'signed-apk-test-bytes'
        class Opener:
            def open(self, request, timeout):
                return io.BytesIO(payload)
        metadata = {'url': 'https://mdm.example.com/files/agent.apk',
                    'sha256': hashlib.sha256(payload).hexdigest()}
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / 'agent.apk'
            self.assertEqual(module.download_apk(metadata, apk, Opener()).read_bytes(), payload)
            metadata['sha256'] = '0' * 64
            with self.assertRaises(ValueError):
                module.download_apk(metadata, apk, Opener())
            self.assertFalse(apk.exists())

    def test_rejects_http_apk(self):
        with self.assertRaises(ValueError):
            module.download_apk({'url': 'http://example.com/app.apk'}, Path('/unused'))

    def test_adb_accounts_failure_has_actionable_message(self):
        result = subprocess.CompletedProcess([], 255, '',
            'Not allowed to set the device owner because there are already some accounts on the device.')
        with patch.object(module.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'Gỡ tất cả tài khoản'):
                module.Device('adb', 'test').shell('dpm', 'set-device-owner', module.ADMIN)

    def test_adb_text_failure_never_exposes_token(self):
        secret = 'private-enrollment-token'
        result = subprocess.CompletedProcess([], 1, secret, secret)
        with patch.object(module.subprocess, 'run', return_value=result):
            with self.assertRaises(ValueError) as error:
                module.Device('adb', 'test').shell('input', 'text', secret)
            self.assertNotIn(secret, str(error.exception))

    def test_adb_install_failure_keeps_android_reason(self):
        result = subprocess.CompletedProcess([], 1, '', 'INSTALL_FAILED_UPDATE_INCOMPATIBLE')
        with patch.object(module.subprocess, 'run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'):
                module.Device('adb', 'test').run('install', '-r', '/tmp/test.apk')

    def test_ui_reads_remote_file_when_dump_only_returns_status(self):
        device = module.Device('adb', 'test')
        xml = '<hierarchy><node text="Agent" bounds="[0,0][100,100]" /></hierarchy>'
        with patch.object(device, 'shell', return_value='UI hierchary dumped to: file') as shell, \
                patch.object(device, 'run', return_value=xml) as run:
            self.assertEqual(device.nodes()[0].get('text'), 'Agent')
            remote = shell.call_args_list[0].args[-1]
            self.assertTrue(remote.startswith('/data/local/tmp/mdmesh-ui-'))
            run.assert_called_once_with('exec-out', 'cat', remote)
            self.assertEqual(shell.call_args_list[-1].args, ('rm', '-f', remote))

    def test_ui_temp_file_is_removed_when_xml_is_invalid(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', return_value='') as shell, \
                patch.object(device, 'run', return_value='not xml'):
            with self.assertRaisesRegex(ValueError, 'XML'):
                device.nodes()
            self.assertEqual(shell.call_args_list[-1].args[:2], ('rm', '-f'))

    def test_special_permissions_are_granted_and_verified_for_current_user(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=[
                '10', '35', '', 'GET_USAGE_STATS: allow; time=+1s', '',
                'SCHEDULE_EXACT_ALARM: allow']) as shell, patch('builtins.print'):
            device.grant_special_access()
        self.assertIn(unittest.mock.call('appops', 'set', '--user', '10', module.PACKAGE,
                                        'SCHEDULE_EXACT_ALARM', 'allow'), shell.call_args_list)

    def test_older_android_skips_exact_alarm_appop(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['0', '30', '', 'GET_USAGE_STATS: allow']) as shell, patch('builtins.print'):
            device.grant_special_access()
        self.assertFalse(any('SCHEDULE_EXACT_ALARM' in c.args for c in shell.call_args_list))

    def test_denied_appop_does_not_report_success(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['0', '35', '', 'GET_USAGE_STATS: ignore']):
            with self.assertRaisesRegex(ValueError, 'chưa xác nhận'):
                device.grant_special_access()


if __name__ == '__main__':
    unittest.main()
