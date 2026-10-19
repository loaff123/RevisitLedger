#!/usr/bin/env python3
"""Reproduce licensed Task 1 fixtures; never import/execute downloaded source.

Default reads only pinned official upstream URLs. --source-dir reads an existing
local checkout; --source-json-stdin accepts a JSON map of upstream path to base64
bytes (for a connector download). All three upstream files are hash-verified.
The entire downloaded Python module is never copied into the output fixtures.
"""
import argparse
import ast
import base64
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

COMMIT = "2a797aa8c6d67e70966ce9d1a690208ab250fbe7"
SOURCE_PATH = "test/test_writer.py"
SOURCE_BLOB = "597c24f9d4f3f407de165f3357457f13fe69d74f"
BASE = f"https://raw.githubusercontent.com/webrecorder/warcio/{COMMIT}/"
BROWSE = f"https://github.com/webrecorder/warcio/blob/{COMMIT}/"
PINNED = {
    SOURCE_PATH: (SOURCE_BLOB, "e23bdd509a28ee14cf73e294456e77decbc8cc80d663e93ddce722447e8ec430"),
    "LICENSE": ("d3cdba059785ad8554c12c170dfddc7bc0484886", "a428ab27f0cf20f5b887af1385ab2c7a9d6881936fa7bea4015feae5eb8cfd91"),
    "NOTICE": ("17b221238ad855fce8f47b0b239b4ff16c9ba4f2", "91b3105745a3a5b434c8040e9df47219b0710b26228d2550992bd8b090ed56a8"),
}
SYMBOLS = ("RESPONSE_RECORD", "REVISIT_RECORD_1", "REVISIT_RECORD_2", "REVISIT_RECORD_3", "RESOURCE_RECORD")
FIXTURE_PINS = {
    "RESPONSE_RECORD": (458, "2face319bb3dd9adbd018cce20f8bbc7c8e023b6334e34021c1377b26b44765e"),
    "REVISIT_RECORD_1": (533, "95364f58dd2f0f3877511ab998bd94693cba890548ec16a0c92d7e59df701b37"),
    "REVISIT_RECORD_2": (622, "d4aeac98ee08596efe0beafc0d3fd15f3911eb25ee3b93c398be73487fd129ba"),
    "REVISIT_RECORD_3": (540, "f6875dc51078bbc07c584d0ae970e12c93089529397e9a851ac4162377a1d05b"),
    "RESOURCE_RECORD": (344, "447d4d3602c9fbd731690fe3b74543b818cc1db9e7c57fc1d85dfe8399e78041"),
}
PAYLOAD = b"some\ntext"
# Fixed, valid gzip member. OS=3, mtime=0: do not depend on local zlib/gzip output.
ENCODED = bytes.fromhex("1f8b08000000000002032bcecf4de52a49ad2801001f8acbc409000000")
HTTP_HEAD = b'HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset="UTF-8"\r\nCustom-Header: somevalue\r\n\r\n'
OLD_ID = "<urn:uuid:12345678-feb0-11e6-8f83-68a86d1772ce>"
PROFILE10 = "http://netpreserve.org/warc/1.0/revisit/identical-payload-digest"
PROFILE11 = "http://netpreserve.org/warc/1.1/revisit/identical-payload-digest"
ROOT = Path(__file__).resolve().parents[1] / "tests/fixtures"


def git_blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()


def verify_source(path, data):
    blob, sha256 = PINNED[path]
    if git_blob_sha(data) != blob or hashlib.sha256(data).hexdigest() != sha256:
        raise ValueError("pinned source hash mismatch: " + path)


def extract_literals(source, symbols=SYMBOLS):
    """Evaluate only explicitly named top-level literal assignment AST nodes."""
    found = {}
    for node in ast.parse(source).body:
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if not isinstance(target, ast.Name) or target.id not in symbols:
                continue
            if target.id in found:
                raise ValueError("duplicate requested literal: " + target.id)
            try:
                value = ast.literal_eval(node.value)
            except (ValueError, TypeError) as error:
                raise ValueError("requested value is not a literal: " + target.id) from error
            if not isinstance(value, str):
                raise ValueError("requested value must be a string literal: " + target.id)
            found[target.id] = value.encode("utf-8")
    missing = set(symbols) - found.keys()
    if missing:
        raise ValueError("missing requested literals: " + ", ".join(sorted(missing)))
    return found


