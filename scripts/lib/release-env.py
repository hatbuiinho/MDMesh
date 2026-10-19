#!/usr/bin/env python3
"""Read only release settings from dotenv as literal values, never as shell code."""
import re
import sys
from pathlib import Path

ALLOWED = {
    'MDM_RELEASE_STORE_FILE', 'MDM_RELEASE_STORE_PASSWORD', 'MDM_RELEASE_KEY_ALIAS',
    'MDM_RELEASE_KEY_PASSWORD', 'MDM_REFERENCE_APK', 'MDM_RELEASE_VERSION_CODE',
    'MDM_RELEASE_VERSION_NAME', 'MDM_BASE_URL', 'MDM_APK_URL', 'MDM_RELEASE_OUTPUT_ROOT',
}


def read_settings(path):
    settings = {}
    for number, line in enumerate(Path(path).read_text(encoding='utf-8-sig').splitlines(), 1):
        match = re.match(r'^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$', line)
        if not match or match[1] not in ALLOWED:
            continue
        key, raw = match[1], match[2].strip()
        if raw.startswith(('"', "'")):
            # Quotes delimit literal values. No shell expansion, command substitution or escapes.
            end = raw.find(raw[0], 1)
            if end < 0 or (raw[end + 1:].strip() and not raw[end + 1:].lstrip().startswith('#')):
                raise ValueError(f'Invalid quoted release setting at line {number}')
            value = raw[1:end]
        else:
            value = re.split(r'\s+#', raw, maxsplit=1)[0].rstrip()
        if '\0' in value:
            raise ValueError(f'Invalid release setting at line {number}')
        settings[key] = value
    return settings


def main():
    try:
        settings = read_settings(sys.argv[1])
    except (OSError, ValueError):
        # Do not include source lines, values, or filesystem exception text in secret-related logs.
        print('Cannot read release settings: check the env file and quoting.', file=sys.stderr)
        return 1
    for key, value in settings.items():
        sys.stdout.buffer.write(key.encode() + b'\0' + value.encode() + b'\0')
    sys.stdout.buffer.write(b'__RELEASE_ENV_DONE__\0\0')
    return 0


if __name__ == '__main__':
    sys.exit(main())
