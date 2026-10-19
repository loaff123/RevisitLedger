package revisitledger;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.*;
import java.nio.file.*;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class FixtureCompatibilityTest {
    private static final Path ROOT = Path.of("tests", "fixtures", "upstream-warcio");

    @Test void upstreamResponseHasExactBlockAndPayload() throws Exception {
        Path path = ROOT.resolve("RESPONSE_RECORD.warc");
        assertTrue(Files.isRegularFile(path), "extract and verify the pinned upstream response fixture first");
        try (WarcReader reader = new WarcReader(path)) {
            WarcRecord record = reader.next().orElseThrow();
            assertInstanceOf(WarcResponse.class, record);
            assertEquals(97, record.body().size());
            WarcResponse response = (WarcResponse) record;
            assertArrayEquals("some\ntext".getBytes(StandardCharsets.US_ASCII),
                    response.http().body().stream().readAllBytes());
            assertTrue(reader.next().isEmpty());
        }
    }

    @Test void upstreamRevisitHasEmptyBlock() throws Exception {
        Path path = ROOT.resolve("REVISIT_RECORD_1.warc");
        assertTrue(Files.isRegularFile(path), "extract and verify the pinned upstream revisit fixture first");
        try (WarcReader reader = new WarcReader(path)) {
            WarcRecord record = reader.next().orElseThrow();
            assertInstanceOf(WarcRevisit.class, record);
            assertEquals(0, record.body().size());
            assertEquals(-1, record.body().stream().read());
            assertTrue(reader.next().isEmpty());
        }
    }
}
