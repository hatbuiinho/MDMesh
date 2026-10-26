#!/usr/bin/env python3
"""Interactive ADB enrollment or verified recovery of an existing MDMesh registration."""
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
                raise ValueError(f'Gia tri .env khong hop le o dong {number}.')
            value = value[1:end]
        else:
            value = re.split(r'\s+#', value, maxsplit=1)[0].rstrip()
        result[key] = value
    return result


def ask(label, default=''):
    if not sys.stdin.isatty():
        raise ValueError('Script tuong tac can terminal. Chay truc tiep lenh trong Terminal.')
    return input(f'{label}' + (f' [{default}]' if default else '') + ': ').strip() or default


def validate_url(base):
    url = urllib.parse.urlsplit(base)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.query or url.fragment or not base.isascii()
            or re.search(r'[\s%\x00-\x1f]', base)):
        raise ValueError('URL phai la HTTPS, khong chua thong tin dang nhap, query, fragment hoac ky tu escape.')
    return base.rstrip('/')


def login(client, username, password):
    options = client.request('public/auth/options') or {}
    if options.get('publicKey'):
        raise ValueError('Server bat RSA login; helper chua ho tro che do nay.')
    user = client.request('public/auth/login', json.dumps({
        'login': username, 'password': hashlib.md5(password.encode()).hexdigest().upper(),
    }).encode()) or {}
    if user.get('passwordReset') or user.get('twoFactor'):
        raise ValueError('Tai khoan yeu cau doi mat khau hoac xac thuc hai buoc.')


def mint_token(client):
    result = client.request('private/agent/v1/token', b'{}') or {}
    token = result.get('token', '')
    if not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', token):
        raise ValueError('API khong tra ve enrollment token hop le.')
    return token


def newest_published(client):
    catalog = client.request('private/agent/v1/releases') or []
    if not isinstance(catalog, list):
        raise ValueError('Danh sach release tu server khong hop le.')
    candidates = [r for r in catalog if isinstance(r, dict)
                  and r.get('packageName') == PACKAGE
                  and type(r.get('versionCode')) is int and r['versionCode'] > 0]
    if not candidates:
        raise ValueError('Server chua co APK release com.mdmesh.agent da publish.')
    highest = max(r['versionCode'] for r in candidates)
    newest = [r for r in candidates if r['versionCode'] == highest]
    if len({r.get('sha256') for r in newest}) != 1:
        raise ValueError('Server co nhieu APK khac nhau cung versionCode moi nhat; kiem tra catalog release.')
    selected = newest[0]
    if not re.fullmatch(r'[0-9a-fA-F]{64}', selected.get('sha256', '')):
        raise ValueError('Release tren server thieu SHA256 hop le.')
    return selected