def sha1_declared(data):
    return "sha1:" + base64.b32encode(hashlib.sha1(data).digest()).decode("ascii")


def identifier(n):
    return f"<urn:uuid:00000000-0000-4000-8000-{n:012d}>"


def metadata(symbol):
    revisit = symbol.startswith("REVISIT")
    resource = symbol == "RESOURCE_RECORD"
    return {
        "record_type": "revisit" if revisit else "resource" if resource else "response",
        "version": "WARC/1.1" if symbol == "REVISIT_RECORD_3" else "WARC/1.0",
        "record_id": OLD_ID,
        "raw_target": "ftp://example.com/" if resource else "http://example.com/",
        "raw_date": "2000-01-01T00:00:00.123456Z" if symbol == "REVISIT_RECORD_3" else "2000-01-01T00:00:00Z",
        "raw_profile": (PROFILE11 if symbol == "REVISIT_RECORD_3" else PROFILE10) if revisit else None,
        "refers_to": None,
        "refers_to_target": "http://example.com/foo" if revisit else None,
        "refers_to_date": "1999-01-01T00:00:00Z" if revisit else None,
    }


def expected_row(path, meta, block, payload, http=None, retained=None):
    """Writer intentions from known source literals, not a readback of serialized WARC."""
    http = http or {}
    return {
        "fixture": path, "record_count": 1, "ordinal": 1, **meta,
        "block_length": len(block), "block_sha256_hex": hashlib.sha256(block).hexdigest(),
        "payload_length": None if payload is None else len(payload),
        "payload_hex": None if payload is None else payload.hex(),
        "retained_payload_length": (0 if payload is None else len(payload)) if retained is None else retained,
        "sha1_hex": None if payload is None else hashlib.sha1(payload).hexdigest(),
        "sha256_hex": None if payload is None else hashlib.sha256(payload).hexdigest(),
        "http_content_length": http.get("content-length"),
        "http_transfer_encoding": http.get("transfer-encoding"),
        "http_content_encoding": http.get("content-encoding"),
    }


def patch_headers(raw, changes):
    head, block = raw.split(b"\r\n\r\n", 1)
    lines = head.split(b"\r\n")
    remaining = dict(changes)
    for i, line in enumerate(lines):
        name, colon, _ = line.partition(b":")
        key = name.decode("ascii")
        if colon and key in remaining:
            lines[i] = name + b": " + remaining.pop(key).encode("ascii")
    for name, value in remaining.items():
        lines.append(name.encode("ascii") + b": " + value.encode("ascii"))
    return b"\r\n".join(lines) + b"\r\n\r\n" + block


def serialize(meta, block, payload):
    """Independent literal-byte serializer used only for additional authored cases."""
    fields = [
        ("WARC-Type", meta["record_type"]), ("WARC-Record-ID", meta["record_id"]),
        ("WARC-Target-URI", meta["raw_target"]), ("WARC-Date", meta["raw_date"]),
    ]
    for key, field in [("raw_profile", "WARC-Profile"), ("refers_to", "WARC-Refers-To"),
                       ("refers_to_target", "WARC-Refers-To-Target-URI"), ("refers_to_date", "WARC-Refers-To-Date")]:
        if meta.get(key) is not None:
            fields.append((field, meta[key]))
    fields.extend([("WARC-Payload-Digest", sha1_declared(PAYLOAD if payload is None else payload)),
                   ("WARC-Block-Digest", sha1_declared(block)),
                   ("Content-Type", "text/plain" if meta["record_type"] == "resource" else "application/http; msgtype=response"),
                   ("Content-Length", str(len(block)))])
    head = meta["version"] + "\r\n" + "\r\n".join(k + ": " + v for k, v in fields)
    return head.encode("ascii") + b"\r\n\r\n" + block + b"\r\n\r\n"


