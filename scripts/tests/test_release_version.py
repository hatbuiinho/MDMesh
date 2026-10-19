#!/usr/bin/env python3
import runpy
import tempfile
import unittest
from pathlib import Path

select_version = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'lib/release-version.py'))['select_version']


class ReleaseVersionTest(unittest.TestCase):
    def test_numeric_history_across_roots_and_reserved_directories(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for path in ('docker/999', 'docker/1000', 'archive/1022', 'archive/.build-9999'):
                (root / path).mkdir(parents=True)
            self.assertEqual(select_version(1001, '', [root / 'docker', root / 'archive']), 1023)
            self.assertEqual(select_version(1100, '', [root / 'docker', root / 'archive']), 1101)

    def test_missing_history_uses_baseline(self):
        self.assertEqual(select_version(16, '', []), 17)
        self.assertEqual(select_version(16, 'auto', []), 17)

    def test_explicit_override_must_exceed_history(self):
        self.assertEqual(select_version(1022, '1030', []), 1030)
        for code in ('1022', '0999', 'invalid', '2100000001'):
            with self.assertRaises(ValueError):
                select_version(1022, code, [])

    def test_exhausted_range_stops(self):
        with self.assertRaises(ValueError):
            select_version(2100000000, '', [])


if __name__ == '__main__':
    unittest.main()