def download_apk(metadata, destination, opener=None):
    url = metadata.get('url', '')
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username
            or parsed.password or parsed.fragment):
        raise ValueError('URL APK tren server phai la HTTPS hop le.')
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
                raise ValueError('APK tai ve vuot qua 128 MiB.')
            digest.update(chunk)
            output.write(chunk)
    if not size or digest.hexdigest() != metadata['sha256'].lower():
        destination.unlink(missing_ok=True)
        raise ValueError('APK tai ve khong khop SHA256 cua release; dung truoc khi cai.')
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
                raise ValueError('ADB khong nhap duoc du lieu tren thiet bi; mo khoa man hinh roi thu lai.')
            detail = (result.stderr + '\n' + result.stdout).strip()
            if 'already some accounts' in detail:
                raise ValueError('Khong the thiet lap Device Owner vi thiet bi dang co tai khoan. '
                                 'Go tat ca tai khoan trong Cai dat Android -> Tai khoan, roi chay lai script. '
                                 'Script khong tu go tai khoan hoac xoa du lieu.')
            label = ' '.join(command[:2])
            raise ValueError(f'ADB that bai o buoc {label}: {detail[:2000] or "khong co thong bao tu Android"}')
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
            raise ValueError('Khong xac dinh duoc user Android hien tai.')
        sdk = int(self.shell('getprop', 'ro.build.version.sdk').strip())
        operations = [('GET_USAGE_STATS', 'Usage Access')]
        if sdk >= 31:
            operations.append(('SCHEDULE_EXACT_ALARM', 'Chuong bao va loi nhac'))
        for operation, label in operations:
            self.shell('appops', 'set', '--user', user, PACKAGE, operation, 'allow')
            state = self.shell('appops', 'get', '--user', user, PACKAGE, operation)
            if not re.search(r'(?m)^\s*' + re.escape(operation) + r':\s*allow\b', state):
                raise ValueError(f'Android chua xac nhan quyen {label} da duoc cho phep.')
            print(f'Da cap va xac minh: {label}', flush=True)
        if sdk < 31:
            print('Android duoi 12: khong can cap rieng quyen Chuong bao va loi nhac.', flush=True)

    def nodes(self):
        # Some Android builds only print a success message for /dev/tty and
        # discard the XML. Use a unique temporary file, read it, then remove it.
        remote = '/data/local/tmp/mdmesh-ui-' + uuid.uuid4().hex + '.xml'
        try:
            status = self.shell('uiautomator', 'dump', remote)
            if 'ERROR:' in status:
                raise ValueError('UI Automator chua doc duoc man hinh: ' + status.strip()[:300])
            output = self.run('exec-out', 'cat', remote)
        finally:
            try:
                self.shell('rm', '-f', remote)
            except (ValueError, OSError, subprocess.SubprocessError):
                pass
        start, end = output.find('<hierarchy'), output.rfind('</hierarchy>')
        if start < 0 or end < 0:
            raise ValueError('UI Automator khong tra ve XML giao dien hop le. Kiem tra Agent dang o phia truoc va thu lai.')
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
        raise ValueError('Khong tim thay truong can thao tac. Giu Agent o tab Tong quan, mo khoa man hinh roi thu lai. Khong tao them token tu dong.')

    def enrollment_state(self):
        """Inspect the visible form or support details without reading private app storage."""
        identifiers, servers = set(), set()
        expanded = False
        home_selected = False
        self.scroll(upward=True)
        for _ in range(10):
            nodes = self.nodes()
            if not home_selected:
                home = next((n for n in nodes if n.get('text') == 'T\u1ed5ng quan'), None)
                home_selected = True
                if home is not None:
                    self.tap(home)
                    self.scroll(upward=True)
                    continue
            for node in nodes:
                if node.get('resource-id') == PACKAGE + ':id/enrollment_server':
                    return {'kind': 'new'}
                text = node.get('text', '').strip()
                if re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', text):
                    identifiers.add(text)
                if re.fullmatch(r'https://[^\s]+', text):
                    servers.add(text.rstrip('/'))
            if len(identifiers) == 1 and len(servers) == 1:
                return {'kind': 'existing', 'deviceId': next(iter(identifiers)), 'server': next(iter(servers))}
            toggle = next((n for n in nodes if n.get('text', '').startswith('Th\xf4ng tin h\u1ed7 tr\u1ee3')
                           and n.get('text', '').endswith('+')), None)
            if toggle is not None and not expanded:
                self.tap(toggle)
                expanded = True
                continue
            self.scroll()
        raise ValueError('Khong thay form dang ky hoac thong tin ho tro cua Agent. '
                         'Mo tab Tong quan va mo khoa man hinh; chua tao token hay xoa du lieu.')

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
            raise ValueError('Khong dien duoc du lieu chinh xac tren thiet bi; dung truoc khi gui dang ky.')
        self.shell('input', 'keyevent', 'KEYCODE_BACK')


