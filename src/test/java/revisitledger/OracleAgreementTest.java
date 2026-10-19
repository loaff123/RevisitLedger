package revisitledger;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OracleAgreementTest {
    @Test void everyRecordAgreesWithIndependentPythonKnownBytes() throws Exception {
        Path expectations=Path.of("tests/fixtures/derived/expected.tsv");
        assertTrue(Files.isRegularFile(expectations),"project the independent known-byte expectations first");
        Map<String,List<String[]>> byFile=new TreeMap<>();
        for(String line:Files.readAllLines(expectations)) {
            if(line.startsWith("#"))continue;
            String[] row=line.split("\t",-1);
            assertEquals(16,row.length);
            byFile.computeIfAbsent(row[0],k->new ArrayList<>()).add(row);
        }
        int total=0;
        for(var entry:byFile.entrySet()) {
            try(WarcReader reader=new WarcReader(Path.of("tests/fixtures").resolve(entry.getKey()))) {
                int ordinal=0;
                for(String[] row:entry.getValue()) {
                    WarcRecord record=reader.next().orElseThrow(); ordinal++; total++;
                    assertEquals(Integer.parseInt(row[1]),ordinal,entry.getKey());
                    assertEquals(row[3],record.type(),entry.getKey());
                    assertEquals(row[4],record.headers().sole("WARC-Target-URI").orElse("-"));
                    assertEquals(row[5],record.headers().sole("WARC-Date").orElse("-"));
                    assertEquals(Long.parseLong(row[6]),record.body().size());
                    assertEquals(row[10],record.version().toString());
                    assertEquals(row[11],record.headers().sole("WARC-Record-ID").orElse("-"));
                    assertEquals(row[12],record.headers().sole("WARC-Profile").orElse("-"));
                    assertEquals(row[13],record.headers().sole("WARC-Refers-To").orElse("-"));
                    assertEquals(row[14],record.headers().sole("WARC-Refers-To-Target-URI").orElse("-"));
                    assertEquals(row[15],record.headers().sole("WARC-Refers-To-Date").orElse("-"));
                    if(record instanceof WarcRevisit) {
                        assertEquals("-",row[7]);assertEquals("-",row[8]);assertEquals("-",row[9]);
                        record.body().stream().readAllBytes();
                    } else {
                        byte[] payload=record instanceof WarcResponse
                            ? HttpResponse.parseStrictly(record.body()).body().stream().readAllBytes()
                            : record.body().stream().readAllBytes();
                        assertEquals(Long.parseLong(row[7]),payload.length,entry.getKey());
                        assertEquals(row[8],HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(payload)),entry.getKey());
                        assertEquals(row[9],HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),entry.getKey());
                    }
                }
                assertTrue(reader.next().isEmpty(),entry.getKey());
                assertEquals(Integer.parseInt(entry.getValue().get(0)[2]),ordinal);
            }
        }
        assertEquals(13,byFile.size());assertEquals(14,total);
    }
}
