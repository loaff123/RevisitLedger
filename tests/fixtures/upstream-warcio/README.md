# Authored upstream conformance fixtures

These are unchanged authored test literals, not a production crawl.
Source: https://github.com/webrecorder/warcio/blob/2a797aa8c6d67e70966ce9d1a690208ab250fbe7/test/test_writer.py

Apache-2.0; the exact upstream LICENSE and NOTICE are included.
Each .warc file preserves one complete UTF-8 literal unchanged, including CRLF.
The revisit selectors intentionally do not match RESPONSE_RECORD: they refer to /foo in 1999; the response is / in 2000.
The upstream literals also intentionally reuse one fixed record ID. Do not treat their union as a closed collection.
provenance.json pins source Git blob/SHA-256 and every output byte length/SHA-256.
