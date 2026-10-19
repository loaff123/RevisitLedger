package revisitledger.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import revisitledger.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static revisitledger.ingest.IngestFixtures.*;

class IngestionIntegrationTest {
 @TempDir Path dir;
 @Test void productionScannerAgreesWithEveryIndependentFixture()throws Exception {
  var expected=new TreeMap<RecordKey,String[]>(Comparator.comparing(RecordKey::relativePath).thenComparingLong(RecordKey::ordinal));
  for(String line:Files.readAllLines(Path.of("tests/fixtures/derived/expected.tsv"))){if(line.startsWith("#"))continue;String[] row=line.split("\t",-1);expected.put(new RecordKey(row[0],Long.parseLong(row[1])),row);}
  var files=expected.keySet().stream().map(RecordKey::relativePath).distinct().toList();var sink=new Sink();
  assertEquals(new ScanSummary(13,14,true),new ArchiveScanner().scan(Path.of("tests/fixtures"),files,ScanLimits.defaults(),sink));
  assertTrue(sink.findings.isEmpty(),()->sink.findings.toString());
  for(var record:sink.records){String[] row=expected.get(record.key());assertNotNull(row);assertEquals(row[3],record.type());assertEquals(row[4],record.header("WARC-Target-URI").orElse("-"));assertEquals(row[5],record.header("WARC-Date").orElse("-"));assertEquals(row[10],record.version());
   if(row[7].equals("-"))assertTrue(record.payload().isEmpty());else{var p=record.payload().orElseThrow();assertEquals(Long.parseLong(row[7]),p.length());assertEquals(row[8],p.hexDigests().get("sha1"));assertEquals(row[9],p.hexDigests().get("sha256"));}
  }
 }
 @Test void wholeFileAndPerRecordGzipProduceIdenticalFacts()throws Exception {
  byte[] a=record("resource",ascii("a"),""),b=record("resource",ascii("b"),"");var scanner=new ArchiveScanner();var before=new Sink();var after=new Sink();
  Files.write(dir.resolve("same.warc"),gzip(cat(a,b)));assertTrue(scanner.scan(dir,List.of("same.warc"),ScanLimits.defaults(),before).complete());
  Files.write(dir.resolve("same.warc"),cat(gzip(a),gzip(b)));assertTrue(scanner.scan(dir,List.of("same.warc"),ScanLimits.defaults(),after).complete());assertEquals(before.records,after.records);
 }
 @Test void corruptGzipAfterValidRecordNeverCertifiesFile()throws Exception {
  byte[] record=record("resource",ascii("a"),"");Files.write(dir.resolve("a.warc.gz"),cat(gzip(record),ascii("tail")));var sink=new Sink();
  assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc.gz"),ScanLimits.defaults(),sink).complete());assertFalse(sink.files.get(0).complete());assertTrue(sink.has("input_incomplete"));
 }
 @Test void fileOrderingDoesNotChangeFactsOrFingerprints()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("a"),""));Files.write(dir.resolve("b.warc"),record("resource",ascii("b"),""));var a=new Sink();var b=new Sink();var scanner=new ArchiveScanner();
  assertTrue(scanner.scan(dir,List.of("a.warc","b.warc"),ScanLimits.defaults(),a).complete());assertTrue(scanner.scan(dir,List.of("b.warc","a.warc"),ScanLimits.defaults(),b).complete());assertEquals(a.records,b.records);assertEquals(a.files,b.files);assertEquals(a.findings,b.findings);
 }
 @Test void sixteenMiBResourceStreamsToExpectedHashWithNoScratchFiles()throws Exception {
  long length=16L<<20;Path path=dir.resolve("large.warc");var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[8192];new Random(1234).nextBytes(bytes);
  try(var output=Files.newOutputStream(path)){
   output.write(ascii("WARC/1.1\r\nWARC-Type: resource\r\nWARC-Record-ID: <urn:large>\r\nWARC-Date: 2020-01-01T00:00:00Z\r\nWARC-Target-URI: https://example/\r\nContent-Length: "+length+"\r\n\r\n"));
   for(long i=0;i<length;i+=bytes.length){output.write(bytes);digest.update(bytes);}output.write(ascii("\r\n\r\n"));
  }
  var sink=new Sink();assertTrue(new ArchiveScanner().scan(dir,List.of("large.warc"),new ScanLimits(length+1024,1,length,65536,1),sink).complete());
  var payload=sink.records.get(0).payload().orElseThrow();assertEquals(length,payload.length());assertEquals(HexFormat.of().formatHex(digest.digest()),payload.hexDigests().get("sha256"));try(var paths=Files.list(dir)){assertEquals(1,paths.count());}
 }
 @Test void recordCountBudgetAllowsExactEndOfCorpus()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("a"),""));assertTrue(new ArchiveScanner().scan(dir,List.of("a.warc"),new ScanLimits(10000,1,10000,65536,1),new Sink()).complete());
 }
 @Test void malformedRequiredWarcFieldsNeverUseParserDefaults()throws Exception {
  String original=new String(record("resource",ascii("a"),""),java.nio.charset.StandardCharsets.ISO_8859_1);
  for(String broken:List.of(original.replace("Content-Length: 1\r\n",""),original.replace("Content-Length: 1","Content-Length: -1"),original.replace("Content-Length: 1","Content-Length: 999999999999999999999999"),original.replace("WARC/1.1","WARC/1.9"),original.replace("WARC-Type: resource\r\n",""),original.substring(0,original.length()-2))){
   Files.write(dir.resolve("a.warc"),ascii(broken));var sink=new Sink();assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),sink).complete());assertTrue(sink.records.isEmpty());
  }
 }
 @Test void malformedHttpIsRetainedButScanIncomplete()throws Exception {
  Files.write(dir.resolve("a.warc"),record("response",http("Content-Length: 1\r\n","extra"),"Content-Type: application/http; msgtype=response\r\n"));var sink=new Sink();assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),sink).complete());assertEquals(1,sink.records.size());assertTrue(sink.records.get(0).payload().isEmpty());assertTrue(sink.has("http_framing"));
 }
 @Test void digestMismatchIsSeparateFromCompletedScan()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("other"),"WARC-Payload-Digest: sha1:0fa09f05a9dc781d6ef76455951d6f3271aa938e\r\n"));var sink=new Sink();assertTrue(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),sink).complete());assertTrue(sink.has("digest_mismatch"));assertTrue(sink.records.get(0).payload().orElseThrow().complete());
 }
 @Test void interruptedScanCannotReturnComplete()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("a"),""));Thread.currentThread().interrupt();try{assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),new Sink()).complete());assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
 }
 @Test void sinkFailuresRemainCheckedIoFailures()throws Exception {
  Files.write(dir.resolve("a.warc"),record("resource",ascii("a"),"WARC-Payload-Digest: md5:unsupported\r\n"));
  RecordSink broken=new RecordSink(){public void record(RecordFacts r){}public void file(FileFacts f){}public void finding(Finding f)throws IOException{throw new IOException("sink unavailable");}};
  assertThrows(IOException.class,()->new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),broken));
  Files.write(dir.resolve("a.warc"),ascii("broken"));
  assertThrows(IOException.class,()->new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),broken));
 }
 @Test void malformedUtf8CannotCollapseDistinctSelectors()throws Exception {
  for(int invalid:new int[]{0x80,0x81}){
   byte[] b=record("resource",ascii("a"),"X-Test: value\r\n");String text=new String(b,java.nio.charset.StandardCharsets.ISO_8859_1);int offset=text.indexOf("https://EXAMPLE.com/a%2fb/")+"https://EXAMPLE.com/".length();b[offset]=(byte)invalid;
   Files.write(dir.resolve("a.warc"),b);var sink=new Sink();assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),sink).complete());assertTrue(sink.records.isEmpty());
  }
 }
 @Test void warcVersionMustMatchExactSupportedLiteral()throws Exception {
  for(String version:List.of("WARC/4294967297.1","WARC/0001.01")){
   String s=new String(record("resource",ascii("a"),""),java.nio.charset.StandardCharsets.ISO_8859_1).replace("WARC/1.1",version);Files.write(dir.resolve("a.warc"),ascii(s));assertFalse(new ArchiveScanner().scan(dir,List.of("a.warc"),ScanLimits.defaults(),new Sink()).complete());
  }
 }
 @Test void replacedParentDirectoryCannotEscapeRootBetweenFiles()throws Exception {
  Path root=Files.createDirectory(dir.resolve("root")),sub=Files.createDirectory(root.resolve("sub")),outside=Files.createDirectory(dir.resolve("outside"));
  Files.write(root.resolve("a.warc"),record("resource",ascii("a"),""));Files.write(sub.resolve("b.warc"),record("resource",ascii("b"),""));Files.write(outside.resolve("b.warc"),record("resource",ascii("private"),""));var sink=new Sink();
  RecordSink mutator=new RecordSink(){public void record(RecordFacts r)throws IOException{sink.record(r);if(r.key().relativePath().equals("a.warc")){Files.move(sub,root.resolve("old"));Files.createSymbolicLink(sub,outside);}}public void file(FileFacts f){sink.file(f);}public void finding(Finding f){sink.finding(f);}};
  assertFalse(new ArchiveScanner().scan(root,List.of("a.warc","sub/b.warc"),ScanLimits.defaults(),mutator).complete());assertEquals(1,sink.records.size());assertFalse(sink.files.get(1).complete());
 }
}
