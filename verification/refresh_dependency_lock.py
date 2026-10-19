"""Explicit maintainer action: record resolved official registry bytes; never run by tests."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT=Path(__file__).resolve().parents[1]
repository=ROOT/'tooling/m2'
artifacts=[]
for path in sorted(repository.rglob('*')):
    if path.suffix not in ('.pom','.jar'): continue
    relative=path.relative_to(repository).as_posix()
    data=path.read_bytes()
    artifacts.append({'path':relative,'url':'https://repo.maven.apache.org/maven2/'+relative,'byte_length':len(data),'sha256':hashlib.sha256(data).hexdigest()})
coordinates=[]
for line in (ROOT/'evidence/dependency-tree.txt').read_text().splitlines():
    m=re.search(r'([\w.\-]+):([\w.\-]+):jar:([\w.\-]+):(compile|runtime|test)',line)
    if m and 'omitted' not in line: coordinates.append(dict(zip(['group','artifact','version','scope'],m.groups())))
notices=[]
for coordinate in coordinates:
    g,a,v,s=[coordinate[k] for k in ['group','artifact','version','scope']]
    jar=repository/g.replace('.','/')/a/v/f'{a}-{v}.jar'
    with zipfile.ZipFile(jar) as z:
        for member in z.namelist():
            if member.startswith('META-INF/') and ('license' in member.lower() or 'notice' in member.lower()) and not member.endswith('/'):
                dest=ROOT/'third-party-notices'/f'{a}-{v}-{Path(member).name}'
                dest.write_bytes(z.read(member));notices.append(dest.relative_to(ROOT).as_posix())
for wheel in (ROOT/'tooling/python-wheels').glob('*.whl'):
    with zipfile.ZipFile(wheel) as z:
        for member in z.namelist():
            if ('license' in member.lower() or 'notice' in member.lower()) and not member.endswith('/'):
                dest=ROOT/'third-party-notices'/f'{wheel.stem}-{Path(member).name}'
                dest.write_bytes(z.read(member));notices.append(dest.relative_to(ROOT).as_posix())
lock={'schema_version':1,'date':'2026-10-17','repository':'https://repo.maven.apache.org/maven2/',
      'scope':'All acquired Maven JAR/POM bytes including build/test dependencies; active graph is listed separately. No packaged CLI exists yet.',
      'active_coordinates':coordinates,'maven_artifacts':artifacts,
      'python_artifacts':json.loads((ROOT/'tooling/python-lock.json').read_text()),
      'toolchains':[json.loads((ROOT/'tooling/jdk-lock.json').read_text()),json.loads((ROOT/'tooling/maven-lock.json').read_text())],
      'notice_files':sorted(notices),
      'source_notices':[{'path':'third-party-notices/sqlite-jdbc-3.53.4.0-NOTICE',
                        'url':'https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/NOTICE',
                        'sha256':hashlib.sha256((ROOT/'third-party-notices/sqlite-jdbc-3.53.4.0-NOTICE').read_bytes()).hexdigest()}]}
(ROOT/'dependency-lock.json').write_text(json.dumps(lock,indent=2,sort_keys=True)+'\n')
print(f'Recorded {len(artifacts)} Maven JAR/POM artifacts, {len(coordinates)} active dependencies, two Python wheels')
