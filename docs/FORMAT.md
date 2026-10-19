# CLI and report format

Commands:

```text
revisit-ledger audit --root DIR --files FILE --json OUTPUT
revisit-ledger plan --root DIR --files FILE --seeds FILE --json OUTPUT [--choices FILE]
revisit-ledger explain --report FILE --record relative/file.warc#ORDINAL
revisit-ledger --help
revisit-ledger --version
```

Audit and plan accept --overwrite, --scratch DIR, --max-header-bytes N,
--max-block-bytes N, --max-expanded-bytes N, --max-records N and --max-scratch-bytes N.
Limits are positive integer byte/count values. Default maxima are 64 KiB per
header, 4 GiB per WARC block, 64 GiB expanded bytes, 1,000,000 records and 4 GiB
index-owned scratch. These are rejection limits, not measured capacity claims.
The launcher sets the Java heap maximum to 128 MiB; process RSS includes additional
native/JVM memory. JAVA_HOME selects the runtime; standard JVM JAVA_TOOL_OPTIONS
remains available. The launcher does not interpret archive data as shell arguments.

File lists are UTF-8, at most 16 MiB and 10,000 entries, with at most 4,096 UTF-8
bytes per path. Paths are explicit and root-relative. Absolute paths, URLs,
backslashes, colons, ASCII control characters and DEL, dot/parent components, symlinks and multiple
paths for one hard-linked input are refused. The root itself and every ancestor
are checked for symlinks before canonicalization; lexical parent components cannot
hide an already encountered link. Output parent directories must exist.
Existing outputs require --overwrite; archive aliases cannot be overwritten.

## Saved JSON

The normative structural schema is [report-v1.schema.json](../schemas/report-v1.schema.json).
Reports use schemaVersion 1, toolVersion 0.1.0 and supportProfile
revisitledger/identical-payload-v1. Their mode is audit or plan.

- limits records the actual configured scan/index bounds
- corpusFingerprint identifies the sealed corpus metadata and source hashes
- files includes root-relative path, raw-file SHA-256, recordCount and read completeness
- records retains one-based {path, ordinal} keys, raw selectors and digest evidence
- scanComplete concerns framing and reading, separately from resolutionComplete
- closureComplete concerns dependency closure; inputsVerified concerns subsequent
  source-byte rechecks; complete is the scoped command result
- includedFiles and addedFiles are sorted whole-file paths for a plan
- choices records explicit bindings; witnessEdges explains added dependencies
- blockers, findings and warnings retain unresolved issues and the scope of the result

Output ordering is deterministic for the same bytes, choices and limits, independent
of input-list ordering. JSON has no wall-clock timestamps, absolute source paths or
payload excerpts. Relevant raw header values are retained as escaped strings, so
reports can still contain private URLs or identifiers from the source archives.
Locators count records from one; gzip offsets are not promised.

Exit 0 means complete under this profile, 1 means a complete read with blockers,
and 2 means invalid arguments or an incomplete result. A parse/resource failure
can leave an explicitly incomplete report if enough state is available, or no
report if output cannot safely be created. Staged writes do not publish an unfinished
report as a complete one.

## Explicit choices

An optional choices file has exactly this structure:

```json
{
  "version": 1,
  "corpusFingerprint": "<64 lowercase hexadecimal characters from the audit>",
  "bindings": [
    {
      "consumer": {"path": "A.warc", "ordinal": 1},
      "provider": {"path": "B.warc", "ordinal": 1}
    }
  ]
}
```

All fields are required; unknown/duplicate JSON keys and duplicate consumers are
rejected. Bounds are 16 MiB per file, 100,000 bindings and 65,536 UTF-16 units per string.
The fingerprint must match the supplied corpus. Every chosen provider must be an
enumerated candidate; a choice cannot waive explicit-selector disagreement,
unsupported bytes/profiles, bad digests or cycles. Regenerate choices after inputs
change. Even unused bindings are validated.

explain validates the complete saved report and EOF before rendering the requested
record. It never reopens a source path or follows a URL from a report. It renders the
record and edges incident to it, plus cross-file edges incident to its file.
Unrelated same-file edges are omitted; use the full JSON for the complete graph. It reports saved evidence, not a fresh verification of current source files.

Saved-report parsing is streaming, strict UTF-8, and limited to nesting depth 24.
All named schema fields are required; unknown/duplicate fields and non-integer JSON
number spellings for integer fields are rejected. General scalar strings are capped
at 1,048,576 UTF-16 units, with smaller field-specific bounds (paths: 65,536 units).
Dynamic maps allow at most 32 raw-header names, 64 evidence keys or two digest
algorithms, as applicable. Arrays stream independently of their total length.

explain stages the selected record and final text in deleted temporary files. Each
stage is capped at 4,194,304 UTF-16 units (at most 16 MiB UTF-8), independent of total
report size. C0/C1/DEL and Unicode FORMAT controls are escaped in terminal text.
This is not a comprehensive scrub of private URLs or identifiers in raw headers.
