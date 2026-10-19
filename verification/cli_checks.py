#!/usr/bin/env python3
"""End-to-end offline CLI contract checks against an extracted distribution."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def check(distribution):
    distribution = Path(distribution).resolve()
    launcher = distribution / 'bin/revisit-ledger'
    if not launcher.is_file():
        raise AssertionError('packaged CLI launcher is missing')
    fixtures = distribution / 'tests/fixtures'
    with tempfile.TemporaryDirectory(prefix='revisitledger-cli-') as name:
        work = Path(name)
        corpus = work / 'corpus'
        shutil.copytree(fixtures / 'derived/whole-file', corpus)
        before = {p.name: sha(p) for p in corpus.iterdir()}
        all_files = work / 'all.txt'
        all_files.write_text('C.warc\nA.warc\nB.warc\n', encoding='utf-8')
        seeds = work / 'seeds.txt'
        seeds.write_text('A.warc\n', encoding='utf-8')
        scratch = work / 'scratch'
        scratch.mkdir()
        calls = []

        def run(args, expected):
            result = subprocess.run([str(launcher), *map(str, args)], cwd=work,
                                    capture_output=True, text=True, timeout=180)
            assert result.returncode == expected, (args[0], result.returncode, result.stdout, result.stderr)
            assert str(work) not in result.stdout + result.stderr
            assert not list(scratch.iterdir()), 'owned scratch was not cleaned'
            calls.append({'command': args[0], 'exit': result.returncode})
            return result

        def audit(files, output, expected=0, extra=()):
            run(['audit', '--root', corpus, '--files', files, '--json', output,
                 '--scratch', scratch, *extra], expected)
            return json.loads(output.read_text())

        run(['--help'], 0)
        run(['--version'], 0)
        first = work / 'audit.json'
        audit_doc = audit(all_files, first)
        assert all(audit_doc[k] for k in ('scanComplete','resolutionComplete','closureComplete','inputsVerified','complete'))
        all_files.write_text('A.warc\nB.warc\nC.warc\n')
        second = work / 'audit-again.json'
        audit(all_files, second)
        assert first.read_bytes() == second.read_bytes(), 'file-order permutation changed report'
        plan_file = work / 'plan.json'
        run(['plan','--root',corpus,'--files',all_files,'--seeds',seeds,'--json',plan_file,'--scratch',scratch], 0)
        plan = json.loads(plan_file.read_text())
        assert plan['complete']
        included = plan['includedFiles']
        assert included == ['A.warc','B.warc','C.warc'], included
        assert plan['addedFiles'] == ['B.warc','C.warc']
        assert len(plan['witnessEdges']) == 2
        subset = work / 'subset.txt'
        subset.write_text(''.join(path+'\n' for path in included))
        assert audit(subset,work/'subset.json')['complete']
        explained = run(['explain','--report',plan_file,'--record','A.warc#1'],0)
        assert 'A.warc' in explained.stdout
        missing = work / 'missing.txt'
        missing.write_text('A.warc\nB.warc\n')
        blocked = audit(missing, work/'missing.json', 1)
        assert blocked['scanComplete'] and not blocked['complete']
        limited = audit(all_files,work/'limited.json',2,('--max-records','1'))
        assert not limited['scanComplete'] and not limited['complete']
        untouched = first.read_bytes()
        run(['audit','--root',corpus,'--files',all_files,'--json',first,'--scratch',scratch],2)
        assert first.read_bytes() == untouched
        alias = work / 'alias.json'
        alias.hardlink_to(corpus/'A.warc')
        run(['audit','--root',corpus,'--files',all_files,'--json',alias,'--overwrite','--scratch',scratch],2)
        assert {p.name: sha(p) for p in corpus.iterdir()} == before, 'archive bytes changed'
        for p in (first,second,plan_file,work/'missing.json',work/'limited.json'):
            text=p.read_text()
            assert str(work) not in text
            assert 'some\\ntext' not in text
            assert not any(ord(c)<32 and c not in '\n\r\t' for c in text)
        # SIGTERM exercises Main's cooperative shutdown rather than a test-only thread hook.
        interrupt_input = corpus / 'interrupt.warc'
        unit = (fixtures/'upstream-warcio/RESOURCE_RECORD.warc').read_bytes()
        with interrupt_input.open('xb') as target:
            for _ in range(50000):
                target.write(unit)
        interrupt_hash = sha(interrupt_input)
        interrupt_list = work/'interrupt.txt'
        interrupt_list.write_text('interrupt.warc\n')
        interrupt_output = work/'interrupt.json'
        interrupt_output.write_text('previous report sentinel')
        child = subprocess.Popen([str(launcher),'audit','--root',str(corpus),
            '--files',str(interrupt_list),'--json',str(interrupt_output),'--overwrite',
            '--scratch',str(scratch)],cwd=work,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            deadline=time.monotonic()+10
            while child.poll() is None and not list(scratch.iterdir()) and time.monotonic()<deadline:
                time.sleep(.01)
            assert child.poll() is None and list(scratch.iterdir()), 'process did not enter active indexing'
            time.sleep(.1)
            child.terminate()
            stdout,stderr=child.communicate(timeout=10)
            assert child.returncode != 0
            assert interrupt_output.read_text() == 'previous report sentinel'
            assert not list(scratch.iterdir()), 'interrupted process left owned scratch'
            assert not list(work.glob('.revisitledger-report-*')), 'interrupted process left staged output'
            assert sha(interrupt_input) == interrupt_hash
            assert str(work) not in stdout+stderr
            calls.append({'command':'SIGTERM during audit','exit':child.returncode})
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()
        return {'checks':'passed','calls':calls,'inputHashesUnchanged':True,
                'byteIdenticalPermutedReports':True,'outsideCheckout':True}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--distribution',required=True,type=Path)
    args=parser.parse_args()
    print(json.dumps(check(args.distribution),sort_keys=True,indent=2))

if __name__=='__main__':
    main()
