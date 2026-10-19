# Separately attributed modified and independently authored fixtures

These are tiny conformance cases, not captured production traffic. See NOTICE and provenance.json.

closed-pair changes only response target/date and both record IDs; URI/date now close the dependency.
whole-file/A.warc#1 selects B.warc#1. B.warc#2 is an unrelated revisit selecting C.warc#1.
Planning seed A must therefore include entire files A, B and C. All four IDs are distinct.
The HTTP blocks in these derivatives are byte-identical to the source literals.

chunked-response uses extensions on both data chunks and the terminal chunk, plus a trailer. Entity bytes are 736f6d650a74657874.
content-encoded-response retains the fixed 29-byte gzip entity; decoding it would incorrectly produce nine bytes.
header-only-revisit retains Content-Length: 9 with zero retained entity bytes. Its payload evidence is null.
expected.json contains separately specified writer intentions, fixed entity hex, record fields and hashes; verification/known_bytes.py parses the files independently.
Revisit payload_length/sha1_hex/sha256_hex are null because the payload is omitted, not an empty concrete payload.
retained_payload_length counts body bytes after HTTP headers before transfer decoding; it is zero for revisits.

## Observed cross-implementation boundary (warcio 1.8.1)

All 14 records agree on counts, types, raw URI/date, block lengths, and retained-body lengths.
Its content_stream() decodes the 29-byte gzip entity to nine bytes, intentionally outside this contract.
For the valid chunk-trailer case, content_stream() returns 42 bytes rather than nine: the zero-chunk path requires an immediate CRLF, then permissive fallback retains terminal-chunk and trailer text.
ChunkedDataReader(..., raise_exceptions=True) instead raises Incorrect CRLF after length header of 0.
This disagreement does not change the known nine-byte expectation. RFC 9112 section 7.1 permits last-chunk extensions and trailer fields.
Sources: https://www.rfc-editor.org/rfc/rfc9112.html#section-7.1 and https://github.com/webrecorder/warcio/blob/2a797aa8c6d67e70966ce9d1a690208ab250fbe7/warcio/bufferedreaders.py#L334-L343
