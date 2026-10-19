"""Fail closed if any installed dependency bytes differ from the recorded lock."""
import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class DependencyLockTest(unittest.TestCase):
    def test_sqlite_runtime_is_exactly_locked_and_licensed(self):
        lock = json.loads((ROOT / 'dependency-lock.json').read_text())
        coordinates = [x for x in lock['active_coordinates'] if x['group'] == 'org.xerial']
        self.assertEqual([{'group':'org.xerial','artifact':'sqlite-jdbc','version':'3.53.4.0','scope':'compile'}], coordinates)
        self.assertTrue(any('sqlite-jdbc-3.53.4.0' in x and 'LICENSE' in x for x in lock['notice_files']))

    def test_sqlite_source_notice_is_attributed_and_checksum_matches_registry(self):
        lock = json.loads((ROOT / 'dependency-lock.json').read_text())
        notice = next((x for x in lock.get('source_notices', []) if x['path'].endswith('sqlite-jdbc-3.53.4.0-NOTICE')), None)
        self.assertIsNotNone(notice)
        self.assertEqual('https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/NOTICE', notice['url'])
        self.assertEqual(hashlib.sha256((ROOT / notice['path']).read_bytes()).hexdigest(), notice['sha256'])
        artifact = next(x for x in lock['maven_artifacts'] if x['path'].endswith('sqlite-jdbc-3.53.4.0.jar'))
        self.assertEqual('bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb', artifact['sha256'])

    @unittest.skipUnless((ROOT / 'tooling/m2').is_dir() and (ROOT / 'tooling/python-wheels').is_dir(),
                         'acquisition-cache inventory requires the optional local tooling caches')
    def test_exact_lock_exists_and_all_recorded_artifact_bytes_match(self):
        path = ROOT / 'dependency-lock.json'
        self.assertTrue(path.is_file(), 'dependency lock must exist before the gate can pass')
        lock = json.loads(path.read_text())
        self.assertGreater(len(lock['maven_artifacts']), 6)
        actual_paths = {p.relative_to(ROOT/'tooling/m2').as_posix() for p in (ROOT/'tooling/m2').rglob('*') if p.suffix in ('.jar', '.pom')}
        self.assertEqual(actual_paths, {x['path'] for x in lock['maven_artifacts']}, 'unrecorded Maven artifact inventory')
        for artifact in lock['maven_artifacts']:
            self.assertTrue(artifact['url'].startswith('https://repo.maven.apache.org/maven2/'))
            data = (ROOT / 'tooling' / 'm2' / artifact['path']).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), artifact['sha256'], artifact['path'])
        for artifact in lock['python_artifacts']:
            data = (ROOT / 'tooling' / 'python-wheels' / artifact['file']).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), artifact['sha256'], artifact['file'])

if __name__ == '__main__': unittest.main()
