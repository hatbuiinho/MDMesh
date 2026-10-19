#!/usr/bin/env python3
"""Interactive ADB enrollment using a freshly minted MDMesh API token."""
import argparse
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import tempfile
import urllib.request
import uuid
import urllib.error
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.mdmesh.agent'
ADMIN = PACKAGE + '/com.mdmesh.agent.admin.AdminReceiver'


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load_module('mdmesh_release', ROOT / 'scripts/publish-release.py')


def read_env(path):
    # Same literal quoting rules as release-env; never source shell commands.
    result = {}
    for number, line in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
        match = re.match(r'^\s*(?:export\s+)?(MDM_BASE_URL|MDM_ADMIN_USERNAME|MDM_ADMIN_PASSWORD)\s*=(.*)$', line)
        if not match:
            continue
        key, value = match[1], match[2].strip()
        if value.startswith(('"', "'")):
            end = value.find(value[0], 1)
            if end < 0 or (value[end + 1:].strip() and not value[end + 1:].lstrip().startswith('#')):
                raise ValueError(f'Giá trị .env không hợp lệ ở dòng {number}.')
            value = value[1:end]
        else:
            value = re.split(r'\s+#', value, maxsplit=1)[0].rstrip()
        result[key] = value
    return result


def ask(label, default=''):
    if not sys.stdin.isatty():
        raise ValueError('Script tương tác cần terminal. Chạy trực tiếp lệnh trong Terminal.')
    return input(f'{label}' + (f' [{default}]' if default else '') + ': ').strip() or default


def validate_url(base):
    url = urllib.parse.urlsplit(base)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.query or url.fragment or not base.isascii()
            or re.search(r'[\s%\x00-\x1f]', base)):
        raise ValueError('URL phải là HTTPS, không chứa thông tin đăng nhập, query, fragment hoặc ký tự escape.')
    return base.rstrip('/')


def login(client, username, password):
    options = client.request('public/auth/options') or {}
    if options.get('publicKey'):
        raise ValueError('Server bật RSA login; helper chưa hỗ trợ chế độ này.')
    user = client.request('public/auth/login', json.dumps({
        'login': username, 'password': hashlib.md5(password.encode()).hexdigest().upper(),
    }).encode()) or {}
    if user.get('passwordReset') or user.get('twoFactor'):
        raise ValueError('Tài khoản yêu cầu đổi mật khẩu hoặc xác thực hai bước.')


def mint_token(client):
    result = client.request('private/agent/v1/token', b'{}') or {}
    token = result.get('token', '')
    if not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', token):
        raise ValueError('API không trả về enrollment token hợp lệ.')
    return token


def newest_published(client):
    catalog = client.request('private/agent/v1/releases') or []
    if not isinstance(catalog, list):
        raise ValueError('Danh sách release từ server không hợp lệ.')
    candidates = [r for r in catalog if isinstance(r, dict)
                  and r.get('packageName') == PACKAGE
                  and type(r.get('versionCode')) is int and r['versionCode'] > 0]
    if not candidates:
        raise ValueError('Server chưa có APK release com.mdmesh.agent đã publish.')
    highest = max(r['versionCode'] for r in candidates)
    newest = [r for r in candidates if r['versionCode'] == highest]
    if len({r.get('sha256') for r in newest}) != 1:
        raise ValueError('Server có nhiều APK khác nhau cùng versionCode mới nhất; kiểm tra catalog release.')
    selected = newest[0]
    if not re.fullmatch(r'[0-9a-fA-F]{64}', selected.get('sha256', '')):
        raise ValueError('Release trên server thiếu SHA256 hợp lệ.')
    return selected


def download_apk(metadata, destination, opener=None):
    url = metadata.get('url', '')
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username
            or parsed.password or parsed.fragment):
        raise ValueError('URL APK trên server phải là HTTPS hợp lệ.')
    # APK files are public. Do not forward the admin session to download hosts.
    opener = opener or urllib.request.build_opener(release.NoRedirect())
    digest = hashlib.sha256()
    size = 0
    with opener.open(urllib.request.Request(url), timeout=180) as response, destination.open('wb') as output:
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            size += len(chunk)
            if size > 128 * 1024 * 1024:
                raise ValueError('APK tải về vượt quá 128 MiB.')
            digest.update(chunk)
            output.write(chunk)
    if not size or digest.hexdigest() != metadata['sha256'].lower():
        destination.unlink(missing_ok=True)
        raise ValueError('APK tải về không khớp SHA256 của release; dừng trước khi cài.')
    return destination


