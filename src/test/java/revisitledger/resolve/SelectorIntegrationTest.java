package revisitledger.resolve;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import revisitledger.index.*;import revisitledger.ingest.*;import revisitledger.model.*;
class SelectorIntegrationTest {
 @TempDir Path temp;
 @Test void unchangedUpstreamReferenceNeverUsesEqualDigestResponse()throws Exception{
  try(var index=RecordIndex.create(temp,64L<<20)){var scan=new ArchiveScanner().scan(Path.of("tests/fixtures/upstream-warcio"),List.of("REVISIT_RECORD_1.warc","RESPONSE_RECORD.warc"),ScanLimits.defaults(),index);index.seal(scan);
   var result=new SelectorResolver().resolve(index,new RecordKey("REVISIT_RECORD_1.warc",1),ChoiceSet.empty(index.corpusFingerprint()));assertEquals("missing_in_corpus",result.status());assertTrue(result.optionalChosen().isEmpty());
   try(var c=index.byId("<urn:uuid:12345678-feb0-11e6-8f83-68a86d1772ce>")){assertTrue(c.next());assertTrue(c.next());assertFalse(c.next());}
  }
 }
 @Test void derivedClosedPairResolves()throws Exception{
  try(var index=RecordIndex.create(temp,64L<<20)){index.seal(new ArchiveScanner().scan(Path.of("tests/fixtures/derived/closed-pair"),List.of("revisit.warc","response.warc"),ScanLimits.defaults(),index));
   var result=new SelectorResolver().resolve(index,new RecordKey("revisit.warc",1),ChoiceSet.empty(index.corpusFingerprint()));assertEquals("resolved",result.status());assertEquals(Optional.of(new RecordKey("response.warc",1)),result.optionalChosen());
  }
 }
}
