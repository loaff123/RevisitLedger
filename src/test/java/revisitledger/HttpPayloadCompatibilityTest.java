package revisitledger;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.*;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class HttpPayloadCompatibilityTest {
    private static final Path ROOT=Path.of("tests/fixtures/derived");
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    private static HttpResponse strict(byte[] b) throws IOException {
        return HttpResponse.parseStrictly(Channels.newChannel(new ByteArrayInputStream(b)));
    }
    @Test void chunkExtensionsAndTrailersAreExcludedFromPayload() throws Exception {
        assertTrue(Files.isRegularFile(ROOT.resolve("chunked-response.warc")),"independent chunked fixture missing");
        try(WarcReader r=new WarcReader(ROOT.resolve("chunked-response.warc"))) {
            WarcRecord record=r.next().orElseThrow();
            byte[] payload=HttpResponse.parseStrictly(record.body()).body().stream().readAllBytes();
            assertArrayEquals(ascii("some\ntext"),payload);
            assertTrue(r.next().isEmpty());
        }
    }
    @Test void contentEncodingBytesRemainOpaque() throws Exception {
        assertTrue(Files.isRegularFile(ROOT.resolve("content-encoded-response.warc")),"independent encoded fixture missing");
        try(WarcReader r=new WarcReader(ROOT.resolve("content-encoded-response.warc"))) {
            WarcRecord record=r.next().orElseThrow();
            HttpResponse http=HttpResponse.parseStrictly(record.body());
            assertEquals("gzip",http.headers().sole("Content-Encoding").orElseThrow());
            byte[] payload=http.body().stream().readAllBytes();
            assertArrayEquals(Files.readAllBytes(ROOT.resolve("content-encoded-entity.bin")),payload);
            try(InputStream decoded=new GZIPInputStream(new ByteArrayInputStream(payload))) {
                assertArrayEquals(ascii("some\ntext"),decoded.readAllBytes());
            }
            assertTrue(r.next().isEmpty());
        }
    }
    @Test void headerOnlyRevisitDoesNotConsumeOmittedContent() throws Exception {
        assertTrue(Files.isRegularFile(ROOT.resolve("header-only-revisit.warc")),"independent header-only fixture missing");
        byte[] revisit=Files.readAllBytes(ROOT.resolve("header-only-revisit.warc"));
        byte[] sentinel=Files.readAllBytes(Path.of("tests/fixtures/upstream-warcio/RESOURCE_RECORD.warc"));
        ByteArrayOutputStream corpus=new ByteArrayOutputStream(); corpus.write(revisit);corpus.write(sentinel);
        try(WarcReader r=new WarcReader(new ByteArrayInputStream(corpus.toByteArray()))) {
            WarcRevisit record=assertInstanceOf(WarcRevisit.class,r.next().orElseThrow());
            HttpResponse http=record.http();
            assertEquals("9",http.headers().sole("Content-Length").orElseThrow());
            assertEquals(0,http.body().size());
            assertEquals(-1,http.body().stream().read());
            WarcResource resource=assertInstanceOf(WarcResource.class,r.next().orElseThrow());
            assertArrayEquals(ascii("some\ntext"),resource.body().stream().readAllBytes());
            assertTrue(r.next().isEmpty());
        }
    }
    @Test void malformedChunkFailsOnStrictPath() throws Exception {
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\nbody\r\n0\r\n\r\n"));
        assertThrows(IOException.class,()->http.body().stream().readAllBytes());
    }
    @Test void unterminatedTrailerFailsOnStrictPath() throws Exception {
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\nTrailer: value\r\n"));
        assertThrows(IOException.class,()->http.body().stream().readAllBytes());
    }
    @Test void strictParserRejectsBufferedTailAfterLastChunk() throws Exception {
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n\r\ngarbage"));
        assertThrows(IOException.class,()->http.body().stream().readAllBytes());
    }
    @Test void truncatedChunkFailsOnStrictPath() throws Exception {
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\nsome"));
        assertThrows(IOException.class,()->http.body().stream().readAllBytes());
    }
    @Test void strictParserAloneDoesNotRejectDuplicateConflictingLengths() throws Exception {
        // Characterization: a future adapter MUST reject before trusting this body.
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 9\r\n\r\nabcdefghi"));
        assertEquals(2,http.headers().all("Content-Length").size());
        assertArrayEquals(ascii("abc"),http.body().stream().readAllBytes());
    }
    @Test void strictParserAloneDoesNotRejectUnsupportedTransferCoding() throws Exception {
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\nContent-Length: 6\r\n\r\nopaque"));
        assertEquals("gzip",http.headers().sole("Transfer-Encoding").orElseThrow());
        assertArrayEquals(ascii("opaque"),http.body().stream().readAllBytes());
    }
    @Test void strictParserAloneDoesNotRejectTrailingEntityBytes() throws Exception {
        HttpResponse http=strict(ascii("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcdef"));
        assertArrayEquals(ascii("abc"),http.body().stream().readAllBytes());
    }
}
