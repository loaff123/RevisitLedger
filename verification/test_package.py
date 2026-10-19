"""Tests for clean, byte-accounted distribution assembly."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class PackageBuilderTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / 'scripts/package.py'
        self.assertTrue(path.is_file(), 'the clean distribution builder is missing')
        spec = importlib.util.spec_from_file_location('package_builder', path)
        self.builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.builder)

    def test_source_allowlist_excludes_private_build_and_gate_material(self):
        paths = [p.relative_to(ROOT).as_posix() for p in self.builder.source_files(ROOT)]
        self.assertIn('src/main/java/revisitledger/model/RecordKey.java', paths)
        self.assertIn('verification/test_dependency_lock.py', paths)
        self.assertEqual({p for p in paths if p.startswith('docs/')},
                         {'docs/FORMAT.md', 'docs/LIMITATIONS.md', 'docs/VALIDATION.md', 'docs/COMPETITORS.md'})
        self.assertTrue(all(not p.startswith(('evidence/', 'tooling/', 'target/')) for p in paths))
        self.assertTrue(all('__pycache__' not in p and not p.endswith('.pyc') for p in paths))

    def test_manifest_accounts_for_every_source_byte(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'README.md').write_bytes(b'hello\n')
            manifest = self.builder.source_manifest(root, [root / 'README.md'])
            self.assertEqual(manifest['files'], [{'path': 'README.md', 'bytes': 6,
                'sha256': '5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03'}])

    def test_zip_is_deterministic_and_preserves_executable_launcher(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entries = {'README.md': b'hello\n', 'bin/revisit-ledger': b'#!/bin/sh\n'}
            self.builder.write_zip(root / 'one.zip', entries)
            self.builder.write_zip(root / 'two.zip', dict(reversed(list(entries.items()))))
            self.assertEqual((root / 'one.zip').read_bytes(), (root / 'two.zip').read_bytes())
            with zipfile.ZipFile(root / 'one.zip') as archive:
                self.assertEqual(archive.namelist(), ['revisitledger/README.md', 'revisitledger/bin/revisit-ledger'])
                self.assertEqual((archive.getinfo('revisitledger/bin/revisit-ledger').external_attr >> 16) & 0o111, 0o111)

    def test_unsafe_zip_member_and_symlink_source_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                self.builder.write_zip(root / 'bad.zip', {'../private.txt': b'bad'})
            (root / 'README.md').symlink_to('/etc/passwd')
            with self.assertRaises(ValueError):
                self.builder.source_files(root)


class QualificationGeneratorTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / 'verification/qualification.py'
        self.assertTrue(path.is_file(), 'the bounded qualification generator is missing')
        spec = importlib.util.spec_from_file_location('qualification', path)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_small_generation_is_exact_repeatable_and_has_requested_record_counts(self):
        import known_bytes
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = self.module.generate(root / 'first', 257, 6)
            second = self.module.generate(root / 'second', 257, 6)
            self.assertEqual(first, second)
            self.assertEqual(len(known_bytes.inspect_bytes((root/'first/payload.warc').read_bytes(), 'payload.warc')), 1)
            rows = known_bytes.inspect_bytes((root/'first/records.warc').read_bytes(), 'records.warc')
            self.assertEqual(len(rows), 6)
            self.assertEqual([row['record_type'] for row in rows], ['resource','revisit'] * 3)
            self.assertEqual(first['cases'][0]['payloadBytes'], 257)
            self.assertEqual(first['cases'][1]['recordCount'], 6)

    def test_qualification_identifies_exact_application_bytes(self):
        self.assertTrue(hasattr(self.module, 'runtime_identity'), 'runtime identity metadata is missing')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'bin').mkdir()
            (root/'lib').mkdir()
            (root/'bin/revisit-ledger').write_bytes(b'launcher')
            (root/'lib/revisitledger.jar').write_bytes(b'application')
            (root/'runtime-dependencies.json').write_text('{"schemaVersion":1,"dependencies":[]}')
            identity = self.module.runtime_identity(root/'bin/revisit-ledger')
            import hashlib
            self.assertEqual(identity['applicationJarSha256'], hashlib.sha256(b'application').hexdigest())
            self.assertEqual(identity['launcherSha256'], hashlib.sha256(b'launcher').hexdigest())
            self.assertEqual(identity['dependencies'], [])

    def test_measured_process_captures_exit_rss_elapsed_and_scratch(self):
        import sys
        self.assertTrue(hasattr(self.module, 'run_measured'), 'per-process wait4 measurement is missing')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scratch = root/'scratch'
            scratch.mkdir()
            program = "import pathlib,time; x=bytearray(32*1024*1024); pathlib.Path('scratch/item').write_bytes(b'x'*10000); print('measured'); time.sleep(0.12); raise SystemExit(7)"
            metrics = self.module.run_measured([sys.executable, '-c', program], root, scratch,
                                               root/'stdout.txt', root/'stderr.txt')
            self.assertEqual(metrics['exitCode'], 7)
            self.assertGreater(metrics['maxRssKiB'], 32*1024)
            self.assertGreaterEqual(metrics['elapsedSeconds'], 0.1)
            self.assertEqual(metrics['maxObservedScratchBytes'], 10000)
            self.assertEqual((root/'stdout.txt').read_text().strip(), 'measured')

    def test_generator_rejects_bad_bounds_and_existing_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for payload, count in [(0, 2), (1, 0), (1, 3), (-1, 2)]:
                with self.assertRaises(ValueError):
                    self.module.generate(root / 'bad', payload, count)
            self.module.generate(root / 'good', 1, 2)
            with self.assertRaises(FileExistsError):
                self.module.generate(root / 'good', 1, 2)


if __name__ == '__main__':
    unittest.main()
