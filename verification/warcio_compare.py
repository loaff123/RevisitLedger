"""Read-only comparator: pin expected semantic differences, never change known bytes."""
import io,json,hashlib
from pathlib import Path
from warcio.archiveiterator import ArchiveIterator
ROOT=Path(__file__).resolve().parents[1]
expected=json.loads((ROOT/'tests/fixtures/derived/expected.json').read_text())
byfile={}
for row in expected: byfile.setdefault(row['fixture'],[]).append(row)
observed=[]
for name,rows in byfile.items():
    records=ArchiveIterator(io.BytesIO((ROOT/'tests/fixtures'/name).read_bytes()))
    count=0
    for record,want in zip(records,rows,strict=True):
        count+=1
        assert record.rec_type==want['record_type']
        assert record.rec_headers.get_header('WARC-Target-URI')==want['raw_target']
        assert record.rec_headers.get_header('WARC-Date')==want['raw_date']
        assert int(record.rec_headers.get_header('Content-Length'))==want['block_length']
        content=record.content_stream().read()
        status='agrees'
        if name=='derived/content-encoded-response.warc':
            assert content==b'some\ntext'; status='expected Content-Encoding decode: 29 raw entity bytes become 9'
        elif name=='derived/chunked-response.warc':
            assert content==b'some\ntext0;done=yes\r\nChecksum: ignored\r\n\r\n'
            status='warcio1.8.1 known valid-trailer limitation: expected payload9, returned42'
        elif want['payload_hex'] is None: assert content==b''
        else: assert content==bytes.fromhex(want['payload_hex'])
        observed.append({'fixture':name,'ordinal':count,'record_type':record.rec_type,'returned_content_bytes':len(content),'status':status})
    assert count==len(rows)
print(json.dumps({'warcio_version':'1.8.1','record_count':len(observed),'observations':observed},indent=2,sort_keys=True))
