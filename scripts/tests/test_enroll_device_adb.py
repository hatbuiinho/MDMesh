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
            with self.assertRaisesRegex(ValueError, 'Khong the thiet lap Device Owner'):
                module.Device('adb', 'test').shell('dpm', 'set-device-owner', module.ADMIN)

    def test_account_diagnostics_only_lists_current_accounts_with_profile(self):
        device = module.Device('adb', 'test')
        dump = '''User UserInfo{0:Owner:4c13}:
  Accounts: 2
    Account {name=XXXXXXXXX, type=com.hihonor.id}
    Account {name=XXXXXXXXX, type=com.whatsapp}
  Accounts History
  9,action_account_remove,old-google-account
  RegisteredServicesCache: 1 services
    ServiceInfo: AuthenticatorDescription {type=com.google}
User UserInfo{10:Work:30}:
  Accounts: 1
    Account {name=work@example.com, type=custom.auth}
'''
        with patch.object(device, 'shell', side_effect=[dump, 'Users:\nUserInfo{0:Owner:4c13}\nUserInfo{10:Work:30}']):
            report = device.account_diagnostics()
        for expected in ('HONOR ID', 'WhatsApp', 'Android da an ten', 'user 10 (Work)', 'custom.auth'):
            self.assertIn(expected, report)
        self.assertNotIn('old-google-account', report)
        self.assertNotIn('com.google', report)

    def test_account_diagnostics_distinguishes_empty_and_unreadable(self):
        device = module.Device('adb', 'test')
        for dump, expected in [('User UserInfo{0:Owner:13}:\n  Accounts: 0', 'Khong thay tai khoan'),
                               ('Permission Denial', 'Khong doc duoc')]:
            with patch.object(device, 'shell', side_effect=[dump, 'Users:']):
                report = device.account_diagnostics()
                self.assertIn(expected, report)
                self.assertNotIn('--diagnose-accounts', report)
                self.assertNotIn('--manage-accounts', report)
                self.assertNotIn('HONOR/HUAWEI ID:', report)
                self.assertNotIn('WhatsApp co the', report)
                if 'Accounts: 0' in dump:
                    self.assertIn('chon 1', report)
                    self.assertNotIn('chon 3', report)
        with patch.object(device, 'shell', side_effect=ValueError('private raw dump')):
            report = device.account_diagnostics()
        self.assertIn('Khong doc duoc', report)
        self.assertNotIn('private raw dump', report)

    def test_current_accounts_rejects_incomplete_or_unreadable_dump(self):
        device = module.Device('adb', 'test')
        for dump in ('Permission Denial', 'User UserInfo{0:Owner:13}:\n Accounts: 1'):
            with patch.object(device, 'shell', return_value=dump):
                with self.assertRaises(ValueError):
                    device.current_accounts()

    def test_current_accounts_preserves_profile_and_ignores_services(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', return_value='''User UserInfo{10:Work:30}:
 Accounts: 1
 Account {name=XXXXXXXXX, type=com.whatsapp}
 ServiceInfo: AuthenticatorDescription {type=com.google}
'''):
            self.assertEqual(device.current_accounts(), [dict(user='10', user_name='Work', name='XXXXXXXXX', type='com.whatsapp')])

    def test_open_account_settings_falls_back_on_android_error_with_zero_exit(self):
        device = module.Device('adb', 'test')
        account = dict(user='10', type='com.whatsapp')
        with patch.object(device, 'shell', side_effect=['Error: Activity not started', 'Starting: Intent']), patch('sys.stdout', new=io.StringIO()):
            device.open_account_settings(account)
            self.assertEqual(device.shell.call_args_list, [
                unittest.mock.call('am', 'start', '--user', '10', '-a', 'android.settings.SYNC_SETTINGS'),
                unittest.mock.call('am', 'start', '--user', '10', '-a', 'android.settings.SETTINGS')])

    def test_manage_accounts_verifies_and_never_removes_data(self):
        device = module.Device('adb', 'test')
        account = dict(user='0', user_name='Owner', name='XXXXXXXXX', type='com.whatsapp')
        with patch.object(device, 'current_accounts', side_effect=[[account], [], []]), \
                patch.object(device, 'open_account_settings') as opened, \
                patch.object(device, 'shell') as shell, \
                patch.object(module, 'ask', side_effect=['1', '']), patch('sys.stdout', new=io.StringIO()) as output:
            device.assist_accounts()
        opened.assert_called_once_with(account)
        shell.assert_not_called()
        self.assertIn('Khong con tai khoan loai nay', output.getvalue())

    def test_manage_accounts_does_not_claim_success_for_recreated_account(self):
        device = module.Device('adb', 'test')
        account = dict(user='0', user_name='Owner', name='XXXXXXXXX', type='com.whatsapp')
        with patch.object(device, 'current_accounts', return_value=[account]), \
                patch.object(device, 'open_account_settings'), \
                patch.object(module, 'ask', side_effect=['1', '', 'q']), patch('sys.stdout', new=io.StringIO()) as output:
            device.assist_accounts()
        self.assertIn('chua xac nhan go thanh cong', output.getvalue())

    def test_manage_accounts_skips_server_and_installation(self):
        device = unittest.mock.Mock()
        with patch.object(module.sys, 'argv', ['enroll-device-adb.py', '--manage-accounts']), \
                patch.object(module, 'select_device', return_value=device), \
                patch.object(module, 'read_env') as env:
            self.assertEqual(module.main(), 0)
        device.assist_accounts.assert_called_once_with()
        env.assert_not_called()

    def test_no_arguments_menu_can_exit_without_adb_or_server(self):
        with patch.object(module, 'ask', return_value='0'), \
                patch.object(module, 'select_device') as select, \
                patch.object(module, 'read_env') as env, patch('builtins.print'):
            self.assertEqual(module.main([]), 0)
        select.assert_not_called()
        env.assert_not_called()

    def test_menu_dispatches_actions_and_returns_after_failure(self):
        with patch.object(module, 'ask', side_effect=['invalid', '1', '2', '3', '4', '0']), \
                patch.object(module, 'main', side_effect=[1, 0, 0, 0]) as run, patch('builtins.print'):
            self.assertEqual(module.action_menu(['--serial', 'test']), 0)
        self.assertEqual(run.call_args_list, [unittest.mock.call(['--serial', 'test', mode])
                         for mode in ('--enroll', '--diagnose-accounts', '--manage-accounts', '--permissions-only')])

    def test_menu_diagnostics_returns_to_menu_without_server(self):
        device = unittest.mock.Mock()
        device.account_diagnostics.return_value = 'accounts report'
        with patch.object(module, 'ask', side_effect=['2', '0']), \
                patch.object(module, 'select_device', return_value=device) as select, \
                patch.object(module, 'read_env') as env, patch('builtins.print'):
            self.assertEqual(module.main(['--serial', 'test']), 0)
        select.assert_called_once_with('test')
        device.account_diagnostics.assert_called_once_with()
        env.assert_not_called()

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
                patch.object(device, 'run', return_value='not xml'), patch.object(module.time, 'sleep'):
            with self.assertRaisesRegex(ValueError, 'XML'):
                device.nodes()
            self.assertEqual(shell.call_args_list[-1].args[:2], ('rm', '-f'))

    def test_ui_dump_preserves_stderr_when_android_returns_success(self):
        result = subprocess.CompletedProcess([], 0, '',
            'ERROR: null root node returned by UiTestAutomationBridge.')
        with patch.object(module.subprocess, 'run', return_value=result):
            self.assertIn('null root node', module.Device('adb', 'test').shell('uiautomator', 'dump', '/tmp/ui.xml'))

    def test_fill_dismisses_keyboard_before_verifying_text(self):
        device = module.Device('adb', 'test')
        node = module.ET.Element('node', {'text': '', 'bounds': '[0,0][100,100]'})
        actual = module.ET.Element('node', {'text': 'https://example.com'})
        with patch.object(device, 'shell') as shell:
            def find(predicate):
                if not shell.call_args_list:
                    return node
                self.assertEqual(shell.call_args_list[-2].args,
                                 ('input', 'keyevent', 'KEYCODE_BACK'))
                self.assertEqual(shell.call_args_list[-1].args,
                                 ('input', 'keyevent', 'KEYCODE_TAB'))
                return actual
            with patch.object(device, 'find', side_effect=find):
                device.fill('enrollment_server', 'https://example.com')

    def test_visible_token_field_does_not_scroll(self):
        device = module.Device('adb', 'test')
        node = module.ET.Element('node', {'resource-id': module.PACKAGE + ':id/enrollment_token',
                                        'bounds': '[20,300][500,400]'})
        with patch.object(device, 'nodes', return_value=[node]), patch.object(device, 'scroll') as scroll:
            self.assertIs(device.find(lambda n: n.get('resource-id') == module.PACKAGE + ':id/enrollment_token'), node)
            scroll.assert_not_called()

    def test_hidden_token_field_scrolls_until_visible_then_stops(self):
        device = module.Device('adb', 'test')
        node = module.ET.Element('node', {'resource-id': module.PACKAGE + ':id/enrollment_token',
                                        'bounds': '[20,300][500,400]'})
        with patch.object(device, 'nodes', side_effect=[[], [node]]), patch.object(device, 'scroll') as scroll:
            self.assertIs(device.find(lambda n: n.get('resource-id') == module.PACKAGE + ':id/enrollment_token'), node)
            scroll.assert_called_once_with()

    def test_ui_recovers_after_three_null_root_errors(self):
        device = module.Device('adb', 'test')
        error = 'ERROR: null root node returned by UiTestAutomationBridge.'
        with patch.object(device, 'shell', side_effect=[error, '', error, '', error, '', 'ok', '']), \
                patch.object(device, 'run', return_value='<hierarchy><node text="Agent" /></hierarchy>'), \
                patch.object(device, 'scroll') as scroll, \
                patch.object(module.time, 'sleep'):
            self.assertEqual(device.nodes()[0].get('text'), 'Agent')
            scroll.assert_not_called()

    def test_ui_retries_transient_empty_dump_after_launch(self):
        device = module.Device('adb', 'test')
        xml = '<hierarchy><node text="Agent" /></hierarchy>'
        with patch.object(device, 'shell', return_value=''), \
                patch.object(device, 'run', side_effect=['', xml]) as run, \
                patch.object(module.time, 'sleep'):
            self.assertEqual(device.nodes()[0].get('text'), 'Agent')
            self.assertEqual(run.call_count, 2)

    def test_ui_retries_empty_accessibility_tree(self):
        device = module.Device('adb', 'test')
        with patch.object(device, '_dump_nodes', side_effect=[[], [ET_node := module.ET.Element('node')]]), \
                patch.object(module.time, 'sleep'):
            self.assertEqual(device.nodes(), [ET_node])

    def test_special_permissions_are_granted_and_verified_for_current_user(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=[
                '10', '35', '', 'GET_USAGE_STATS: allow; time=+1s', '',
                'SCHEDULE_EXACT_ALARM: allow']) as shell, patch.object(device, 'grant_location_access'), patch('builtins.print'):
            device.grant_special_access()
        self.assertIn(unittest.mock.call('appops', 'set', '--user', '10', module.PACKAGE,
                                        'SCHEDULE_EXACT_ALARM', 'allow'), shell.call_args_list)

    def test_older_android_skips_exact_alarm_appop(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['0', '30', '', 'GET_USAGE_STATS: allow']) as shell, patch.object(device, 'grant_location_access'), patch('builtins.print'):
            device.grant_special_access()
        self.assertFalse(any('SCHEDULE_EXACT_ALARM' in c.args for c in shell.call_args_list))

    def test_denied_appop_does_not_report_success(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['0', '35', '', 'GET_USAGE_STATS: ignore']):
            with self.assertRaisesRegex(ValueError, 'chua xac nhan'):
                device.grant_special_access()


class LocationAccessTests(unittest.TestCase):
    def grant_state(self, user='10', background=True):
        permissions = ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION']
        if background:
            permissions.append('ACCESS_BACKGROUND_LOCATION')
        return 'User ' + user + ':\n  runtime permissions:\n' + '\n'.join(
            '    android.permission.' + p + ': granted=true, flags=[]' for p in permissions)

    def test_current_user_gets_foreground_then_background_and_gps(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=[
                '', '', '', self.grant_state(), '', 'true']) as shell, patch('builtins.print'):
            device.grant_location_access('10', 35)
        grants = [c.args for c in shell.call_args_list if c.args[:2] == ('pm', 'grant')]
        self.assertEqual([g[-1] for g in grants], ['android.permission.ACCESS_COARSE_LOCATION',
                         'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_BACKGROUND_LOCATION'])
        self.assertTrue(all(g[2:5] == ('--user', '10', module.PACKAGE) for g in grants))
        self.assertIn(unittest.mock.call('cmd', 'location', 'set-location-enabled', 'true', '--user', '10'), shell.call_args_list)

    def test_android_9_skips_background_permission_and_enables_legacy_location(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=[
                '', '', self.grant_state(background=False), '', '3']) as shell, patch('builtins.print'):
            device.grant_location_access('10', 28)
        self.assertFalse(any('android.permission.ACCESS_BACKGROUND_LOCATION' in c.args for c in shell.call_args_list))
        self.assertIn(unittest.mock.call('settings', '--user', '10', 'put', 'secure', 'location_mode', '3'), shell.call_args_list)

    def test_another_users_grants_do_not_count(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['', '', '', self.grant_state('0')]):
            with self.assertRaisesRegex(ValueError, 'chua xac nhan quyen vi tri'):
                device.grant_location_access('10', 35)

    def test_location_switch_must_be_verified(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['', '', '', self.grant_state(), '', 'false']):
            with self.assertRaisesRegex(ValueError, 'dich vu vi tri/GPS'):
                device.grant_location_access('10', 35)


class DeviceNameTests(unittest.TestCase):
    def test_reads_unicode_phone_name(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['10', 'Điện thoại của An']) as shell:
            self.assertEqual(device.phone_name(), 'Điện thoại của An')
        self.assertEqual(shell.call_args_list[-1].args, ('settings', 'get', 'global', 'device_name'))

    def test_falls_back_to_model(self):
        device = module.Device('adb', 'test')
        with patch.object(device, 'shell', side_effect=['0', 'null', '', 'Galaxy A54']):
            self.assertEqual(device.phone_name(), 'Galaxy A54')

    def test_sets_and_reloads_name_as_utf8_text(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.enrollment_state.return_value = {'kind': 'existing', 'server': 'https://mdm.example', 'deviceId': 'uuid'}
        client.request.side_effect = [{'id': 42, 'number': 'uuid', 'description': None},
                                      None, {'description': 'Điện thoại An'}]
        with patch('builtins.print'):
            module.set_default_device_name(device, client, 'https://mdm.example', 'Điện thoại An')
        client.request.assert_any_call('private/devices/42/description', 'Điện thoại An'.encode('utf-8'),
                                       content_type='text/plain; charset=utf-8')
        self.assertEqual(client.request.call_count, 3)

    def test_preserves_existing_admin_name(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.enrollment_state.return_value = {'kind': 'existing', 'server': 'https://mdm.example', 'deviceId': 'uuid'}
        client.request.return_value = {'id': 42, 'number': 'uuid', 'description': 'Warehouse tablet'}
        with patch('builtins.print'):
            module.set_default_device_name(device, client, 'https://mdm.example', 'Galaxy')
        self.assertEqual(client.request.call_count, 1)

    def test_different_server_is_never_renamed(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.enrollment_state.return_value = {'kind': 'existing', 'server': 'https://other.example', 'deviceId': 'uuid'}
        with self.assertRaisesRegex(ValueError, 'dung server'):
            module.set_default_device_name(device, client, 'https://mdm.example', 'Galaxy')
        client.request.assert_not_called()

    def test_wrong_device_response_is_never_renamed(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.enrollment_state.return_value = {'kind': 'existing', 'server': 'https://mdm.example', 'deviceId': 'uuid'}
        client.request.return_value = {'id': 42, 'number': 'another'}
        with self.assertRaisesRegex(ValueError, 'dung device'):
            module.set_default_device_name(device, client, 'https://mdm.example', 'Galaxy')
        self.assertEqual(client.request.call_count, 1)


class ExistingEnrollmentTests(unittest.TestCase):
    def test_registration_succeeds_without_checkin_or_success_label(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        state = {'kind': 'existing', 'deviceId': 'registered-id', 'server': 'https://mdm.example.com'}
        device.enrollment_state.side_effect = [{'kind': 'new'}, state]
        device.is_owner.return_value = True
        client.request.return_value = {'id': 12, 'number': 'registered-id'}
        with patch.object(module.time, 'sleep'):
            self.assertEqual(module.wait_for_registration(device, client, state['server']), state)
        client.request.assert_called_once_with('private/devices/number/registered-id')
        device.nodes.assert_not_called()

    def test_registration_rejects_another_server(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.enrollment_state.return_value = {'kind': 'existing', 'deviceId': 'id', 'server': 'https://other.example'}
        with self.assertRaisesRegex(ValueError, 'may chu khac'):
            module.wait_for_registration(device, client, 'https://mdm.example')
        client.request.assert_not_called()

    def test_registration_requires_matching_server_record_and_owner(self):
        from unittest.mock import Mock
        for row, owner in [({'id': 1, 'number': 'other'}, True), ({'id': 1, 'number': 'id'}, False), (None, True)]:
            device, client = Mock(), Mock()
            device.enrollment_state.return_value = {'kind': 'existing', 'deviceId': 'id', 'server': 'https://mdm.example'}
            device.is_owner.return_value = owner
            client.request.return_value = row
            with patch.object(module.time, 'monotonic', side_effect=[0, 0, 61]), patch.object(module.time, 'sleep'):
                with self.assertRaisesRegex(ValueError, 'Chua xac minh'):
                    module.wait_for_registration(device, client, 'https://mdm.example')

    def test_existing_main_flow_does_not_mint_or_fill_another_token(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.serial = 'test-device'
        device.run.return_value = 'Success'
        device.is_owner.return_value = True
        state = {'kind': 'existing', 'server': 'https://mdm.example.com', 'deviceId': 'existing-id'}
        device.enrollment_state.return_value = state
        with patch.object(module.sys, 'argv', ['enroll-device-adb.py', '--enroll']), \
                patch.object(module, 'read_env', return_value={'MDM_ADMIN_USERNAME': 'admin', 'MDM_ADMIN_PASSWORD': 'test'}), \
                patch.object(module, 'ask', side_effect=['https://mdm.example.com', 'y']), \
                patch.object(module, 'select_device', return_value=device), \
                patch.object(module.release, 'Client', return_value=client), \
                patch.object(module, 'login'), patch.object(module, 'download_apk', return_value=Path('/test.apk')), \
                patch.object(module, 'newest_published', return_value={'versionName': 'test', 'versionCode': 1045}), \
                patch.object(module, 'verify_existing_enrollment') as verify, \
                patch.object(module, 'wait_for_registration') as confirm, \
                patch.object(module, 'mint_token') as mint, patch('builtins.print'):
            self.assertEqual(module.main(), 0)
        verify.assert_not_called()
        confirm.assert_called_once_with(device, client, 'https://mdm.example.com')
        mint.assert_not_called()
        device.fill.assert_not_called()

    def test_restored_usage_tab_is_switched_to_overview_before_scanning(self):
        device = module.Device('adb', 'test')
        home = module.ET.fromstring('<node text="Tổng quan" bounds="[0,0][100,100]"/>')
        field = module.ET.fromstring('<node resource-id="com.mdmesh.agent:id/enrollment_server"/>')
        with patch.object(device, 'scroll'), patch.object(device, 'tap') as tap, \
                patch.object(device, 'nodes', side_effect=[[home], [field]]):
            self.assertEqual(device.enrollment_state(), {'kind': 'new'})
        tap.assert_called_once_with(home)

    def test_fresh_form_does_not_need_existing_identity(self):
        device = module.Device('adb', 'test')
        field = module.ET.fromstring('<node resource-id="com.mdmesh.agent:id/enrollment_server"/>')
        with patch.object(device, 'scroll'), patch.object(device, 'nodes', return_value=[field]):
            self.assertEqual(device.enrollment_state(), {'kind': 'new'})

    def test_opens_support_details_and_reads_existing_registration(self):
        device = module.Device('adb', 'test')
        toggle = module.ET.fromstring('<node text="Thông tin hỗ trợ  +" bounds="[0,0][100,100]"/>')
        details = [module.ET.fromstring('<node text="01234567-1234-1234-1234-012345678901"/>'),
                   module.ET.fromstring('<node text="https://mdm.example.com/"/>')]
        with patch.object(device, 'scroll'), patch.object(device, 'tap') as tap, \
                patch.object(device, 'nodes', side_effect=[[toggle], details]):
            state = device.enrollment_state()
        tap.assert_called_once_with(toggle)
        self.assertEqual(state, {'kind': 'existing', 'deviceId': '01234567-1234-1234-1234-012345678901',
                                 'server': 'https://mdm.example.com'})

    def test_managed_label_alone_is_not_enough_to_claim_enrollment(self):
        device = module.Device('adb', 'test')
        label = module.ET.fromstring('<node text="Thiết bị đã được quản lý"/>')
        with patch.object(device, 'scroll'), patch.object(device, 'nodes', return_value=[label]):
            with self.assertRaisesRegex(ValueError, 'Khong thay form'):
                device.enrollment_state()

    def test_existing_registration_requires_new_checkin_and_device_owner(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.is_owner.return_value = True
        client.request.side_effect = [{'updatedAt': 100}, {}, {'updatedAt': 101}]
        state = {'server': 'https://mdm.example.com', 'deviceId': 'existing-id'}
        with patch('builtins.print'):
            module.verify_existing_enrollment(device, client, state, state['server'])
        self.assertEqual([call.args[0] for call in client.request.call_args_list], [
            'private/agent/v1/devices/existing-id/state',
            'private/agent/v1/devices/existing-id/sync',
            'private/agent/v1/devices/existing-id/state'])

    def test_stale_checkin_is_not_treated_as_recovery(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        client.request.return_value = {'updatedAt': 100}
        state = {'server': 'https://mdm.example.com', 'deviceId': 'existing-id'}
        with patch.object(module.time, 'monotonic', side_effect=[0, 0, 61]), \
                patch.object(module.time, 'sleep'), patch('builtins.print'):
            with self.assertRaisesRegex(ValueError, 'chua check-in'):
                module.verify_existing_enrollment(device, client, state, state['server'])
        device.is_owner.assert_not_called()

    def test_fresh_checkin_without_owner_is_not_recovery(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        device.is_owner.return_value = False
        client.request.side_effect = [{'updatedAt': 100}, {}, {'updatedAt': 101}]
        state = {'server': 'https://mdm.example.com', 'deviceId': 'existing-id'}
        with patch.object(module.time, 'monotonic', side_effect=[0, 0, 61]), \
                patch.object(module.time, 'sleep'), patch('builtins.print'):
            with self.assertRaisesRegex(ValueError, 'chua check-in'):
                module.verify_existing_enrollment(device, client, state, state['server'])

    def test_different_server_is_not_overwritten(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        state = {'server': 'https://old.example.com', 'deviceId': 'existing-id'}
        with self.assertRaisesRegex(ValueError, 'may chu khac'):
            module.verify_existing_enrollment(device, client, state, 'https://new.example.com')
        client.request.assert_not_called()
        device.shell.assert_not_called()

    def test_missing_server_registration_has_actionable_error(self):
        from unittest.mock import Mock
        device, client = Mock(), Mock()
        client.request.side_effect = ValueError('unknown device')
        state = {'server': 'https://mdm.example.com', 'deviceId': 'existing-id'}
        with self.assertRaisesRegex(ValueError, 'ID dang ky cu'):
            module.verify_existing_enrollment(device, client, state, state['server'])
        self.assertEqual(client.request.call_count, 1)


if __name__ == '__main__':
    unittest.main()
