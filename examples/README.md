# Whole-file dependency example

These are authored upstream conformance fixtures and separately labeled derivatives.
They are not production crawl samples. Original license, notice, pinned provenance
and every fixture hash are under tests/fixtures/. The HTTP block bytes in the
whole-file derivatives remain identical to their source test literals.

The generated [audit.json](audit.json) and [plan.json](plan.json) are deterministic
example outputs from this three-file corpus. Repeating both commands produced
byte-identical reports.

Run the README commands from the extracted distribution directory. corpus-files.txt
lists all three available files. selected-files.txt starts with A.warc only.

A.warc#1 refers to B.warc#1. Including the entire B.warc file also includes B.warc#2,
a separate revisit that refers to C.warc#1. The sufficient closure is therefore
A.warc, B.warc and C.warc. The program reports why each file was added.

To inspect missing-reference behavior, make a new list containing only A.warc and
B.warc, then audit it with a new JSON output path. The full scan finishes but the
missing C dependency blocks completion and the command returns 1. This does not
mean C is absent outside the explicitly supplied corpus.

Reports are JSON metadata. explain reads a saved report; it does not open archive
paths from the report or retrieve a URL. The report retains relevant raw header
values. Review those values before sharing a report from a private collection.

The original unchanged warcio revisit literals intentionally do not form a closed
collection with the unchanged response literal and reuse a record ID. Their
individual presence is not a complete-collection example.