def build(source_files, root):
    for path, data in source_files.items():
        verify_source(path, data)
    literals = extract_literals(source_files[SOURCE_PATH])
    for name, data in literals.items():
        if (len(data), hashlib.sha256(data).hexdigest()) != FIXTURE_PINS[name]:
            raise ValueError("extracted fixture differs from pinned exact bytes: " + name)
    if sha1_declared(PAYLOAD) != "sha1:B6QJ6BNJ3R4B23XXMRKZKHLPGJY2VE4O":
        raise ValueError("authored payload digest disagrees with source")
    root = Path(root)
    upstream = []
    derived = []
    rows = []

    def write(path, data, symbol, modifications, upstream_file=False, source_path=SOURCE_PATH):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        entry = {
            "fixture": path, "source_url": BROWSE + source_path,
            "source_commit": COMMIT, "source_blob_sha": PINNED[source_path][0],
            "symbol": symbol, "byte_length": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "modifications": modifications,
        }
        if symbol is None and source_path == SOURCE_PATH:
            entry.update({"source_url": None, "source_commit": None, "source_blob_sha": None,
                          "source_script": "verification/extract_fixtures.py", "source_kind": "independently_authored"})
        (upstream if upstream_file else derived).append(entry)

    blocks = {"RESPONSE_RECORD": HTTP_HEAD + PAYLOAD, "REVISIT_RECORD_1": b"",
              "REVISIT_RECORD_2": HTTP_HEAD, "REVISIT_RECORD_3": b"", "RESOURCE_RECORD": PAYLOAD}
    for name in SYMBOLS:
        path = "upstream-warcio/" + name + ".warc"
        write(path, literals[name], name, [], True)
        rows.append(expected_row(path, metadata(name), blocks[name], None if name.startswith("REVISIT") else PAYLOAD))
    for name in ["LICENSE", "NOTICE"]:
        write("upstream-warcio/" + name, source_files[name], None, [], True, name)

    def derived_literal(path, name, changes, meta_changes):
        data = patch_headers(literals[name], changes)
        modifications = [{"header": k, "new_value": v} for k, v in changes.items()]
        write(path, data, name, modifications)
        rows.append(expected_row(path, {**metadata(name), **meta_changes}, blocks[name], None if name.startswith("REVISIT") else PAYLOAD))
        return data

    derived_literal("derived/closed-pair/response.warc", "RESPONSE_RECORD",
                    {"WARC-Record-ID": identifier(101), "WARC-Target-URI": "http://example.com/foo", "WARC-Date": "1999-01-01T00:00:00Z"},
                    {"record_id": identifier(101), "raw_target": "http://example.com/foo", "raw_date": "1999-01-01T00:00:00Z"})
    derived_literal("derived/closed-pair/revisit.warc", "REVISIT_RECORD_1",
                    {"WARC-Record-ID": identifier(102)}, {"record_id": identifier(102)})

    whole_specs = [
        ("A.warc", "REVISIT_RECORD_1", 201, "http://example.com/a", "2001-01-01T00:00:00Z", 202, "http://example.com/b", "1999-01-01T00:00:00Z"),
        ("B.warc", "RESPONSE_RECORD", 202, "http://example.com/b", "1999-01-01T00:00:00Z", None, None, None),
        ("B.warc", "REVISIT_RECORD_2", 203, "http://example.com/unrelated", "2002-01-01T00:00:00Z", 204, "http://example.com/c", "1998-01-01T00:00:00Z"),
        ("C.warc", "RESPONSE_RECORD", 204, "http://example.com/c", "1998-01-01T00:00:00Z", None, None, None),
    ]
    assembled = {}
    whole_mods = {}
    for file, name, rid, target, date, refid, reftarget, refdate in whole_specs:
        path = "derived/whole-file/" + file
        changes = {"WARC-Record-ID": identifier(rid), "WARC-Target-URI": target, "WARC-Date": date}
        meta = {**metadata(name), "record_id": identifier(rid), "raw_target": target, "raw_date": date}
        if refid is not None:
            changes.update({"WARC-Refers-To": identifier(refid), "WARC-Refers-To-Target-URI": reftarget, "WARC-Refers-To-Date": refdate})
            meta.update({"refers_to": identifier(refid), "refers_to_target": reftarget, "refers_to_date": refdate})
        assembled.setdefault(path, []).append(patch_headers(literals[name], changes))
        whole_mods.setdefault(path, []).append({"source_symbol": name, "record_ordinal": len(assembled[path]), "header_changes": changes})
        row = expected_row(path, meta, blocks[name], None if name.startswith("REVISIT") else PAYLOAD)
        row["ordinal"] = len(assembled[path])
        rows.append(row)
    for path, records in assembled.items():
        write(path, b"".join(records), [m["source_symbol"] for m in whole_mods[path]], whole_mods[path])
        for row in rows:
            if row["fixture"] == path:
                row["record_count"] = len(records)

    chunk_body = b'4;name=one\r\nsome\r\n5;quoted="two words"\r\n\ntext\r\n0;done=yes\r\nX-Checksum: ignored\r\n\r\n'
    chunk_head = b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nTrailer: X-Checksum\r\n\r\n"
    encoded_head = b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: gzip\r\nContent-Length: 29\r\n\r\n"
    revisit_head = b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 9\r\n\r\n"
    cases = [
        ("chunked-response", 301, "response", chunk_head + chunk_body, PAYLOAD, {"transfer-encoding": "chunked"}, len(chunk_body)),
        ("content-encoded-response", 302, "response", encoded_head + ENCODED, ENCODED, {"content-encoding": "gzip", "content-length": "29"}, len(ENCODED)),
        ("header-only-revisit", 303, "revisit", revisit_head, None, {"content-length": "9"}, 0),
    ]
    for label, rid, kind, block, payload, http, retained in cases:
        meta = {**metadata("REVISIT_RECORD_1" if kind == "revisit" else "RESPONSE_RECORD"),
                "version": "WARC/1.1", "record_id": identifier(rid), "raw_target": "http://example.com/" + label,
                "raw_profile": PROFILE11 if kind == "revisit" else None}
        path = "derived/" + label + ".warc"
        write(path, serialize(meta, block, payload), None,
              [{"authorship": "Independently serialized RevisitLedger test case; no warcio or jwarc writer used.",
                "details": label + "; payload entity is fixed known bytes; WARC and block digests computed with Python hashlib."}])
        rows.append(expected_row(path, meta, block, payload, http, retained))

    write("derived/content-encoded-entity.bin", ENCODED, None,
          [{"authorship": "Fixed independently authored gzip entity bytes; no platform recompression.",
            "decoded_entity_hex": PAYLOAD.hex()}])

    # Frozen writer intentions are compared by a separate parser in known_bytes.py.
    rows.sort(key=lambda r: (r["fixture"], r["ordinal"]))
    (root / "derived/expected.json").write_text(json.dumps(rows, indent=2, sort_keys=True) + "\n")
    for name, files in [("upstream-warcio", upstream), ("derived", derived)]:
        manifest = {"schema_version": 1, "fixture_kind": "authored upstream conformance inputs and separately labeled derivatives; not a production crawl",
                    "source_commit": COMMIT, "source_blob_sha": SOURCE_BLOB,
                    "source_sha256": PINNED[SOURCE_PATH][1], "source_byte_length": len(source_files[SOURCE_PATH]),
                    "extraction": "ast.parse; ast.literal_eval of exactly five named top-level string literals; UTF-8 encoding; no import or execution",
                    "files": sorted(files, key=lambda f: f["fixture"])}
        (root / name / "provenance.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (root / "upstream-warcio/README.md").write_text(
        "# Authored upstream conformance fixtures\n\nThese are unchanged authored test literals, not a production crawl.\n"
        "Source: " + BROWSE + SOURCE_PATH + "\n\nApache-2.0; the exact upstream LICENSE and NOTICE are included.\n"
        "Each .warc file preserves one complete UTF-8 literal unchanged, including CRLF.\n"
        "The revisit selectors intentionally do not match RESPONSE_RECORD: they refer to /foo in 1999; the response is / in 2000.\n"
        "The upstream literals also intentionally reuse one fixed record ID. Do not treat their union as a closed collection.\n"
        "provenance.json pins source Git blob/SHA-256 and every output byte length/SHA-256.\n")
    (root / "derived/NOTICE").write_bytes(source_files["NOTICE"] +
        b"\nRevisitLedger fixture modifications, 2026-10-17:\n"
        b"closed-pair and whole-file fixtures modify the named authored warcio literals.\n"
        b"Every modified header and source symbol is listed in provenance.json.\n"
        b"The original HTTP blocks, payloads and block digests in those derivatives remain unchanged.\n"
        b"chunked-response, content-encoded-response and header-only-revisit were independently serialized for this gate.\n"
        b"The unchanged upstream Apache-2.0 LICENSE is in ../upstream-warcio/LICENSE.\n")
    (root / "derived/README.md").write_text(
        "# Separately attributed modified and independently authored fixtures\n\n"
        "These are tiny conformance cases, not captured production traffic. See NOTICE and provenance.json.\n\n"
        "closed-pair changes only response target/date and both record IDs; URI/date now close the dependency.\n"
        "whole-file/A.warc#1 selects B.warc#1. B.warc#2 is an unrelated revisit selecting C.warc#1.\n"
        "Planning seed A must therefore include entire files A, B and C. All four IDs are distinct.\n"
        "The HTTP blocks in these derivatives are byte-identical to the source literals.\n\n"
        "chunked-response uses extensions on both data chunks and the terminal chunk, plus a trailer. Entity bytes are 736f6d650a74657874.\n"
        "content-encoded-response retains the fixed 29-byte gzip entity; decoding it would incorrectly produce nine bytes.\n"
        "header-only-revisit retains Content-Length: 9 with zero retained entity bytes. Its payload evidence is null.\n"
        "expected.json contains separately specified writer intentions, fixed entity hex, record fields and hashes; verification/known_bytes.py parses the files independently.\n"
        "Revisit payload_length/sha1_hex/sha256_hex are null because the payload is omitted, not an empty concrete payload.\n"
        "retained_payload_length counts body bytes after HTTP headers before transfer decoding; it is zero for revisits.\n\n"
        "## Observed cross-implementation boundary (warcio 1.8.1)\n\n"
        "All 14 records agree on counts, types, raw URI/date, block lengths, and retained-body lengths.\n"
        "Its content_stream() decodes the 29-byte gzip entity to nine bytes, intentionally outside this contract.\n"
        "For the valid chunk-trailer case, content_stream() returns 42 bytes rather than nine: the zero-chunk path requires an immediate CRLF, then permissive fallback retains terminal-chunk and trailer text.\n"
        "ChunkedDataReader(..., raise_exceptions=True) instead raises Incorrect CRLF after length header of 0.\n"
        "This disagreement does not change the known nine-byte expectation. RFC 9112 section 7.1 permits last-chunk extensions and trailer fields.\n"
        "Sources: https://www.rfc-editor.org/rfc/rfc9112.html#section-7.1 and "
        "https://github.com/webrecorder/warcio/blob/" + COMMIT + "/warcio/bufferedreaders.py#L334-L343\n")
    print(f"Extracted {len(SYMBOLS)} unchanged upstream WARC literals and wrote {sum(f['fixture'].endswith('.warc') for f in derived)} derived WARC files; {len(rows)} expected records")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--source-dir", type=Path)
    source.add_argument("--source-json-stdin", action="store_true")
    parser.add_argument("--output-root", type=Path, default=ROOT)
    args = parser.parse_args()
    if args.source_json_stdin:
        encoded = json.load(sys.stdin)
        files = {name: base64.b64decode(encoded[name], validate=True) for name in PINNED}
    elif args.source_dir:
        files = {name: (args.source_dir / name).read_bytes() for name in PINNED}
    else:
        files = {}
        for name in PINNED:
            with urllib.request.urlopen(BASE + name, timeout=30) as response:
                files[name] = response.read()
    build(files, args.output_root)


if __name__ == "__main__":
    main()
