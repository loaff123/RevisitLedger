#!/usr/bin/env python3
"""Assemble a deterministic, checksum-accounted runnable and source distribution.

No network requests or dependency resolution occur here. Build first, and supply a
local Maven repository containing the exact locked runtime bytes.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_DOCS = ('FORMAT.md', 'LIMITATIONS.md', 'VALIDATION.md', 'COMPETITORS.md')
TOP_LEVEL = ('README.md', 'LICENSE', 'NOTICE', 'pom.xml', 'dependency-lock.json',
             'requirements-oracle.lock', '.gitignore')
POSIX_LAUNCHER = b'''#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
exec "$JAVA" -Xmx128m -cp "$ROOT/lib/*" revisitledger.Main "$@"
'''
WINDOWS_LAUNCHER = b'''@echo off\r
setlocal\r
set "ROOT=%~dp0.."\r
if defined JAVA_HOME (\r
  "%JAVA_HOME%\\bin\\java.exe" -Xmx128m -cp "%ROOT%\\lib\\*" revisitledger.Main %*\r
) else (\r
  java -Xmx128m -cp "%ROOT%\\lib\\*" revisitledger.Main %*\r
)\r
exit /b %ERRORLEVEL%\r
'''


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode('utf-8')


def source_files(root):
    root = Path(root)
    candidates = [root / name for name in TOP_LEVEL]
    candidates += [root / 'docs' / name for name in PUBLIC_DOCS]
    for name in ('src', 'tests/fixtures', 'verification', 'scripts', 'schemas',
                 'examples', 'third-party-notices', '.github/workflows'):
        directory = root / name
        if directory.is_symlink():
            raise ValueError('symlink source directory refused: ' + name)
        candidates.extend(directory.rglob('*'))
    files = []
    for path in sorted(set(candidates)):
        relative = path.relative_to(root)
        if '__pycache__' in relative.parts or path.suffix == '.pyc':
            continue
        if path.is_symlink():
            raise ValueError('symlink source refused: ' + relative.as_posix())
        if path.is_file():
            files.append(path)
    return files


def source_manifest(root, paths):
    root = Path(root)
    return {'schemaVersion': 1, 'files': [
        {'path': path.relative_to(root).as_posix(), 'bytes': len(data),
         'sha256': hashlib.sha256(data).hexdigest()}
        for path in sorted(paths) for data in [path.read_bytes()]]}


def checked_relative(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or str(path) != name or any(p in ('.', '..') for p in path.parts) or '\\' in name:
        raise ValueError('unsafe distribution member')
    return name


def write_zip(destination, entries):
    # Fixed metadata makes an identical source + built JAR produce identical ZIP bytes.
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            name = checked_relative(name)
            info = zipfile.ZipInfo('revisitledger/' + name, (2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = ((0o100755 if name == 'bin/revisit-ledger' else 0o100644) << 16)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)


def check_java17_classes(jar):
    count = 0
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith('.class') or name.startswith('META-INF/versions/'):
                continue
            raw = archive.read(name)
            if len(raw) < 8 or raw[:4] != b'\xca\xfe\xba\xbe' or struct.unpack('>H', raw[6:8])[0] > 61:
                raise ValueError('class is not Java17 bytecode compatible: ' + name)
            count += 1
    return count


def build(root, jar, repository, output):
    root, jar, repository, output = map(Path, (root, jar, repository, output))
    if not jar.is_file():
        raise ValueError('application JAR missing; run Maven package first')
    with zipfile.ZipFile(jar) as archive:
        if 'revisitledger/Main.class' not in archive.namelist():
            raise ValueError('application JAR has no revisitledger.Main entry point')
    check_java17_classes(jar)
    paths = source_files(root)
    entries = {p.relative_to(root).as_posix(): p.read_bytes() for p in paths}
    for name in ('LICENSE', 'NOTICE', 'README.md', 'docs/VALIDATION.md', 'schemas/report-v1.schema.json'):
        if name not in entries:
            raise ValueError('required distribution file missing: ' + name)
    lock = json.loads((root / 'dependency-lock.json').read_text())
    locked = {entry['path']: entry for entry in lock['maven_artifacts']}
    runtime = []
    for coordinate in lock['active_coordinates']:
        if coordinate['scope'] not in ('compile', 'runtime'):
            continue
        group, artifact, version = [coordinate[key] for key in ('group', 'artifact', 'version')]
        filename = artifact + '-' + version + '.jar'
        relative = group.replace('.', '/') + '/' + artifact + '/' + version + '/' + filename
        data = (repository / relative).read_bytes()
        expected = locked[relative]
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected['sha256'] or len(data) != expected['byte_length']:
            raise ValueError('runtime dependency checksum mismatch: ' + filename)
        check_java17_classes(repository / relative)
        entries['lib/' + filename] = data
        runtime.append({'path': 'lib/' + filename, 'coordinate': group+':'+artifact+':'+version,
                        'sha256': actual, 'bytes': len(data), 'source': expected['url']})
    entries['lib/revisitledger.jar'] = jar.read_bytes()
    entries['bin/revisit-ledger'] = POSIX_LAUNCHER
    entries['bin/revisit-ledger.cmd'] = WINDOWS_LAUNCHER
    entries['source-manifest.json'] = json_bytes(source_manifest(root, paths))
    entries['runtime-dependencies.json'] = json_bytes({'schemaVersion': 1, 'dependencies': runtime})
    entries['distribution-manifest.json'] = json_bytes({'schemaVersion': 1, 'files': [
        {'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        for name, data in sorted(entries.items())]})
    output.parent.mkdir(parents=True, exist_ok=True)
    write_zip(output, entries)
    return {'file': output.name, 'bytes': output.stat().st_size,
            'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
            'sourceFileCount': len(paths), 'runtimeDependencyCount': len(runtime)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, help='built application JAR (default: the sole target/*.jar)')
    parser.add_argument('--repository', type=Path, default=None, help='local Maven repository, no downloads')
    parser.add_argument('--output', type=Path, default=ROOT/'dist/revisitledger-0.1.0.zip')
    args = parser.parse_args()
    if args.jar is None:
        candidates = [p for p in (ROOT/'target').glob('*.jar') if not p.name.endswith(('-sources.jar','-tests.jar','-javadoc.jar'))]
        if len(candidates) != 1:
            parser.error('--jar is required unless exactly one built application JAR exists')
        args.jar = candidates[0]
    repository = args.repository or (ROOT/'tooling/m2' if (ROOT/'tooling/m2').is_dir() else Path.home()/'.m2/repository')
    try:
        print(json.dumps(build(ROOT, args.jar, repository, args.output), sort_keys=True))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, 'Packaging failed: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