def verify_existing_enrollment(device, client, state, base, timeout=60):
    """Prove a fresh authenticated check-in before reporting idempotent recovery as success."""
    if state['server'].rstrip('/') != base.rstrip('/'):
        raise ValueError('Agent dang luu may chu khac voi may chu da chon. '
                         'Kiem tra Thong tin ho tro trong Agent; khong ghi de dang ky hoac tao token moi.')
    path = 'private/agent/v1/devices/' + urllib.parse.quote(state['deviceId'], safe='')
    try:
        previous = client.request(path + '/state') or {}
        baseline = previous.get('updatedAt') or 0
        client.request(path + '/sync', b'{}')
    except (ValueError, urllib.error.HTTPError) as error:
        raise ValueError('Agent con ID dang ky cu nhung khong xac minh duoc tren may chu da chon. '
                         'Kiem tra tai khoan, quyen va thiet bi tren web. Chua tao token hoac xoa du lieu.') from error
    print('Agent con dang ky cu. Dang xac minh ket noi moi sau khi khoi phuc Device Owner...', flush=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot = client.request(path + '/state') or {}
        if (snapshot.get('updatedAt') or 0) > baseline and device.is_owner():
            print('Khoi phuc quan ly thanh cong: Device Owner hop le va server vua nhan check-in moi. '
                  'Giu nguyen thiet bi hien co; khong can token dang ky khac.', flush=True)
            return
        time.sleep(2)
    raise ValueError('Agent con ID dang ky cu nhung chua check-in duoc sau khi khoi phuc quyen quan ly. '
                     'Kiem tra mang va trang thai ket noi trong Agent. Neu can dang ky moi hoan toan, '
                     'bo quyen quan ly roi xac nhan Go cai dat tren Android truoc khi chay lai. '
                     'Tool khong tu xoa du lieu hoac tao token thua.')


def select_device(requested_serial):
    adb = os.environ.get('ADB') or shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
    output = subprocess.run([adb, 'devices'], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True, timeout=30).stdout
    devices = [line.split()[0] for line in output.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
    if not devices:
        raise ValueError('Khong co thiet bi ADB da authorize. Bat USB debugging va xac nhan may tinh.')
    serial = requested_serial
    if serial is None:
        for number, value in enumerate(devices, 1):
            print(f'  {number}. {value}')
        choice = ask('Chon thiet bi', '1')
        if not choice.isdigit() or not 1 <= int(choice) <= len(devices):
            raise ValueError('Lua chon thiet bi khong hop le.')
        serial = devices[int(choice) - 1]
    if serial not in devices:
        raise ValueError('Thiet bi da chon chua san sang.')
    return Device(adb, serial)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--permissions-only', action='store_true', help='Chi cap Usage Access va Chuong bao/loi nhac; khong cai APK hoac enroll')
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
            raise ValueError('Khong tim thay --env-file.')
        def setting(key, default=''):
            return os.environ.get(key, settings.get(key, default))
        base = validate_url(ask('URL MDMesh', args.base_url or setting('MDM_BASE_URL', 'https://mdm.hatbuinho.me')))
        device = select_device(args.serial)
        serial = device.serial
        username = setting('MDM_ADMIN_USERNAME') or ask('Tai khoan MDMesh', 'admin')
        password = setting('MDM_ADMIN_PASSWORD') or getpass.getpass(f'Mat khau MDMesh ({username}): ')
        print('Dang dang nhap va tim APK moi nhat da publish tren server...', flush=True)
        client = release.Client(base)
        login(client, username, password)
        password = None
        published = newest_published(client)
        print(f"APK server: {published.get('versionName', '')} (versionCode {published['versionCode']})"
              f"\nThiet bi: {serial}\nServer: {base}")
        if ask('Tai APK server, cai va enroll thiet bi nay? (y/n)', 'y').lower() not in ('y', 'yes'):
            print('Da huy.'); return 0
        download_directory = tempfile.TemporaryDirectory(prefix='mdmesh-enroll-')
        print('Dang tai APK va kiem tra SHA256...', flush=True)
        apk = download_apk(published, Path(download_directory.name) / 'mdmesh-published.apk')
        installed = device.run('install', '-r', str(apk))
        if 'Success' not in installed:
            raise ValueError('ADB chua xac nhan cai APK thanh cong.')
        if not device.is_owner():
            print('Dang thiet lap Device Owner...', flush=True)
            device.shell('dpm', 'set-device-owner', ADMIN)
        if not device.is_owner():
            raise ValueError('Chua co Device Owner. Kiem tra tai khoan/owner cu. Script khong xoa du lieu.')
        device.grant_special_access()
        device.shell('am', 'start', '-W', '-n', PACKAGE + '/com.mdmesh.agent.MainActivity',
                     '--ei', 'agentDestination', '0')
        state = device.enrollment_state()
        if state['kind'] == 'existing':
            verify_existing_enrollment(device, client, state, base)
            return 0
        # Inspection may have scrolled the form offscreen; restart at the top before filling.
        device.scroll(upward=True)
        device.fill('enrollment_server', base)
        # Verify the form is available BEFORE minting the token.
        device.find(lambda n: n.get('resource-id') == PACKAGE + ':id/enrollment_token')
        print('Dang tao token moi...', flush=True)
        token = mint_token(client)
        device.fill('enrollment_token', token)
        token = None
        button = device.find(lambda n: n.get('class') == 'android.widget.Button' and n.get('text') == '\u0110\u0103ng k\xfd thi\u1ebft b\u1ecb')
        device.tap(button)
        print('Da gui dang ky. Dang cho Agent xac nhan...', flush=True)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            device.scroll(upward=True)
            texts = [n.get('text', '') for n in device.nodes()]
            if any('Thi\u1ebft b\u1ecb \u0111\xe3 \u0111\u01b0\u1ee3c qu\u1ea3n l\xfd' in t for t in texts) and any('K\u1ebft n\u1ed1i th\xe0nh c\xf4ng l\xfac' in t for t in texts):
                print('Enroll thanh cong: Agent xac nhan thiet bi da duoc quan ly va ket noi server.')
                return 0
            time.sleep(2)
        raise ValueError('Het thoi gian cho xac nhan. Kiem tra device ID/trang thai trong Agent; khong tu chay lai de tranh tao token thua.')
    except (OSError, ValueError, subprocess.SubprocessError, ET.ParseError, urllib.error.URLError) as error:
        if isinstance(error, urllib.error.HTTPError):
            message = f'API tra HTTP {error.code}; kiem tra URL, tai khoan va quyen edit_devices.'
        elif isinstance(error, (subprocess.SubprocessError, urllib.error.URLError)):
            message = 'Khong ket noi duoc ADB/API; kiem tra thiet bi va server.'
        else:
            message = str(error)
        print(f'Loi: {message}', file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print('\nDa huy.', file=sys.stderr)
        return 130
    finally:
        if download_directory is not None:
            download_directory.cleanup()


if __name__ == '__main__':
    raise SystemExit(main())
