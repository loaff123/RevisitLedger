"""Project independent JSON facts to TSV for Java; no archive or Java parser code."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ('fixture', 'ordinal', 'record_count', 'record_type', 'raw_target', 'raw_date', 'block_length', 'payload_length', 'sha1_hex', 'sha256_hex', 'version', 'record_id', 'raw_profile', 'refers_to', 'refers_to_target', 'refers_to_date')

def render():
    rows = json.loads((ROOT/'tests/fixtures/derived/expected.json').read_text())
    lines = ['# ' + '\t'.join(FIELDS)]
    for row in rows:
        values = ['-' if row[field] is None else str(row[field]) for field in FIELDS]
        if any('\t' in value or '\n' in value or '\r' in value for value in values):
            raise ValueError('TSV projection only supports the fixed controlled fixture metadata')
        lines.append('\t'.join(values))
    return '\n'.join(lines) + '\n'

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); args=parser.parse_args()
    path = ROOT/'tests/fixtures/derived/expected.tsv'
    text=render()
    if args.check:
        assert path.read_text()==text, 'stale expected TSV'
        print('Independent expectation projection matches JSON')
    else: path.write_text(text)
