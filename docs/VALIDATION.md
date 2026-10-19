# Validation record

Candidate version: 0.1.0. Measurements use authored conformance fixtures and separately
generated synthetic inputs; no production crawl has been qualified.

## Verified candidate

- Offline Maven verify: 242 Java tests, zero failures/errors/skips
- Same full Maven suite from an extracted source distribution outside the checkout:
  242 tests passed, with identical compiled application class bytes
- Workspace Python discovery: 41 tests passed
- Extracted-source Python discovery: 40 passed; one acquisition-cache inventory test
  explicitly skipped because optional build caches are not distributed
- Independent graph comparison: 43 graphs, 524 seed subsets, 243 record outcomes,
  zero disagreements; deterministic JSON/TSV expectations match
- Independent byte oracle: 13 fixture files, 14 records, fixed payload-byte boundaries
  and unchanged provenance hashes; all 23 fixture/provenance/rights files preserved
- Outside-checkout package smoke: 15 command cases passed, with source/distribution
  manifest verification and unchanged archive hashes
- Separate CLI integration checks passed for subset re-audit, exact exit codes,
  hardlink/no-clobber safety, root/ancestor-link rejection, saved-report explanation,
  deterministic output and
  cooperative SIGTERM cleanup without publishing partial output

The Java suite includes 14 independent adversarial CLI/report probes, 10,000-node
chains/cycles and 2,000-file whole-file closure under a 128 MiB Java heap. Eleven
additional root-path regressions cover direct/ancestor symlinks and lexical parent
components across path resolution, scanning, source re-verification and audit/plan.
Root components are checked before canonicalization can erase symlink evidence.
The packaged smoke suite separately verifies direct-root and ancestor-link rejection. A clean
source export retains every functional test and the independent Python oracles.
No build cache, internal working log or machine-specific configuration is shipped.

## Environment and unverified scope

- Measured runtime: Linux x86-64, OpenJDK 21.0.12.1
- Build tools: Apache Maven 3.10.0 and OpenJDK 21.0.2
- Python checks/qualification: Python 3.12.14
- Build source/target: Java 17 (--release 17), with classfile-major checks
- Java 17 runtime: not installed, not run
- Windows/macOS/other CPU/native-library combinations: not run
- Network dependency downloads: unnecessary for the bundled runtime
- OS-level network namespace isolation: unavailable; unshare -n was denied
- GitHub Actions configuration: provided, not run on hosted infrastructure
- Representative production archive: not available, not run

Offline Maven dependency reuse and bundled-runtime execution do not prove OS-enforced
network isolation. Java heap limits do not describe the process RSS maximum.

## Reproduce

Run the README verification commands. The package smoke test extracts outside the
checkout, verifies the exact manifest, and runs help, the licensed tiny fixture,
A/B/C closure/subset, saved-report explanation, deterministic output, output safety,
missing references, unsupported profiles and gzip corruption.

For bounded synthetic measurements:

```sh
python3 verification/qualification.py generate --directory qualification-inputs
python3 verification/qualification.py run \
  --launcher bin/revisit-ledger --directory qualification-inputs \
  --output qualification-results.json
```

The generator writes a 128 MiB concrete payload and a separate 100,000-record corpus
with 50,000 concrete-provider/revisit pairs. Input hashes are deterministic. The
runner records elapsed time, per-process maximum RSS from Linux wait4, maximum sampled
scratch file bytes at 50 ms intervals, record counts, cleanup and before/after hashes.
Scratch sampling is an observed lower bound on peak file bytes, not allocated disk
blocks. The Python verifier is outside the measured JVM heap/RSS process.

## Measured synthetic cases

Both final packaged CLI runs used the launcher's -Xmx128m on the runtime above.
Both returned 0 with scanComplete=true, complete=true, the exact expected record
count, unchanged input hashes and zero owned scratch bytes after exit.

| Case | Input bytes | Records | Elapsed | Maximum process RSS | Maximum sampled scratch bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| 128 MiB concrete payload | 134,218,034 | 1 | 1.111 s | 62,732 KiB | 1,146,816 |
| 50,000 provider/revisit pairs | 39,038,895 | 100,000 | 52.589 s | 189,024 KiB | 169,906,112 |

The second JSON report was 146,184,761 bytes. These final measurements supersede
earlier candidate observations, including the executable before root-path hardening. The application JAR used for the run has
SHA-256 1a33c72145008d1d0c7d4730cf4059d711bd81bcfac1bebcc3ae3bc0937cccdd.

Saved-report explanation also passed on the 146,184,761-byte / 100,000-record report
for ordinals 2 and 100,000 under -Xmx128m: 6.998/6.948 seconds and 165,236/162,152 KiB
maximum process RSS respectively, producing 2,502/2,554-byte text results. The source
report remained unchanged and temporary files were cleaned.

These are single-run synthetic observations, not a universal complexity or
throughput guarantee. Large output and native/JVM
overhead remain material even with a bounded Java heap.

Input SHA-256 values:

- payload.warc: 0436ba31a015069064920b8880b317ec064a5a1077a3ab234217fbb1b57b16ca
- records.warc: 708db4feeeecf36ac760a43ca7c6bac7663d62ecf7a811af9219719cd059b3ee

## Baselines

The pinned warcio 1.8.1 command was warcio check -v FILE for each same one-record
missing-ID and URI/date-only missing-provider fixture. Both returned 0 and printed
“digest present but not checked (revisit)”. RevisitLedger audit returned 1 for both,
with scanComplete=true, complete=false and missing_in_corpus. That difference is
expected: the digest-checker's successful exit is not a dependency-closure claim.

The missing-ID fixture removes URI/date reference fields from the licensed A.warc
revisit derivative while preserving its reference ID and empty block. Its SHA-256
is b953a4ecdf6697cbdb7cddb91a15ec46fb618c4ee6d175cf233589834be16446. The URI/date-only case uses the unchanged closed-pair/revisit.warc
derivative alone, whose SHA-256 is a358aa0931f56010aa800850dd99dcdca240bc6bee1bb0564ac44e95b43a7f46. No third-party payloads were acquired.

warcat-rs is not installed, so its matching baseline is not run. Source inspection
of its reference checker is context, not substitute measured evidence.
