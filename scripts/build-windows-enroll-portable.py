#!/usr/bin/env python3
"""Build a self-contained Windows enrollment folder and ZIP using pinned tools."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / 'scripts/windows-portable'


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_tools(package, cache, destination):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / ('python.zip' if package['directory'] == 'python' else 'adb.zip')
    if not archive.is_file() or checksum(archive) != package['sha256']:
        print(f"Downloading {package['name']}...", flush=True)
        with tempfile.NamedTemporaryFile(dir=cache, suffix='.zip', delete=False) as tmp:
            partial = Path(tmp.name)
            try:
                with urllib.request.urlopen(package['url'], timeout=180) as response:
                    shutil.copyfileobj(response, tmp)
                tmp.close()
                if checksum(partial) != package['sha256']:
                    raise ValueError('Official tools archive checksum mismatch.')
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
    with zipfile.ZipFile(archive) as source:
        for relative, expected in package['files'].items():
            safe = PurePosixPath(relative)
            if safe.is_absolute() or '..' in safe.parts or '\\' in relative:
                raise ValueError('Unsafe tools archive path.')
            member = (package['archiveDirectory'] + '/' if package['archiveDirectory'] else '') + relative
            payload = source.read(member)
            if hashlib.sha256(payload).hexdigest() != expected:
                raise ValueError('Extracted tool file checksum mismatch.')
            target = destination / 'tools' / package['directory'] / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)


def enrollment_env(source, include_credentials):
    example = (TEMPLATE / '.env.example').read_text()
    if source is None:
        return example
    # Validate literal dotenv quoting with exactly the same parser as enrollment.
    spec = importlib.util.spec_from_file_location('enroll_portable', ROOT / 'scripts/enroll-device-adb.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.read_env(source)
    keys = {'MDM_BASE_URL', 'MDM_ADMIN_USERNAME'}
    if include_credentials:
        keys.add('MDM_ADMIN_PASSWORD')
    # Preserve source quoting, including password special characters, without evaluation.
    lines = []
    present = set()
    for line in source.read_text(encoding='utf-8-sig').splitlines():
        match = re.match(r'^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=', line)
        if match and match[1] in keys:
            lines.append(line)
            present.add(match[1])
    for line in example.splitlines():
        match = re.match(r'^([A-Za-z_][A-Za-z0-9_]*)=', line)
        if match and match[1] not in present:
            lines.append(line)
    return '\n'.join(lines) + '\n'


def build(output, cache, env_file=None, include_credentials=False, without_tools=False):
    output = output.resolve()
    bundle = output / 'MDMesh-Enroll'
    archive = output / 'MDMesh-Enroll-Windows.zip'
    if bundle.exists() or archive.exists():
        raise ValueError('Output already exists. Choose another --output directory.')
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((TEMPLATE / 'tools-manifest.json').read_text())
    with tempfile.TemporaryDirectory(prefix='mdmesh-package-', dir=output) as work:
        stage = Path(work) / 'MDMesh-Enroll'
        shutil.copytree(TEMPLATE, stage)
        scripts = stage / 'app/scripts'
        scripts.mkdir(parents=True)
        for name in ('enroll-device-adb.py', 'publish-release.py'):
            shutil.copyfile(ROOT / 'scripts' / name, scripts / name)
        config = enrollment_env(env_file, include_credentials)
        (stage / '.env').write_text(config, encoding='utf-8')
        (stage / '.env').chmod(0o600)
        if not without_tools:
            for package in manifest['packages']:
                prepare_tools(package, cache, stage)
        temporary_zip = Path(work) / archive.name
        with zipfile.ZipFile(temporary_zip, 'w', zipfile.ZIP_DEFLATED) as target:
            for item in sorted(stage.rglob('*')):
                if item.is_file():
                    target.write(item, 'MDMesh-Enroll/' + item.relative_to(stage).as_posix())
        temporary_zip.chmod(0o600)
        stage.rename(bundle)
        temporary_zip.rename(archive)
    digest = checksum(archive)
    (output / 'SHA256SUMS').write_text(f'{digest}  {archive.name}\n')
    print(f'Folder: {bundle}\nZIP: {archive}\nSHA256: {digest}')
    return bundle, archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'releases/portable-windows')
    parser.add_argument('--cache', type=Path, default=ROOT / 'releases/.portable-tools-cache')
    parser.add_argument('--env-file', type=Path, help='Copy only enrollment settings from this file')
    parser.add_argument('--include-credentials', action='store_true', help='Also include MDM_ADMIN_PASSWORD from --env-file')
    parser.add_argument('--without-tools', action='store_true', help='Small bootstrap ZIP; Setup.cmd downloads pinned tools on Windows')
    args = parser.parse_args()
    if args.include_credentials and args.env_file is None:
        parser.error('--include-credentials requires --env-file')
    try:
        build(args.output, args.cache, args.env_file, args.include_credentials, args.without_tools)
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as error:
        parser.exit(1, f'Portable build failed: {error}\n')


if __name__ == '__main__':
    main()
