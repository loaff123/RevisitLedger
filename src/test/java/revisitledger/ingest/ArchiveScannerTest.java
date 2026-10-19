package revisitledger.ingest;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;
import revisitledger.model.*;
import static org.junit.jupiter.api.Assertions.*;import static revisitledger.ingest.IngestFixtures.*;
class ArchiveScannerTest {
 @TempDir Path dir;
 Sink sink=new Sink();
 ScanSummary scan(byte[] b)throws Exception {Files.write(dir.resolve("a.warc"),b);return new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),sink);}
 @Test void exactUriIsNotNormalized()throws Exception {
  assertTrue(scan(record("resource",ascii("x"),"")).complete());
  assertEquals("https://EXAMPLE.com/a%2fb/",sink.records.get(0).header("WARC-Target-URI").orElseThrow());
  assertEquals(new RecordKey("a.warc",1),sink.records.get(0).key());
 }
 @Test void duplicateSingletonIsBlocked()throws Exception {
  assertFalse(scan(record("resource",ascii("x"),"warc-target-uri: https://other/\r\n")).complete());
  assertTrue(sink.has("duplicate_singleton"));
 }
 @Test void shortBlockIsIncomplete()throws Exception {
  byte[] b=record("resource",ascii("body"),"");assertFalse(scan(Arrays.copyOf(b,b.length-7)).complete());
  assertTrue(sink.has("input_incomplete"));assertTrue(sink.records.isEmpty());
 }
 @Test void binaryWarcTextDoesNotResync()throws Exception {
  byte[] b=ascii("\u0000WARC/1.1\r\nWARC-Type: revisit\r\n\u00ff");
  assertTrue(scan(record("resource",b,"")).complete());assertEquals(1,sink.records.size());assertEquals(b.length,sink.records.get(0).payload().orElseThrow().length());
 }
 @Test void fractionalTimestampPreservedAndUnsupportedPrecisionReported()throws Exception {
  byte[] b=record("resource",ascii("x"),"WARC-Refers-To-Date: 2020-01-01T00:00:00.123456789Z\r\n");
  assertTrue(scan(b).complete());assertFalse(sink.has("unsupported_precision"));
  assertEquals("2020-01-01T00:00:00.123456789Z",sink.records.get(0).header("WARC-Refers-To-Date").orElseThrow());
  sink=new Sink();assertTrue(scan(record("resource",ascii("x"),"WARC-Refers-To-Date: 2020-01-01\r\n")).complete());assertTrue(sink.has("unsupported_precision"));
 }
 @Test void limitsBlockBeforeLargeAllocations()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("body"),""));
  for(var limits:List.of(new ScanLimits(10000,10,3,65536,1),new ScanLimits(10000,10,10000,20,1),new ScanLimits(10,10,10000,65536,1))){
   var s=new Sink();assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),limits,s).complete());assertTrue(s.has("resource_limit"));
  }
 }
 @Test void totalRecordBudgetAppliesAcrossFiles()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("a"),""));Files.write(dir.resolve("b.warc"),record("resource",ascii("b"),""));
  var summary=new ArchiveScanner().scan(dir,List.of("b.warc","a.warc"),new ScanLimits(10000,1,10000,65536,1),sink);
  assertFalse(summary.complete());assertEquals(1,summary.recordCount());assertTrue(sink.has("resource_limit"));
 }
 @Test void ordinaryInputMutationInvalidatesCompletedRecords()throws Exception {
  Path p=dir.resolve("a.warc");byte[] b=record("resource",ascii("before"),"");Files.write(p,b);
  RecordSink mutator=new RecordSink(){public void record(RecordFacts r)throws java.io.IOException{sink.record(r);byte[] changed=b.clone();changed[changed.length-5]^=1;Files.write(p,changed);}public void file(FileFacts f){sink.file(f);}public void finding(Finding f){sink.finding(f);}};
  assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),mutator).complete());assertTrue(sink.has("input_changed"));
 }
 @Test void revisitOmittedEntityDoesNotConsumeNextRecord()throws Exception {
  byte[] a=Files.readAllBytes(Path.of("tests/fixtures/derived/header-only-revisit.warc"));
  assertTrue(scan(cat(a,record("resource",ascii("x"),""))).complete());assertEquals(2,sink.records.size());assertTrue(sink.records.get(0).payload().isEmpty());
 }
 @Test void unsupportedProvidersDoNotExposeConcreteEvidence()throws Exception {
  for(String extra:List.of("WARC-Truncated: length\r\n","WARC-Segment-Number: 1\r\n")){
   sink=new Sink();assertTrue(scan(record("resource",ascii("body"),extra)).complete());assertTrue(sink.records.get(0).payload().isEmpty());assertTrue(sink.has("unsupported_provider"));
  }
 }
 @Test void validCorpusHashesRemainUnchanged()throws Exception {
  Path p=dir.resolve("a.warc");byte[] b=record("resource",ascii("body"),"");assertTrue(scan(b).complete());assertArrayEquals(b,Files.readAllBytes(p));assertEquals(64,sink.files.get(0).sha256().length());assertTrue(sink.files.get(0).complete());
 }
 @Test void nonRfc3339MidnightIsNotSilentlyNormalized()throws Exception {
  assertTrue(scan(record("resource",ascii("x"),"WARC-Refers-To-Date: 2020-01-01T24:00:00Z\r\n")).complete());assertTrue(sink.has("unsupported_precision"));
 }
}
