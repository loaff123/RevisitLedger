# Support and limitations

## What completion means

A complete result describes supported revisit payload dependencies among explicitly
supplied local files. A whole-file plan is sufficient for its selected bindings;
it is not globally minimal. Adding a provider file activates every revisit in that
file. Unknown unrelated non-revisit types are retained diagnostically and do not
independently create revisit obligations. Concrete-provider digest errors in an
included file still block completion.

A full read can have blockers. Identity, concrete payload verification and framing
completeness are separate. An explicit selector that fails never falls back to an
equal-digest record. Repeated equal payloads are ordinary alternatives, not corruption;
duplicate record IDs are an identity problem. Chains must end at a supported verified
concrete provider, with every declared payload digest checked. Cycles are unresolved.

Neither SHA-1 consistency nor additional SHA-256 evidence establishes authenticity,
provenance, collision resistance of a historical SHA-1 declaration, complete website
preservation, or replay behavior in a particular importer.

## Supported byte and selector contract

- WARC 1.0/1.1 regular files with .warc or .warc.gz names; magic bytes choose gzip
- Concatenated and whole-file gzip with verified CRC, length and complete consumption
- The exact standard WARC 1.0/1.1 identical-payload-digest profile URIs
- Exact record-ID and target-URI/full-UTC-date selectors, compared conjunctively
- Full UTC instants with zero to nine fractional digits, compared without floats
- Complete resource bodies with http/https/ftp targets; HTTP response bodies with
  http/https targets and supported application/http framing
- Strict fixed/close-delimited or chunked HTTP entity boundaries; Content-Encoding
  bytes remain encoded and are never automatically decompressed
- SHA-1 and SHA-256 with the fixed supported algorithm names and strict hexadecimal,
  base32, or standard padded-base64 encodings

The adapter rejects incomplete framing instead of searching payload bytes for a
new WARC header. Repeated/conflicting framing fields, trailing bytes, corrupt later
gzip members, unsupported transfer-coding chains and resource-limit failures cannot
produce a complete scan.

## Unsupported or deliberately conservative cases

- gzip FHCRC, even when its header checksum is correct
- Metadata-heavy/long empty-member gzip runs beyond the no-progress guard
- Truncated or segmented concrete providers; request/conversion/continuation kinds
- Server-not-modified or unknown revisit profiles
- Reduced date precision, timezone offsets, leap-second representations, or more
  than nine fractional digits
- Interim HTTP response sequences and unsupported trailer/framing combinations
- ARC, WACZ, ZIP and TAR archive inputs; remote URLs and external CDX indexes
- Symlinks, including the corpus root or any ancestor, and links pointing inside
  the selected root

Some refused inputs may be valid under broader WARC/HTTP rules. These boundaries
are the application's conservative support profile, not a universal validity test.

## Resources and source stability

Payloads, indexes and chains use streaming/disk-backed processing. Limits include
header bytes, block bytes, aggregate expansion, record count and owned scratch bytes.
They do not cap wall time, raw-file fingerprint I/O, filesystem allocated blocks,
external output size or total process RSS. A large successful report may be much
larger than its inputs' metadata. The default Java heap is 128 MiB; JVM/native SQLite
memory is additional.

Source checks are read-only, per-file and non-atomic. Ordinary changes and symlink
substitutions are detected and invalidate completion. Equal-byte regular-file
replacement can pass a hash recheck, and an earlier checked file can change later.
Use a stable snapshot and keep the input bytes immutable while acting on a plan.
The planner's second membership check is logical; independently auditing the
planned subset provides an additional parse. No adversarial filesystem-race or
atomic-snapshot guarantee is made.

Index files and extracted native libraries use owned scratch and are cleaned on
normal success/failure and cooperative interruption. Abrupt process termination or
machine failure can leave temporary files. The disposable SQLite index uses no
recovery journal and cannot be reused after a write/quota failure.

Runtime archive access has no fetching or telemetry path. Offline local-JAR execution
is tested separately from operating-system network isolation. See VALIDATION.md
for the measured environment, unavailable checks and synthetic-data scope.
