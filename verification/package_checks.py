#!/usr/bin/env python3
"""Smoke-test a distribution ZIP after extracting it outside the source checkout."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def extract_checked(artifact, destination):
    destination = Path(destination)
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'duplicate ZIP member'
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            assert not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename
            assert path.parts[0] == 'revisitledger'
            assert ((entry.external_attr >> 16) & 0o170000) != 0o120000, 'symlink ZIP member'
        archive.extractall(destination)
    exported = destination/'revisitledger'
    manifest = json.loads((exported/'distribution-manifest.json').read_text())
    actual = {p.relative_to(exported).as_posix() for p in exported.rglob('*') if p.is_file()}
    assert actual == {e['path'] for e in manifest['files']} | {'distribution-manifest.json'}, 'unaccounted distribution members'
    for entry in manifest['files']:
        path = exported/entry['path']
        assert path.stat().st_size == entry['bytes'] and sha(path) == entry['sha256'], 'distribution checksum mismatch'
    sources = json.loads((exported/'source-manifest.json').read_text())
    for entry in sources['files']:
        path = exported/entry['path']
        assert path.stat().st_size == entry['bytes'] and sha(path) == entry['sha256'], 'source checksum mismatch'
    assert not any(name.startswith(('tooling/', 'evidence/', 'target/')) or '__pycache__' in name or name.endswith('.pyc') for name in actual)
    assert not any(name.startswith('docs/') and ('GATE' in name or 'TASK' in name) for name in actual)
    for name in ['README.md', 'NOTICE', *('docs/'+n for n in ['FORMAT.md','LIMITATIONS.md','VALIDATION.md','COMPETITORS.md'])]:
        data = (exported/name).read_text()
        assert not re.search(r'/workspace/|/home/[^/]+/|/tmp/revisit|\bTASK[1-6]\b', data, re.I), 'private implementation context in user documentation'
    launcher = exported/'bin/revisit-ledger'
    launcher.chmod(0o755)
    return exported


def check(artifact, keep=None):
    artifact = Path(artifact).resolve()
    assert artifact.is_file(), 'the packaged distribution is missing: ' + artifact.name
    with tempfile.TemporaryDirectory(prefix='revisitledger-package-smoke-') as temporary:
        work = Path(temporary)
        exported = extract_checked(artifact, work)
        launcher = exported/'bin/revisit-ledger'
        observed = []
        def invoke(name, args, expected=0):
            process = subprocess.run([str(launcher), *args], cwd=work, capture_output=True, text=True)
            assert process.returncode == expected, name+': expected '+str(expected)+' got '+str(process.returncode)+'\n'+process.stdout+process.stderr
            observed.append({'case': name, 'exitCode': process.returncode})
            return process
        invoke('help', ['--help'])
        invoke('version', ['--version'])
        corpus = work/'corpus'
        corpus.mkdir()
        for path in (exported/'tests/fixtures/derived/whole-file').glob('*.warc'):
            shutil.copy2(path, corpus/path.name)
        shutil.copy2(exported/'tests/fixtures/upstream-warcio/RESOURCE_RECORD.warc', corpus/'tiny.warc')
        (work/'files.txt').write_text('C.warc\nA.warc\nB.warc\n')
        (work/'seeds.txt').write_text('A.warc\n')
        (work/'tiny.txt').write_text('tiny.warc\n')
        before = {p.name: sha(p) for p in corpus.iterdir()}
        common = ['--root', str(corpus), '--files', str(work/'files.txt')]
        invoke('licensed-tiny-fixture', ['audit','--root',str(corpus),'--files',str(work/'tiny.txt'),'--json',str(work/'tiny.json')])
        invoke('whole-file-audit', ['audit',*common,'--json',str(work/'audit.json')])
        invoke('whole-file-plan', ['plan',*common,'--seeds',str(work/'seeds.txt'),'--json',str(work/'plan.json')])
        plan = json.loads((work/'plan.json').read_text())
        assert plan['complete'] is True and plan['scanComplete'] is True and plan['inputsVerified'] is True
        assert plan['includedFiles'] == ['A.warc','B.warc','C.warc']
        assert plan['addedFiles'] == ['B.warc','C.warc']
        assert len(plan['witnessEdges']) >= 2
        (work/'files.txt').write_text('A.warc\nB.warc\nC.warc\n')
        invoke('deterministic-plan', ['plan',*common,'--seeds',str(work/'seeds.txt'),'--json',str(work/'plan-again.json')])
        assert (work/'plan.json').read_bytes() == (work/'plan-again.json').read_bytes(), 'file-list order changed report bytes'
        invoke('no-clobber', ['audit',*common,'--json',str(work/'plan.json')], 2)
        invoke('input-alias-refused', ['audit',*common,'--json',str(corpus/'A.warc'),'--overwrite'], 2)
        (work/'linked-corpus').symlink_to(corpus, target_is_directory=True)
        invoke('symlink-root-refused', ['audit','--root',str(work/'linked-corpus'),
               '--files',str(work/'files.txt'),'--json',str(work/'linked-root.json')], 2)
        (work/'linked-parent').symlink_to(work, target_is_directory=True)
        invoke('symlink-root-ancestor-refused', ['audit','--root',str(work/'linked-parent/corpus'),
               '--files',str(work/'files.txt'),'--json',str(work/'linked-ancestor.json')], 2)
        (work/'subset.txt').write_text('\n'.join(plan['includedFiles'])+'\n')
        invoke('planned-subset-recheck', ['audit','--root',str(corpus),'--files',str(work/'subset.txt'),'--json',str(work/'subset.json')])
        assert before == {p.name: sha(p) for p in corpus.iterdir()}, 'archive bytes changed'
        corpus.rename(work/'hidden-corpus')
        explained = invoke('saved-report-explain', ['explain','--report',str(work/'plan.json'),'--record','A.warc#1'])
        assert 'A.warc' in explained.stdout and 'B.warc' in explained.stdout
        (work/'hidden-corpus').rename(corpus)
        (work/'without-c.txt').write_text('A.warc\nB.warc\n')
        invoke('missing-provider', ['audit','--root',str(corpus),'--files',str(work/'without-c.txt'),'--json',str(work/'missing.json')], 1)
        incomplete = json.loads((work/'missing.json').read_text())
        assert incomplete['scanComplete'] is True and incomplete['complete'] is False
        (corpus/'unsupported.warc').write_bytes((corpus/'A.warc').read_bytes().replace(b'/revisit/identical-payload-digest', b'/revisit/server-not-modified'))
        (work/'unsupported.txt').write_text('unsupported.warc\nB.warc\nC.warc\n')
        invoke('unsupported-profile', ['audit','--root',str(corpus),'--files',str(work/'unsupported.txt'),'--json',str(work/'unsupported.json')], 1)
        damaged = bytearray(gzip.compress((corpus/'tiny.warc').read_bytes(), mtime=0))
        damaged[-8] ^= 1
        (corpus/'bad.warc.gz').write_bytes(damaged)
        (work/'bad.txt').write_text('bad.warc.gz\n')
        invoke('corrupt-gzip', ['audit','--root',str(corpus),'--files',str(work/'bad.txt'),'--json',str(work/'bad.json')], 2)
        bad = json.loads((work/'bad.json').read_text())
        assert bad['scanComplete'] is False and bad['complete'] is False
        for name, digest in before.items():
            assert sha(corpus/name) == digest, 'archive bytes changed'
        if keep:
            keep = Path(keep)
            if keep.exists():
                raise FileExistsError('keep directory already exists')
            shutil.copytree(exported, keep)
        return {'schemaVersion':1, 'artifactSha256':sha(artifact), 'outsideCheckout':True,
                'inputHashesUnchanged':True, 'checks':observed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, default=ROOT/'dist/revisitledger-0.1.0.zip')
    parser.add_argument('--keep', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = check(args.artifact, args.keep)
    text = json.dumps(result, indent=2, sort_keys=True)+'\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')


if __name__ == '__main__':
    main()
