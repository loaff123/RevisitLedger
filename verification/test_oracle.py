"""Independent fixture-oracle tests. Run: python3 -m unittest discover -s verification -v."""
import base64
import gzip
import hashlib
import importlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = b"some\ntext"
ENCODED = bytes.fromhex("1f8b08000000000002032bcecf4de52a49ad2801001f8acbc409000000")


def record(block, kind="response", extra=b""):
    """Test-only handcrafted framing; deliberately not the fixture serializer."""
    return (b"WARC/1.1\r\nWARC-Type: " + kind.encode() +
            b"\r\nWARC-Record-ID: <urn:uuid:00000000-0000-4000-8000-000000000001>" +
            b"\r\nWARC-Target-URI: http://example.org/raw%2fPath" +
            b"\r\nWARC-Date: 2000-01-01T00:00:00.123456Z\r\n" + extra +
            b"Content-Length: " + str(len(block)).encode() + b"\r\n\r\n" + block + b"\r\n\r\n")


class OracleTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(importlib.util.find_spec("known_bytes"), "known-byte oracle is not implemented")
        self.oracle = importlib.import_module("known_bytes")

    def test_plain_payload_hashes_and_exact_headers(self):
        rows = self.oracle.inspect_bytes(record(b"HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n" + PAYLOAD), "x.warc")
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["payload_length"], 9)
        self.assertEqual(row["sha1_hex"], "0fa09f05a9dc781d6ef76455951d6f3271aa938e")
        self.assertEqual(row["sha256_hex"], "e8e5bf447c352c0080e1444994b0cc1fbe7a25f3ea637c5c89f595b6a95c9253")
        self.assertEqual(row["raw_target"], "http://example.org/raw%2fPath")
        self.assertEqual(row["raw_date"], "2000-01-01T00:00:00.123456Z")

    def test_chunk_extensions_and_trailers_are_not_payload(self):
        block = (b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: X-Checksum\r\n\r\n"
                 b"4;name=one\r\nsome\r\n5;quoted=\"two words\"\r\n\ntext\r\n0;done=yes\r\nX-Checksum: ignored\r\n\r\n")
        row = self.oracle.inspect_bytes(record(block), "chunked.warc")[0]
        self.assertEqual(row["payload_hex"], PAYLOAD.hex())
        self.assertEqual(row["payload_length"], 9)

    def test_content_encoding_remains_opaque(self):
        block = b"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 29\r\n\r\n" + ENCODED
        row = self.oracle.inspect_bytes(record(block), "encoded.warc")[0]
        self.assertEqual(gzip.decompress(ENCODED), PAYLOAD)
        self.assertEqual(row["payload_hex"], ENCODED.hex())
        self.assertEqual(row["payload_length"], 29)
        self.assertEqual(row["sha256_hex"], "fb0bd9c637526215c2b2b5949ec0a9b85e072b086840e9900115f2b131d14817")

    def test_revisit_omitted_length_does_not_consume_next_record(self):
        revisit = record(b"HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n", "revisit")
        following = record(PAYLOAD, "resource")
        rows = self.oracle.inspect_bytes(revisit + following, "pair.warc")
        self.assertEqual([r["record_type"] for r in rows], ["revisit", "resource"])
        self.assertIsNone(rows[0]["payload_length"])
        self.assertIsNone(rows[0]["sha1_hex"])
        self.assertEqual(rows[0]["retained_payload_length"], 0)
        self.assertEqual(rows[1]["payload_hex"], PAYLOAD.hex())
        self.assertEqual([r["record_count"] for r in rows], [2, 2])

    def test_rejects_short_block_and_trailing_junk(self):
        for raw in [record(PAYLOAD, "resource")[:-5], record(PAYLOAD, "resource") + b"garbage"]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                self.oracle.inspect_bytes(raw, "bad.warc")

    def test_rejects_duplicate_warc_lengths(self):
        with self.assertRaises(ValueError):
            self.oracle.inspect_bytes(record(PAYLOAD, "resource", b"Content-Length: 9\r\n"), "duplicate.warc")

    def test_rejects_unsafe_http_framing(self):
        bad_blocks = [
            b"HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n" + PAYLOAD,
            b"HTTP/1.1 200 OK\r\nContent-Length: 9\r\nContent-Length: 9\r\n\r\n" + PAYLOAD,
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n",
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n0\r\n\r\n",
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\nsome\ntext\r\n0\r\n\r\nJUNK",
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\nsome\ntext\r\n",
        ]
        for block in bad_blocks:
            with self.subTest(block=block), self.assertRaises(ValueError):
                self.oracle.inspect_bytes(record(block), "bad.warc")


class ExtractionTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(importlib.util.find_spec("extract_fixtures"), "static extractor is not implemented")
        self.extractor = importlib.import_module("extract_fixtures")

    def test_static_extraction_never_executes_imports_or_code(self):
        source = b"raise RuntimeError('must never execute')\nimport no_such_module\nRESPONSE_RECORD = 'safe\\r\\n'\n"
        actual = self.extractor.extract_literals(source, ("RESPONSE_RECORD",))
        self.assertEqual(actual, {"RESPONSE_RECORD": b"safe\r\n"})

    def test_missing_duplicate_or_computed_literal_rejected(self):
        for source in [b"OTHER = 'x'", b"RESPONSE_RECORD='a'\nRESPONSE_RECORD='b'", b"RESPONSE_RECORD=str('a')"]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.extractor.extract_literals(source, ("RESPONSE_RECORD",))

    def test_git_blob_and_sha256_are_checked(self):
        raw = b"hello\n"
        self.assertEqual(self.extractor.git_blob_sha(raw), "ce013625030ba8dba906f756967f9e9ca394464a")
        with self.assertRaises(ValueError):
            self.extractor.verify_source("test/test_writer.py", raw)


class CommittedFixtureTests(unittest.TestCase):
    def test_oracle_matches_all_checked_in_expectations(self):
        self.assertIsNotNone(importlib.util.find_spec("known_bytes"), "known-byte oracle is not implemented")
        oracle = importlib.import_module("known_bytes")
        self.assertTrue((ROOT / "tests/fixtures/derived/expected.json").is_file(), "fixtures not yet extracted")
        self.assertEqual(oracle.check(ROOT / "tests/fixtures"), [])


if __name__ == "__main__":
    unittest.main()
