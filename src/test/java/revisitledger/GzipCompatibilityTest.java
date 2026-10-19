package revisitledger;

import org.junit.jupiter.api.Test;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import java.io.*;
import java.util.Arrays;
import java.util.zip.*;
import java.nio.file.*;
import org.netpreserve.jwarc.*;
import static org.junit.jupiter.api.Assertions.*;

class GzipCompatibilityTest {
    static byte[] member(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(payload); }
        return out.toByteArray();
    }
    static byte[] join(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(a); out.write(b); return out.toByteArray();
    }
    static byte[] decode(byte[] bytes) throws IOException {
        try (InputStream in = GzipCompressorInputStream.builder().setInputStream(new ByteArrayInputStream(bytes)).setDecompressConcatenated(true).get()) { return in.readAllBytes(); }
    }
    @Test void validSingleAndConcatenatedMembersAgree() throws Exception {
        byte[] a={1,2,3}, b={4,5,6};
        assertArrayEquals(join(a,b),decode(member(join(a,b))));
        assertArrayEquals(join(a,b),decode(join(member(a),member(b))));
    }
    @Test void wholeFileAndRecordMembersParseTheSameRecords() throws Exception {
        byte[] a=Files.readAllBytes(Path.of("tests/fixtures/upstream-warcio/RESPONSE_RECORD.warc"));
        byte[] b=Files.readAllBytes(Path.of("tests/fixtures/upstream-warcio/REVISIT_RECORD_1.warc"));
        for(byte[] compressed:new byte[][]{member(join(a,b)),join(member(a),member(b))}) {
            try(InputStream gzip=GzipCompressorInputStream.builder().setInputStream(new ByteArrayInputStream(compressed)).setDecompressConcatenated(true).get();
                WarcReader reader=new WarcReader(gzip)) {
                assertInstanceOf(WarcResponse.class,reader.next().orElseThrow());
                assertInstanceOf(WarcRevisit.class,reader.next().orElseThrow());
                assertTrue(reader.next().isEmpty());
            }
        }
    }
    @Test void corruptTailCannotCertifyAValidWarcPrefix() throws Exception {
        byte[] a=Files.readAllBytes(Path.of("tests/fixtures/upstream-warcio/RESPONSE_RECORD.warc"));
        byte[] corrupted=join(member(a),new byte[]{'x','y'});
        assertThrows(IOException.class,()->{
            try(InputStream gzip=GzipCompressorInputStream.builder().setInputStream(new ByteArrayInputStream(corrupted)).setDecompressConcatenated(true).get();
                WarcReader reader=new WarcReader(gzip)) {
                while(reader.next().isPresent()) { /* Drain to EOF, not merely one valid record. */ }
                gzip.readAllBytes();
            }
        });
    }
    @Test void crcCorruptionFails() throws Exception {
        byte[] bytes=member(new byte[]{1,2,3}); bytes[bytes.length-8]^=1;
        assertThrows(IOException.class,()->decode(bytes));
    }
    @Test void truncatedTrailerFails() throws Exception {
        byte[] bytes=member(new byte[]{1,2,3});
        for(int missing=1;missing<=8;missing++) {
            byte[] truncated=Arrays.copyOf(bytes,bytes.length-missing);
            assertThrows(IOException.class,()->decode(truncated));
        }
    }
    @Test void trailingGarbageFails() throws Exception {
        byte[] bytes=join(member(new byte[]{1,2,3}),new byte[]{'x','y','z'});
        assertThrows(IOException.class,()->decode(bytes));
    }
    @Test void trailingNulFails() throws Exception {
        assertThrows(IOException.class,()->decode(join(member(new byte[]{1,2,3}),new byte[]{0})));
    }
    @Test void corruptSecondMemberFailsAfterValidPrefix() throws Exception {
        byte[] bad=member(new byte[]{4,5,6}); bad[bad.length-8]^=1;
        assertThrows(IOException.class,()->decode(join(member(new byte[]{1,2,3}),bad)));
    }
    @Test void incompleteSecondHeaderFailsAfterValidPrefix() throws Exception {
        assertThrows(IOException.class,()->decode(join(member(new byte[]{1,2,3}),new byte[]{0x1f,(byte)0x8b})));
    }
}
