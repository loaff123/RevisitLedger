#!/usr/bin/env python3
"""Small independent, stdlib-only byte oracle for the Task 1 plain fixtures.

This is test code, not the production scanner. It reads bounded tiny fixture files
in memory, never imports the fixture writer or Java, and never decodes HTTP
Content-Encoding. Revisit payloads are absent (null), not empty concrete entities.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re

CRLF = b"\r\n"
SEP = b"\r\n\r\n"
TOKEN = rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+"
QUOTED = rb'"(?:[\x20\x21\x23-\x5b\x5d-\x7e]|\\[\x20-\x7e])*"'
EXTENSIONS = re.compile(rb"(?:;" + TOKEN + rb"(?:=(?:" + TOKEN + rb"|" + QUOTED + rb"))?)*")
ROOT = Path(__file__).resolve().parents[1] / "tests/fixtures"


def _headers(raw):
    lines = raw.split(CRLF)
    first = lines[0].decode("ascii")
    result = {}
    for line in lines[1:]:
        name, marker, value = line.partition(b":")
        if not marker or not re.fullmatch(TOKEN, name):
            raise ValueError("invalid header line")
        key = name.decode("ascii").lower()
        if key in result:
            raise ValueError("duplicate header: " + key)
        result[key] = value.strip(b" \t").decode("ascii")
    return first, result


def _split_http(block):
    head, marker, body = block.partition(SEP)
    if not marker:
        raise ValueError("incomplete HTTP headers")
    first, headers = _headers(head)
    if not re.fullmatch(r"HTTP/1\.[01] [0-9]{3}(?: [\x20-\x7e]*)?", first):
        raise ValueError("unsupported HTTP status line")
    return headers, body


def _dechunk(body):
    cursor = 0
    chunks = []
    while True:
        end = body.find(CRLF, cursor)
        if end < 0:
            raise ValueError("missing chunk-size terminator")
        size_line = body[cursor:end]
        size_token, separator, extension = size_line.partition(b";")
        if not re.fullmatch(rb"[0-9A-Fa-f]+", size_token):
            raise ValueError("invalid chunk size")
        if separator and not EXTENSIONS.fullmatch(separator + extension):
            raise ValueError("invalid chunk extension")
        size = int(size_token, 16)
        cursor = end + 2
        if size == 0:
            while True:
                end = body.find(CRLF, cursor)
                if end < 0:
                    raise ValueError("unterminated chunk trailers")
                line = body[cursor:end]
                cursor = end + 2
                if not line:
                    if cursor != len(body):
                        raise ValueError("leftover chunk framing")
                    return b"".join(chunks)
                _, trailer = _headers(b"trailers\r\n" + line)
                if "content-length" in trailer or "transfer-encoding" in trailer:
                    raise ValueError("framing field in chunk trailer")
        if size > len(body) - cursor or body[cursor + size:cursor + size + 2] != CRLF:
            raise ValueError("truncated chunk or missing chunk terminator")
        chunks.append(body[cursor:cursor + size])
        cursor += size + 2


def _payload(kind, block):
    if kind == "resource":
        return block, {}, len(block)
    if kind == "revisit":
        if block:
            headers, retained = _split_http(block)
            if retained:
                raise ValueError("fixture revisit unexpectedly retains entity bytes")
        else:
            headers = {}
        return None, headers, 0
    if kind != "response":
        raise ValueError("unsupported oracle record kind")
    headers, retained = _split_http(block)
    coding = headers.get("transfer-encoding")
    length = headers.get("content-length")
    if coding is not None:
        if coding.lower() != "chunked" or length is not None:
            raise ValueError("unsupported or conflicting HTTP transfer framing")
        return _dechunk(retained), headers, len(retained)
    if length is not None and (not re.fullmatch(r"[0-9]+", length) or int(length) != len(retained)):
        raise ValueError("HTTP Content-Length does not equal retained entity length")
    return retained, headers, len(retained)


def _check_declared_sha1(headers, field, data):
    declared = headers.get(field)
    if declared is None or data is None:
        return
    actual = "sha1:" + base64.b32encode(hashlib.sha1(data).digest()).decode("ascii")
    if declared != actual:
        raise ValueError("declared " + field + " does not match independently hashed bytes")


def inspect_bytes(data, fixture):
    """Return one exact observation per WARC record; reject any unconsumed bytes."""
    records = []
    cursor = 0
    while cursor < len(data):
        end = data.find(SEP, cursor)
        if end < 0:
            raise ValueError("incomplete WARC header or trailing bytes")
        version, headers = _headers(data[cursor:end])
        if version not in ("WARC/1.0", "WARC/1.1"):
            raise ValueError("unsupported WARC version or trailing bytes")
        length = headers.get("content-length", "")
        if not re.fullmatch(r"[0-9]+", length):
            raise ValueError("missing or invalid WARC Content-Length")
        start = end + len(SEP)
        stop = start + int(length)
        if stop > len(data) or data[stop:stop + len(SEP)] != SEP:
            raise ValueError("short WARC block or missing record terminator")
        block = data[start:stop]
        kind = headers["warc-type"]
        payload, http, retained = _payload(kind, block)
        _check_declared_sha1(headers, "warc-block-digest", block)
        _check_declared_sha1(headers, "warc-payload-digest", payload)
        records.append({
            "fixture": fixture, "record_count": 0, "ordinal": len(records) + 1,
            "record_type": kind, "version": version,
            "record_id": headers["warc-record-id"],
            "raw_target": headers.get("warc-target-uri"), "raw_date": headers.get("warc-date"),
            "raw_profile": headers.get("warc-profile"),
            "refers_to": headers.get("warc-refers-to"),
            "refers_to_target": headers.get("warc-refers-to-target-uri"),
            "refers_to_date": headers.get("warc-refers-to-date"),
            "block_length": len(block), "block_sha256_hex": hashlib.sha256(block).hexdigest(),
            "payload_length": None if payload is None else len(payload),
            "payload_hex": None if payload is None else payload.hex(),
            "retained_payload_length": retained,
            "sha1_hex": None if payload is None else hashlib.sha1(payload).hexdigest(),
            "sha256_hex": None if payload is None else hashlib.sha256(payload).hexdigest(),
            "http_content_length": http.get("content-length"),
            "http_transfer_encoding": http.get("transfer-encoding"),
            "http_content_encoding": http.get("content-encoding"),
        })
        cursor = stop + len(SEP)
    for row in records:
        row["record_count"] = len(records)
    return records


def check(root=ROOT):
    """Compare independent byte observations against frozen writer intentions and hashes."""
    root = Path(root)
    problems = []
    expected = json.loads((root / "derived/expected.json").read_text())
    actual = []
    expected_paths = sorted(set(row["fixture"] for row in expected))
    on_disk = sorted(str(p.relative_to(root)) for p in root.rglob("*.warc"))
    if expected_paths != on_disk:
        problems.append("fixture paths differ from frozen expectation set")
    for path in expected_paths:
        try:
            actual.extend(inspect_bytes((root / path).read_bytes(), path))
        except (OSError, ValueError, KeyError) as error:
            problems.append(path + ": " + str(error))
    if actual != expected:
        problems.append("record observations differ from derived/expected.json")
    for manifest_name in ["upstream-warcio/provenance.json", "derived/provenance.json"]:
        manifest = json.loads((root / manifest_name).read_text())
        for entry in manifest["files"]:
            path = root / entry["fixture"]
            data = path.read_bytes()
            if len(data) != entry["byte_length"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                problems.append(entry["fixture"] + ": provenance hash/length mismatch")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify all committed fixture observations and hashes")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("fixtures", nargs="*", type=Path)
    args = parser.parse_args()
    if args.check:
        problems = check(args.root)
        if problems:
            for problem in problems:
                print("FAIL: " + problem)
            return 1
        expected = json.loads((args.root / "derived/expected.json").read_text())
        print(f"PASS: {len(set(r['fixture'] for r in expected))} fixtures, {len(expected)} records; exact bytes, payload boundaries, and provenance agree")
        return 0
    rows = []
    paths = args.fixtures or sorted(args.root.rglob("*.warc"))
    for path in paths:
        rows.extend(inspect_bytes(path.read_bytes(), str(path.relative_to(args.root)) if path.is_relative_to(args.root) else str(path)))
    print(json.dumps(rows, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
