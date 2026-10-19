#!/usr/bin/env python3
"""Exercise unattended release orchestration using a fake Docker command, without production keys."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/build-android-release-docker.sh'

FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['TEST_DOCKER_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\n')
cert = 'a' * 64
if args[0] in ('info', 'image'):
    sys.exit(0)
if '--entrypoint' in args:
    entry = args[args.index('--entrypoint') + 1]
    if entry.endswith('apksigner'):
        print('Signer #1 certificate SHA-256 digest: ' + cert)
    elif entry.endswith('aapt'):
        print("package: name='com.mdmesh.agent' versionCode='1000' versionName='1.0.0'")
    else:
        assert os.environ['MDM_RELEASE_STORE_PASSWORD'] == os.environ['TEST_EXPECT_STORE']
        print(('b' * 64 if os.environ.get('TEST_CERT_MISMATCH') else cert) + '  -')
else:
    assert os.environ['MDM_RELEASE_STORE_PASSWORD'] == os.environ['TEST_EXPECT_STORE']
    assert os.environ['MDM_RELEASE_KEY_PASSWORD'] == os.environ['TEST_EXPECT_KEY']
    assert '--no-configuration-cache' in args
    mount = next(a for a in args if a.startswith('type=bind,src=') and a.endswith(',dst=/output'))
    output = Path(mount.split(',src=', 1)[1].rsplit(',dst=', 1)[0])
    apk = output / 'app/build/outputs/apk/release/app-release.apk'
    apk.parent.mkdir(parents=True)
    apk.write_bytes(b'test APK')
'''


class ReleaseEnvTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.bin = self.work / 'bin'
        self.bin.mkdir()
        (self.bin / 'docker').write_text(FAKE_DOCKER)
        (self.bin / 'docker').chmod(0o755)
        self.key = self.work / 'release key.jks'
        self.reference = self.work / 'production.apk'
        self.key.touch()
        self.reference.touch()
        self.log = self.work / 'docker.log'
        self.env_file = self.work / '.env'
        history = [ROOT / '.android-docker/releases', ROOT / 'releases/android',
                   ROOT / 'agent-android/app/build/distributions']
        known = [int(p.name) for root in history if root.exists() for p in root.iterdir()
                 if p.is_dir() and p.name.isdecimal() and 0 < int(p.name) <= 2100000000]
        self.next_code = max([1000] + known) + 1
        self.password = 'literal # dollar$ `tick` $(touch ' + str(self.work / 'should-not-exist') + ')'
        self.settings = {
            'MDM_RELEASE_STORE_FILE': str(self.key), 'MDM_REFERENCE_APK': str(self.reference),
            'MDM_RELEASE_OUTPUT_ROOT': str(self.work / 'output'),
            'MDM_RELEASE_VERSION_NAME': '1.0.1-test', 'MDM_BASE_URL': 'https://mdm.example.test/',
            'MDM_RELEASE_STORE_PASSWORD': self.password,
        }
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(('MDM_RELEASE_', 'MDM_REFERENCE_', 'MDM_BASE_', 'MDM_APK_'))}
        self.env.update(PATH=str(self.bin) + os.pathsep + self.env['PATH'],
                        TEST_DOCKER_LOG=str(self.log), TEST_EXPECT_STORE=self.password, TEST_EXPECT_KEY=self.password)

    def run_script(self, extra_env=None):
        self.env_file.write_text('\n'.join(f"{key}='{value}'" for key, value in self.settings.items()) + '\n')
        result = subprocess.run(['bash', str(SCRIPT), '--non-interactive', '--env-file', str(self.env_file)],
                                env={**self.env, **(extra_env or {})}, text=True, capture_output=True,
                                stdin=subprocess.DEVNULL, timeout=20)
        combined = result.stdout + result.stderr + (self.log.read_text() if self.log.exists() else '')
        self.assertNotIn(self.password, combined)
        self.assertFalse((self.work / 'should-not-exist').exists())
        return result

    def test_unattended_build_literal_password_and_fallback(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        output = self.work / 'output' / str(self.next_code)
        for name in ('mdmesh-custom.apk', 'SHA256SUMS', 'web-build.env', 'release-info.txt'):
            self.assertTrue((output / name).is_file(), name)
        self.assertIn('serverBaseUrl=https://mdm.example.test/', (output / 'release-info.txt').read_text())

    def test_repeated_build_increases_code_with_same_reference(self):
        for code in (self.next_code, self.next_code + 1):
            result = self.run_script()
            self.assertEqual(result.returncode, 0, result.stderr)
            output = self.work / 'output' / str(code)
            self.assertIn(f'versionCode={code}\n', (output / 'release-info.txt').read_text())
            self.assertIn(f'-PversionCode={code}', self.log.read_text())

    def test_environment_overrides_env_file_and_key_password(self):
        store, key = 'override # $ store', 'separate # $ key'
        result = self.run_script({'MDM_RELEASE_STORE_PASSWORD': store, 'MDM_RELEASE_KEY_PASSWORD': key,
                                  'TEST_EXPECT_STORE': store, 'TEST_EXPECT_KEY': key})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(store, result.stdout + result.stderr + self.log.read_text())
        self.assertNotIn(key, result.stdout + result.stderr + self.log.read_text())

    def test_missing_password_fails_without_waiting_or_building(self):
        self.settings.pop('MDM_RELEASE_STORE_PASSWORD')
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('MDM_RELEASE_STORE_PASSWORD', result.stderr)
        self.assertNotIn(':app:assembleRelease', self.log.read_text())

    def test_invalid_version_fails_without_looping(self):
        self.settings['MDM_RELEASE_VERSION_CODE'] = '999'
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(':app:assembleRelease', self.log.read_text())

    def test_certificate_mismatch_stops_before_build(self):
        result = self.run_script({'TEST_CERT_MISMATCH': '1'})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(':app:assembleRelease', self.log.read_text())

    def test_existing_output_is_not_overwritten(self):
        output = self.work / 'output' / str(self.next_code)
        output.mkdir(parents=True)
        (output / 'sentinel').write_text('keep')
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((output / 'sentinel').read_text(), 'keep')
        self.assertTrue((output.parent / str(self.next_code + 1) / 'mdmesh-custom.apk').is_file())

    def test_bad_quoting_is_redacted_and_does_not_run_docker(self):
        self.env_file.write_text("MDM_RELEASE_STORE_PASSWORD='secret-without-closing-quote\n")
        result = subprocess.run(['bash', str(SCRIPT), '--non-interactive', '--env-file', str(self.env_file)],
                                env=self.env, capture_output=True, text=True, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('secret-without-closing-quote', result.stdout + result.stderr)
        self.assertFalse(self.log.exists())

    def test_native_helper_reads_passwords_without_terminal(self):
        sdk = self.work / 'sdk'
        (sdk / 'platforms/android-35').mkdir(parents=True)
        signer = self.bin / 'apksigner'
        signer.write_text('#!/bin/sh\nexit 0\n')
        signer.chmod(0o755)
        aapt = self.bin / 'aapt'
        aapt.write_text("#!/bin/sh\nprintf \"package: name='com.mdmesh.agent' versionCode='1000'\\n\"\n")
        aapt.chmod(0o755)
        java = self.bin / 'java'
        java.write_text('#!/bin/sh\nexit 0\n')
        java.chmod(0o755)
        keytool = self.bin / 'keytool'
        keytool.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
assert os.environ['MDM_RELEASE_STORE_PASSWORD'] == os.environ['TEST_EXPECT_STORE']
assert os.environ['MDM_RELEASE_KEY_PASSWORD'] == os.environ['TEST_EXPECT_KEY']
assert '-storepass:env' in sys.argv
Path(os.environ['TEST_DOCKER_LOG']).write_text('credentials checked')
# Stop before Gradle: this test exercises credential forwarding without signing an artifact.
sys.exit(17)
''')
        keytool.chmod(0o755)
        self.settings.update(MDM_RELEASE_VERSION_CODE='2099999999')
        self.env_file.write_text('\n'.join(f"{key}='{value}'" for key, value in self.settings.items()) + '\n')
        result = subprocess.run(['bash', str(ROOT / 'scripts/build-android-release.sh'),
                                 '--non-interactive', '--env-file', str(self.env_file)],
                                env={**self.env, 'JAVA_HOME': '', 'ANDROID_HOME': str(sdk), 'APKSIGNER': str(signer)},
                                capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=20)
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertEqual(self.log.read_text(), 'credentials checked')
        self.assertNotIn(self.password, result.stdout + result.stderr)

        self.settings['MDM_RELEASE_VERSION_CODE'] = 'auto'
        self.env_file.write_text('\n'.join(f"{key}='{value}'" for key, value in self.settings.items()) + '\n')
        automatic = subprocess.run(['bash', str(ROOT / 'scripts/build-android-release.sh'),
                                    '--non-interactive', '--env-file', str(self.env_file)],
                                   env={**self.env, 'JAVA_HOME': '', 'ANDROID_HOME': str(sdk), 'APKSIGNER': str(signer)},
                                   capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=20)
        self.assertEqual(automatic.returncode, 17, automatic.stderr)
        self.assertIn(f'Release versionCode: {self.next_code}', automatic.stdout)
        self.assertNotIn(self.password, automatic.stdout + automatic.stderr)


if __name__ == '__main__':
    unittest.main()
