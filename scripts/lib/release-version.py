#!/usr/bin/env python3
"""Choose the next Android version code from reference and local release history."""
import re
import sys
from pathlib import Path

LIMIT = 2_100_000_000


def select_version(baseline, override, roots):
    latest = baseline
    for root in roots:
        root = Path(root)
        if not root.exists():
            continue
        for entry in root.iterdir():
            if entry.is_dir() and re.fullmatch(r'[1-9][0-9]{0,9}', entry.name):
                value = int(entry.name)
                if value <= LIMIT:
                    latest = max(latest, value)
    if override and override != 'auto':
        if not re.fullmatch(r'[1-9][0-9]{0,9}', override):
            raise ValueError('versionCode must be a positive integer without leading zeros.')
        selected = int(override)
    else:
        selected = latest + 1
    if not latest < selected <= LIMIT:
        raise ValueError(f'versionCode must be greater than {latest} and at most {LIMIT}.')
    return selected


def main():
    try:
        reference, override, gradle, *roots = sys.argv[1:]
        source = Path(gradle).read_text()
        default = re.search(r'^\s*versionCode\s*=.*?\?:\s*([0-9]+)', source, re.MULTILINE)
        baseline = max(int(reference), int(default[1]) if default else 0)
        print(select_version(baseline, override, roots))
    except (OSError, ValueError) as error:
        print(f'Cannot select release version: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