class Device:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def run(self, *args):
        result = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
        if result.returncode:
            command = shlex.split(args[1]) if args[0] == 'shell' else list(args)
            # Never expose text-input argv or stderr (the token can be echoed by Android).
            if command[:2] == ['input', 'text']:
                raise ValueError('ADB không nhập được dữ liệu trên thiết bị; mở khóa màn hình rồi thử lại.')
            detail = (result.stderr + '\n' + result.stdout).strip()
            if 'already some accounts' in detail:
                raise ValueError('Không thể thiết lập Device Owner vì thiết bị đang có tài khoản. '
                                 'Gỡ tất cả tài khoản trong Cài đặt Android → Tài khoản, rồi chạy lại script. '
                                 'Script không tự gỡ tài khoản hoặc xóa dữ liệu.')
            label = ' '.join(command[:2])
            raise ValueError(f'ADB thất bại ở bước {label}: {detail[:2000] or "không có thông báo từ Android"}')
        return result.stdout

    def shell(self, *args):
        return self.run('shell', shlex.join(args))

    def is_owner(self):
        policy = self.shell('dumpsys', 'device_policy')
        section = re.search(r'Device Owner:(.*?)(?=\n\s*User ID:|\n\s*Profile Owner|\Z)', policy, re.S)
        return bool(section and ADMIN in section[1])

    def grant_special_access(self):
        user = self.shell('am', 'get-current-user').strip()
        if not user.isdigit():
            raise ValueError('Không xác định được user Android hiện tại.')
        sdk = int(self.shell('getprop', 'ro.build.version.sdk').strip())
        operations = [('GET_USAGE_STATS', 'Usage Access')]
        if sdk >= 31:
            operations.append(('SCHEDULE_EXACT_ALARM', 'Chuông báo và lời nhắc'))
        for operation, label in operations:
            self.shell('appops', 'set', '--user', user, PACKAGE, operation, 'allow')
            state = self.shell('appops', 'get', '--user', user, PACKAGE, operation)
            if not re.search(r'(?m)^\s*' + re.escape(operation) + r':\s*allow\b', state):
                raise ValueError(f'Android chưa xác nhận quyền {label} đã được cho phép.')
            print(f'Đã cấp và xác minh: {label}', flush=True)
        if sdk < 31:
            print('Android dưới 12: không cần cấp riêng quyền Chuông báo và lời nhắc.', flush=True)

    def nodes(self):
        # Some Android builds only print a success message for /dev/tty and
        # discard the XML. Use a unique temporary file, read it, then remove it.
        remote = '/data/local/tmp/mdmesh-ui-' + uuid.uuid4().hex + '.xml'
        try:
            status = self.shell('uiautomator', 'dump', remote)
            if 'ERROR:' in status:
                raise ValueError('UI Automator chưa đọc được màn hình: ' + status.strip()[:300])
            output = self.run('exec-out', 'cat', remote)
        finally:
            try:
                self.shell('rm', '-f', remote)
            except (ValueError, OSError, subprocess.SubprocessError):
                pass
        start, end = output.find('<hierarchy'), output.rfind('</hierarchy>')
        if start < 0 or end < 0:
            raise ValueError('UI Automator không trả về XML giao diện hợp lệ. Kiểm tra Agent đang ở phía trước và thử lại.')
        return list(ET.fromstring(output[start:end + len('</hierarchy>')]).iter('node'))

    def scroll(self, upward=False):
        size = re.findall(r'(\d+)x(\d+)', self.shell('wm', 'size'))[-1]
        width, height = map(int, size)
        y1, y2 = int(height * .78), int(height * .3)
        if upward:
            y1, y2 = y2, y1
        self.shell('input', 'swipe', str(width // 2), str(y1), str(width // 2), str(y2), '350')

    def find(self, predicate):
        for _ in range(6):
            for node in self.nodes():
                if predicate(node):
                    bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                    if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                        return node
            self.scroll()
        raise ValueError('Không tìm thấy trường đăng ký. Thiết bị có thể đã enroll; kiểm tra Agent. Không tạo thêm token tự động.')

    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
        self.shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

    def fill(self, name, text):
        node = self.find(lambda n: n.get('resource-id') == PACKAGE + ':id/' + name)
        self.tap(node)
        self.shell('input', 'keyevent', 'KEYCODE_MOVE_END')
        self.shell('input', 'keyevent', *(['KEYCODE_DEL'] * (len(node.get('text', '')) + 1)))
        self.shell('input', 'text', text)
        # Confirm text before submitting a one-time token.
        actual = self.find(lambda n: n.get('resource-id') == PACKAGE + ':id/' + name)
        if actual.get('text') != text:
            raise ValueError('Không điền được dữ liệu chính xác trên thiết bị; dừng trước khi gửi đăng ký.')
        self.shell('input', 'keyevent', 'KEYCODE_BACK')


def select_device(requested_serial):
    adb = os.environ.get('ADB') or shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
    output = subprocess.run([adb, 'devices'], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True, timeout=30).stdout
    devices = [line.split()[0] for line in output.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
    if not devices:
        raise ValueError('Không có thiết bị ADB đã authorize. Bật USB debugging và xác nhận máy tính.')
    serial = requested_serial
    if serial is None:
        for number, value in enumerate(devices, 1):
            print(f'  {number}. {value}')
        choice = ask('Chọn thiết bị', '1')
        if not choice.isdigit() or not 1 <= int(choice) <= len(devices):
            raise ValueError('Lựa chọn thiết bị không hợp lệ.')
        serial = devices[int(choice) - 1]
    if serial not in devices:
        raise ValueError('Thiết bị đã chọn chưa sẵn sàng.')
    return Device(adb, serial)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--permissions-only', action='store_true', help='Chỉ cấp Usage Access và Chuông báo/lời nhắc; không cài APK hoặc enroll')
    parser.add_argument('--base-url')
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    args = parser.parse_args()
    download_directory = None
    try:
        if args.permissions_only:
            select_device(args.serial).grant_special_access()
            return 0
        settings = read_env(args.env_file) if args.env_file.is_file() else {}
        if not args.env_file.is_file() and args.env_file != ROOT / '.env':
            raise ValueError('Không tìm thấy --env-file.')
        def setting(key, default=''):
            return os.environ.get(key, settings.get(key, default))
        base = validate_url(ask('URL MDMesh', args.base_url or setting('MDM_BASE_URL', 'https://mdm.hatbuinho.me')))
        device = select_device(args.serial)
        serial = device.serial
        username = setting('MDM_ADMIN_USERNAME') or ask('Tài khoản MDMesh', 'admin')
        password = setting('MDM_ADMIN_PASSWORD') or getpass.getpass(f'Mật khẩu MDMesh ({username}): ')
        print('Đang đăng nhập và tìm APK mới nhất đã publish trên server…', flush=True)
        client = release.Client(base)
        login(client, username, password)
        password = None
        published = newest_published(client)
        print(f"APK server: {published.get('versionName', '')} (versionCode {published['versionCode']})"
              f"\nThiết bị: {serial}\nServer: {base}")
        if ask('Tải APK server, cài và enroll thiết bị này? (y/n)', 'y').lower() not in ('y', 'yes'):
            print('Đã hủy.'); return 0
        download_directory = tempfile.TemporaryDirectory(prefix='mdmesh-enroll-')
        print('Đang tải APK và kiểm tra SHA256…', flush=True)
        apk = download_apk(published, Path(download_directory.name) / 'mdmesh-published.apk')
        installed = device.run('install', '-r', str(apk))
        if 'Success' not in installed:
            raise ValueError('ADB chưa xác nhận cài APK thành công.')
        if not device.is_owner():
            print('Đang thiết lập Device Owner…', flush=True)
            device.shell('dpm', 'set-device-owner', ADMIN)
        if not device.is_owner():
            raise ValueError('Chưa có Device Owner. Kiểm tra tài khoản/owner cũ. Script không xóa dữ liệu.')
        device.grant_special_access()
        device.shell('am', 'start', '-W', '-n', PACKAGE + '/com.mdmesh.agent.MainActivity')
        device.fill('enrollment_server', base)
        # Verify the form is available BEFORE minting the token.
        device.find(lambda n: n.get('resource-id') == PACKAGE + ':id/enrollment_token')
        print('Đang tạo token mới…', flush=True)
        token = mint_token(client)
        device.fill('enrollment_token', token)
        token = None
        button = device.find(lambda n: n.get('class') == 'android.widget.Button' and n.get('text') == 'Đăng ký thiết bị')
        device.tap(button)
        print('Đã gửi đăng ký. Đang chờ Agent xác nhận…', flush=True)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            device.scroll(upward=True)
            texts = [n.get('text', '') for n in device.nodes()]
            if any('Thiết bị đã được quản lý' in t for t in texts) and any('Kết nối thành công lúc' in t for t in texts):
                print('Enroll thành công: Agent xác nhận thiết bị đã được quản lý và kết nối server.')
                return 0
            time.sleep(2)
        raise ValueError('Hết thời gian chờ xác nhận. Kiểm tra device ID/trạng thái trong Agent; không tự chạy lại để tránh tạo token thừa.')
    except (OSError, ValueError, subprocess.SubprocessError, ET.ParseError, urllib.error.URLError) as error:
        if isinstance(error, urllib.error.HTTPError):
            message = f'API trả HTTP {error.code}; kiểm tra URL, tài khoản và quyền edit_devices.'
        elif isinstance(error, (subprocess.SubprocessError, urllib.error.URLError)):
            message = 'Không kết nối được ADB/API; kiểm tra thiết bị và server.'
        else:
            message = str(error)
        print(f'Lỗi: {message}', file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print('\nĐã hủy.', file=sys.stderr)
        return 130
    finally:
        if download_directory is not None:
            download_directory.cleanup()


if __name__ == '__main__':
    raise SystemExit(main())
