# RevisitLedger

Before transferring a subset of local WARC files, find the other files needed by
its revisit records. If A needs a payload in B, and another record in B needs C,
RevisitLedger includes A, B and C and explains both dependencies.

RevisitLedger is an offline, read-only CLI. It audits explicit local files, creates
whole-file dependency plans, and explains saved reports. Its scope is supported
revisit payload dependencies. A complete plan is not proof of archive authenticity,
a fully valid WARC collection, website replayability, or a minimum-size file set.

## Build the source-only alpha

This repository publishes source, tests, fixtures and documentation. No prebuilt
release or Maven Central package is published. To create a runnable distribution,
start in the repository root with JDK 17+, Maven and Python 3.12:

```sh
mvn -B verify
python3 scripts/package.py --jar target/revisitledger-0.1.0.jar
```

The first Maven build downloads the pinned dependencies. Packaging verifies their
locked hashes and creates dist/revisitledger-0.1.0.zip. See the full verification
commands below before using your build.

## Run the included example

Extract your generated distribution ZIP and run these commands from its
revisitledger directory. The distribution includes the application and all pinned
runtime dependencies. It needs a Java runtime; class files target Java 17.
The complete hosted verification suite passes on Linux x86-64 with Temurin 17 and
21. Other operating systems remain unverified; see [validation](docs/VALIDATION.md).
No Maven, Python, network connection or dependency download is needed to run the
already-built distribution.

```sh
chmod +x bin/revisit-ledger  # Needed if your ZIP extractor drops executable bits.
bin/revisit-ledger --help
bin/revisit-ledger audit --root tests/fixtures/derived/whole-file \
  --files examples/corpus-files.txt --json audit.json
bin/revisit-ledger plan --root tests/fixtures/derived/whole-file \
  --files examples/corpus-files.txt --seeds examples/selected-files.txt --json plan.json
bin/revisit-ledger explain --report plan.json --record 'A.warc#1'
```

The plan's includedFiles are A.warc, B.warc and C.warc. Its addedFiles are B.warc
and C.warc. The example uses tiny authored conformance records derived from licensed
warcio test literals, not captured production traffic. See [examples](examples/README.md).

For your collection, replace the root and file lists. Lists contain one explicit
root-relative path per line. Output paths must be new unless --overwrite is given;
archive input aliases are refused even with --overwrite. Windows has a
bin/revisit-ledger.cmd launcher, but it has not been exercised on Windows.

## Results and boundaries

- Exit 0: complete with no blockers under the declared support profile
- Exit 1: fully read inputs with dependency, integrity or unsupported-feature blockers
- Exit 2: arguments, I/O, parsing or a resource limit prevented a complete result

Reports distinguish reading completeness, reference resolution, payload verification,
closure and source-byte rechecking. Missing means absent from the supplied corpus.
Explicit selectors never silently fall back to a different equal-digest payload.
An ambiguous choice requires selecting an enumerated candidate; it cannot waive a
conflict, digest mismatch, unsupported provider or cycle.

Supported: local WARC 1.0/1.1, plain and concatenated gzip, standard identical-payload
revisits, exact ID and URI/full-UTC-date selectors, verified SHA-1/SHA-256, complete
resource and supported HTTP response payloads, strict chunk framing, revisit chains
and whole-file closure. HTTP Content-Encoding bytes stay encoded.

Unsupported or conservative boundaries include gzip FHCRC, incomplete/segmented
providers, other revisit profiles, reduced-precision dates, ARC/WACZ/ZIP/TAR inputs,
URLs, external indexes and symlinks. The program does not retrieve, copy, rewrite or
repair archives. See [limitations](docs/LIMITATIONS.md) and [report format](docs/FORMAT.md).

## Build and verify source

Use a JDK 17+ and Maven. The first build downloads pinned dependencies from Maven
Central; the built runtime then uses local JARs. Optional Python comparison tools
have exact versions and hashes in requirements-oracle.lock.

```sh
mvn -B verify
python3 -m unittest discover -s verification -p 'test_*.py' -v
python3 verification/known_bytes.py --check
python3 verification/project_expectations.py --check
python3 verification/graph_oracle.py --check
python3 scripts/package.py --jar target/revisitledger-0.1.0.jar
python3 verification/package_checks.py --artifact dist/revisitledger-0.1.0.zip
```

The optional acquisition-cache inventory test skips if its original local caches
are absent. All functional tests and independent oracles remain in the source.
The distribution's source-manifest.json lists the exact shipped source bytes;
distribution-manifest.json also accounts for launchers and runtime JARs.

See [measured validation](docs/VALIDATION.md) for what was actually run and what
remains unverified, and [related tools](docs/COMPETITORS.md) for the narrow project
positioning. Original code is MIT-licensed; third-party materials retain their
separate licenses and attribution in NOTICE and third-party-notices/.
