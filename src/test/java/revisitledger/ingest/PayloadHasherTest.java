package revisitledger.ingest;
import org.junit.jupiter.api.*;import java.io.*;import java.nio.file.*;import revisitledger.model.*;
import static org.junit.jupiter.api.Assertions.*;import static revisitledger.ingest.IngestFixtures.*;
class PayloadHasherTest {
 PayloadEvidence http(byte[] b)throws Exception{return PayloadHasher.http(new ByteArrayInputStream(b),b.length,65536);}
 @Test void resourceHashesExactBytes()throws Exception {var e=PayloadHasher.resource(new ByteArrayInputStream(ascii("some\ntext")),9);assertEquals(9,e.length());assertEquals("0fa09f05a9dc781d6ef76455951d6f3271aa938e",e.hexDigests().get("sha1"));assertTrue(e.complete());}
 @Test void duplicateAndConflictingHttpLengthsRejected()throws Exception {for(String n:java.util.List.of("3","9"))assertThrows(IOException.class,()->http(IngestFixtures.http("Content-Length: 3\r\nContent-Length: "+n+"\r\n","abcdefghi")));}
 @Test void unsupportedTransferCodingAndMixedFramingRejected()throws Exception {for(String h:java.util.List.of("Transfer-Encoding: gzip\r\nContent-Length: 6\r\n","Transfer-Encoding: gzip, chunked\r\n","Transfer-Encoding: chunked\r\nContent-Length: 6\r\n"))assertThrows(IOException.class,()->http(IngestFixtures.http(h,"opaque")));}
 @Test void leftoverAndShortHttpEntitiesRejected()throws Exception {assertThrows(IOException.class,()->http(IngestFixtures.http("Content-Length: 3\r\n","abcdef")));assertThrows(IOException.class,()->http(IngestFixtures.http("Content-Length: 9\r\n","abc")));}
 @Test void chunkExtensionsAndTrailersHashNineBytes()throws Exception {var e=http(IngestFixtures.http("Transfer-Encoding: chunked\r\n","4;name=one\r\nsome\r\n5;quoted=\"two words\"\r\n\ntext\r\n0;done=yes\r\nX-Trailer: yes\r\n\r\n"));assertEquals(9,e.length());assertEquals(PayloadHasher.resource(new ByteArrayInputStream(ascii("some\ntext")),9),e);}
 @Test void chunkGarbageMalformedSizesAndTrailerFail()throws Exception {for(String b:java.util.List.of("1\r\na\r\n0\r\n\r\ngarbage","Z\r\na\r\n0\r\n\r\n","1\r\na\r\n0\r\nTrailer: unterminated\r\n","1;bad=\r\na\r\n0\r\n\r\n"))assertThrows(IOException.class,()->http(IngestFixtures.http("Transfer-Encoding: chunked\r\n",b)));}
 @Test void contentEncodingRetainsEncodedEntity()throws Exception {byte[] b=Files.readAllBytes(Path.of("tests/fixtures/derived/content-encoded-entity.bin"));var e=http(cat(ascii("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "+b.length+"\r\n\r\n"),b));assertEquals(29,e.length());assertEquals(PayloadHasher.resource(new ByteArrayInputStream(b),b.length),e);}
}
