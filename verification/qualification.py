#!/usr/bin/env python3
"""Generate deterministic synthetic WARC inputs and measure the packaged CLI.

Synthetic inputs are owned test data, not captured websites. Default cases are a
128 MiB concrete payload and 100,000 alternating provider/revisit records. Runs
record per-process Linux wait4 RSS, elapsed time, sampled scratch bytes, input hashes
and exact environment versions. The generator does not execute the CLI.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import tempfile
import time

PROFILE = 'http://netpreserve.org/warc/1.1/revisit/identical-payload-digest'
PAYLOAD = b'RevisitLedger synthetic payload\n'


def digest_file(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def header(kind, ident, size, payload_digest, refers=None):
    fields = ['WARC/1.1', 'WARC-Type: ' + kind, 'WARC-Record-ID: <urn:uuid:00000000-0000-4000-8000-' + f'{ident:012d}' + '>',
              'WARC-Date: 2026-01-01T00:00:00Z', 'WARC-Target-URI: https://example.invalid/' + str(ident),
              'Content-Type: application/octet-stream', 'Content-Length: ' + str(size),
              'WARC-Payload-Digest: sha1:' + base64.b32encode(bytes.fromhex(payload_digest)).decode('ascii')]
    if refers is not None:
        fields += ['WARC-Profile: ' + PROFILE, 'WARC-Refers-To: <urn:uuid:00000000-0000-4000-8000-' + f'{refers:012d}' + '>']
    return ('\r\n'.join(fields) + '\r\n\r\n').encode('ascii')


def generate(directory, payload_bytes=128 << 20, record_count=100_000):
    if payload_bytes < 1 or record_count < 2 or record_count % 2:
        raise ValueError('payload size must be positive; record count must be positive and even')
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise FileExistsError('qualification directory must be empty')
    chunk = b'R' * (1 << 20)
    remaining = payload_bytes
    payload_digest = hashlib.sha1()
    while remaining:
        block = chunk[:min(remaining, len(chunk))]
        payload_digest.update(block)
        remaining -= len(block)
    with (directory / 'payload.warc').open('xb') as output:
        output.write(header('resource', 1, payload_bytes, payload_digest.hexdigest()))
        remaining = payload_bytes
        while remaining:
            block = chunk[:min(remaining, len(chunk))]
            output.write(block)
            remaining -= len(block)
        output.write(b'\r\n\r\n')
    digest = hashlib.sha1(PAYLOAD).hexdigest()
    with (directory / 'records.warc').open('xb') as output:
        for ident in range(1, record_count + 1, 2):
            output.write(header('resource', ident, len(PAYLOAD), digest))
            output.write(PAYLOAD + b'\r\n\r\n')
            output.write(header('revisit', ident + 1, 0, digest, ident))
            output.write(b'\r\n\r\n')
    cases = []
    for name, count, length in [('payload', 1, payload_bytes), ('records', record_count, len(PAYLOAD))]:
        filename = name + '.warc'
        path = directory / filename
        (directory/(name+'.txt')).write_text(filename+'\n')
        cases.append({'name': name, 'input': filename, 'filesList': name+'.txt',
                      'recordCount': count, 'payloadBytes': length,
                      'inputBytes': path.stat().st_size, 'inputSha256': digest_file(path)})
    manifest = {'schemaVersion': 1, 'description': 'Deterministic synthetic conformance data; no captured content', 'cases': cases}
    (directory/'inputs.json').write_text(json.dumps(manifest, indent=2, sort_keys=True)+'\n')
    return manifest


def scratch_size(path):
    total = 0
    for item in Path(path).rglob('*'):
        try:
            if item.is_file():
                total += item.stat().st_size
        except FileNotFoundError:
            pass
    return total


def run_measured(command, cwd, scratch, stdout_path, stderr_path):
    """Use Linux wait4 rusage, not an accumulated parent/previous-child RSS."""
    if platform.system() != 'Linux' or not hasattr(os, 'wait4'):
        raise ValueError('qualification RSS measurement currently requires Linux wait4')
    start = time.monotonic()
    with Path(stdout_path).open('wb') as stdout, Path(stderr_path).open('wb') as stderr:
        child = os.fork()
        if child == 0:
            try:
                os.chdir(cwd)
                os.dup2(stdout.fileno(), 1)
                os.dup2(stderr.fileno(), 2)
                os.execv(command[0], command)
            except BaseException:
                os._exit(127)
        peak = 0
        try:
            while True:
                peak = max(peak, scratch_size(scratch))
                waited, status, usage = os.wait4(child, os.WNOHANG)
                if waited:
                    break
                time.sleep(0.05)
        except BaseException:
            os.kill(child, signal.SIGTERM)
            os.wait4(child, 0)
            raise
    return {'exitCode': os.waitstatus_to_exitcode(status),
            'elapsedSeconds': round(time.monotonic()-start, 3),
            'maxRssKiB': usage.ru_maxrss, 'maxObservedScratchBytes': peak}


def runtime_identity(launcher):
    launcher = Path(launcher)
    root = launcher.parent.parent
    dependencies = json.loads((root/'runtime-dependencies.json').read_text())['dependencies']
    return {'applicationJarSha256': digest_file(root/'lib/revisitledger.jar'),
            'launcherSha256': digest_file(launcher), 'dependencies': dependencies}


def run(launcher, directory, output):
    launcher, directory, output = map(lambda p: Path(p).resolve(), (launcher, directory, output))
    if not launcher.is_file():
        raise ValueError('packaged launcher missing')
    manifest = json.loads((directory/'inputs.json').read_text())
    java = Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
    version = subprocess.run([str(java), '-version'], text=True, capture_output=True, check=False)
    result = {'schemaVersion': 1, 'runtime': runtime_identity(launcher), 'platform': platform.platform(), 'machine': platform.machine(),
              'javaVersion': (version.stdout+version.stderr).strip(), 'heapLimitBytes': 128 << 20,
              'pythonVersion': platform.python_version(), 'rssMeasurement': 'Linux wait4 per-child ru_maxrss in KiB',
              'scratchSamplingSeconds': 0.05,
              'scratchMeasurement': 'maximum observed file bytes sampled every 50 ms, not exact peak or allocated blocks',
              'cases': []}
    for case in manifest['cases']:
        initial = digest_file(directory/case['input'])
        if initial != case['inputSha256']:
            raise ValueError('qualification input changed before run')
        with tempfile.TemporaryDirectory(prefix='revisitledger-qualification-') as temporary:
            work = Path(temporary)
            scratch = work/'scratch'
            scratch.mkdir()
            report = directory/(case['name']+'-report.json')
            command = [str(launcher), 'audit', '--root', str(directory), '--files', str(directory/case['filesList']),
                       '--json', str(report), '--scratch', str(scratch)]
            measured = run_measured(command, work, scratch,
                                    directory/(case['name']+'-stdout.txt'), directory/(case['name']+'-stderr.txt'))
            unchanged = initial == digest_file(directory/case['input'])
            metrics = dict(case, **measured, scratchBytesAfter=scratch_size(scratch),
                           inputUnchanged=unchanged, reportBytes=report.stat().st_size if report.exists() else None)
            if report.exists():
                # This verifier runs outside the measured Java process. Full JSON load
                # is deliberate here; it is not part of the bounded-heap product.
                document = json.loads(report.read_text())
                metrics.update(scanComplete=document['scanComplete'], complete=document['complete'],
                               observedRecordCount=sum(item['recordCount'] for item in document['files']))
            metrics['passed'] = (metrics['exitCode'] == 0 and unchanged and metrics['scratchBytesAfter'] == 0
                                 and metrics.get('scanComplete') is True and metrics.get('complete') is True
                                 and metrics.get('observedRecordCount') == case['recordCount'])
            result['cases'].append(metrics)
            output.write_text(json.dumps(result, indent=2, sort_keys=True)+'\n')
            print(json.dumps(metrics, sort_keys=True), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    generate_parser = sub.add_parser('generate')
    generate_parser.add_argument('--directory', type=Path, required=True)
    generate_parser.add_argument('--payload-bytes', type=int, default=128 << 20)
    generate_parser.add_argument('--record-count', type=int, default=100_000)
    run_parser = sub.add_parser('run')
    run_parser.add_argument('--launcher', type=Path, required=True)
    run_parser.add_argument('--directory', type=Path, required=True)
    run_parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'generate':
        print(json.dumps(generate(args.directory, args.payload_bytes, args.record_count), indent=2, sort_keys=True))
    else:
        result = run(args.launcher, args.directory, args.output)
        raise SystemExit(0 if all(case['passed'] for case in result['cases']) else 1)


if __name__ == '__main__':
    main()
