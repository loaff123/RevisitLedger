package revisitledger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent CLI integration probes, including previously approved byte/graph invariants. */
class ReviewFinalCliProbeTest {
 @TempDir Path tmp;
 record Result(int code,String out,String err){}
 Result run(String...args){var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();int code=Cli.run(args,new PrintStream(out),new PrintStream(err));return new Result(code,out.toString(StandardCharsets.UTF_8),err.toString(StandardCharsets.UTF_8));}
 Path corpus()throws IOException{Path root=Files.createDirectory(tmp.resolve("corpus"));for(String name:List.of("A.warc","B.warc","C.warc"))Files.copy(Path.of("tests/fixtures/derived/whole-file",name),root.resolve(name));return root;}
 Path list(String name,String text)throws IOException{return Files.writeString(tmp.resolve(name),text);}
 String[] audit(Path root,Path files,Path report,String...extra){var a=new ArrayList<>(List.of("audit","--root",root.toString(),"--files",files.toString(),"--json",report.toString(),"--scratch",tmp.toString()));a.addAll(List.of(extra));return a.toArray(String[]::new);}
 String[] plan(Path root,Path files,Path seeds,Path report,String...extra){var a=new ArrayList<>(List.of(audit(root,files,report,extra)));a.set(0,"plan");a.addAll(List.of("--seeds",seeds.toString()));return a.toArray(String[]::new);}
 byte[] gzip(byte[] bytes)throws IOException{var out=new ByteArrayOutputStream();try(var g=new GZIPOutputStream(out)){g.write(bytes);}return out.toByteArray();}
 byte[] concat(byte[] a,byte[] b){byte[] all=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
 byte[] response(String http)throws IOException{
  byte[] block=http.getBytes(StandardCharsets.ISO_8859_1);
  return ("WARC/1.1\r\nWARC-Type: response\r\nWARC-Record-ID: <urn:uuid:00000000-0000-4000-8000-000000000999>\r\nWARC-Date: 2026-01-01T00:00:00Z\r\nWARC-Target-URI: https://example.invalid/\r\nContent-Type: application/http; msgtype=response\r\nContent-Length: "+block.length+"\r\n\r\n"+http+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
 }
 @Test void addedProviderFileActivatesUnrelatedMissingRevisit()throws Exception{
  Path root=corpus();String extra=Files.readString(root.resolve("A.warc")).replace("000000000201","000000000299").replace("000000000202","000000000999").replace("http://example.com/b","http://example.com/missing");
  Files.writeString(root.resolve("B.warc"),extra,StandardOpenOption.APPEND);Path report=tmp.resolve("blocked.json");
  Result result=run(plan(root,list("files.txt","A.warc\nB.warc\nC.warc\n"),list("seeds.txt","A.warc\n"),report));assertEquals(1,result.code);String json=Files.readString(report);
  assertTrue(json.contains("\"includedFiles\":[\"A.warc\",\"B.warc\",\"C.warc\"]"));assertTrue(json.contains("\"status\":\"missing_in_corpus\""));assertTrue(json.contains("\"complete\":false"));
 }
 @Test void sameDigestDoesNotOverrideConflictingExplicitSelectors()throws Exception{
  Path root=corpus();Files.writeString(root.resolve("A.warc"),Files.readString(root.resolve("A.warc")).replace("000000000202","000000000204"));Path report=tmp.resolve("conflict.json");
  Result result=run(plan(root,list("files.txt","A.warc\nB.warc\nC.warc\n"),list("seeds.txt","A.warc\n"),report));assertEquals(1,result.code);String json=Files.readString(report);
  assertTrue(json.contains("\"status\":\"selector_conflict\""));assertTrue(json.contains("\"includedFiles\":[\"A.warc\"]"));
 }
 @Test void gzipValidMemberThenCorruptMemberOrJunkCannotCertify()throws Exception{
  Path root=Files.createDirectory(tmp.resolve("corpus"));byte[] original=Files.readAllBytes(Path.of("tests/fixtures/upstream-warcio/RESOURCE_RECORD.warc"));byte[] good=gzip(original),bad=gzip(original);bad[bad.length-8]^=1;
  int n=0;for(byte[] bytes:List.of(concat(good,bad),concat(good,new byte[]{0,0,0}),concat(good,new byte[]{'W','A','R','C'}))){Files.write(root.resolve("bad.warc.gz"),bytes);Path report=tmp.resolve("gzip-"+(n++)+".json");assertEquals(2,run(audit(root,list("files.txt","bad.warc.gz\n"),report)).code);String json=Files.readString(report);assertTrue(json.contains("\"scanComplete\":false"));assertTrue(json.contains("\"complete\":false"));}
 }
 @Test void conflictingHttpLengthsAndUnsupportedTransferCodingsRemainIncomplete()throws Exception{
  Path root=Files.createDirectory(tmp.resolve("corpus"));int n=0;
  for(String framing:List.of("Content-Length: 3\r\nContent-Length: 4\r\n","Content-Length: 4\r\n","Transfer-Encoding: gzip, chunked\r\n","Transfer-Encoding: chunked\r\nContent-Length: 3\r\n")){
   Files.write(root.resolve("http.warc"),response("HTTP/1.1 200 OK\r\n"+framing+"\r\nabc"));Path report=tmp.resolve("http-"+(n++)+".json");assertEquals(2,run(audit(root,list("files.txt","http.warc\n"),report)).code);assertTrue(Files.readString(report).contains("\"scanComplete\":false"));
  }
 }
 @Test void contentEncodingRemainsOpaqueBytesInPayloadEvidence()throws Exception{
  Path root=Files.createDirectory(tmp.resolve("corpus"));String body="encoded-payload-not-decoded";Files.write(root.resolve("http.warc"),response("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "+body.length()+"\r\n\r\n"+body));Path report=tmp.resolve("encoded.json");
  assertEquals(0,run(audit(root,list("files.txt","http.warc\n"),report)).code);String json=Files.readString(report);assertTrue(json.contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.ISO_8859_1)))));assertFalse(json.contains(body));
 }
 @Test void hardLinkedSeedAndChoiceOutputsRemainProtected()throws Exception{
  Path root=corpus(),files=list("files.txt","A.warc\nB.warc\nC.warc\n"),seeds=list("seeds.txt","A.warc\n"),seedAlias=tmp.resolve("seed-alias.json");Files.createLink(seedAlias,seeds);
  assertEquals(2,run(plan(root,files,seeds,seedAlias,"--overwrite")).code);assertEquals("A.warc\n",Files.readString(seeds));
  Path baseline=tmp.resolve("baseline.json");assertEquals(0,run(audit(root,files,baseline)).code);var matcher=java.util.regex.Pattern.compile("\"corpusFingerprint\":\"([0-9a-f]{64})\"").matcher(Files.readString(baseline));assertTrue(matcher.find());
  Path choices=list("choices.json","{\"version\":1,\"corpusFingerprint\":\""+matcher.group(1)+"\",\"bindings\":[]}"),choiceAlias=tmp.resolve("choice-alias.json");Files.createLink(choiceAlias,choices);byte[] before=Files.readAllBytes(choices);
  assertEquals(2,run(audit(root,files,choiceAlias,"--overwrite","--choices",choices.toString())).code);assertArrayEquals(before,Files.readAllBytes(choices));
 }
 @Test void symlinkedInputsListsChoicesAndSavedReportsAreRefused()throws Exception{
  Path root=corpus(),files=list("files.txt","A.warc\nB.warc\nC.warc\n"),linkedList=tmp.resolve("linked.txt");Files.createSymbolicLink(linkedList,files);
  assertEquals(2,run(audit(root,linkedList,tmp.resolve("out.json"))).code);
  Files.createSymbolicLink(root.resolve("escape.warc"),root.resolve("A.warc"));assertEquals(2,run(audit(root,list("unsafe.txt","escape.warc\n"),tmp.resolve("unsafe.json"))).code);
  Path baseline=tmp.resolve("baseline.json");assertEquals(0,run(audit(root,files,baseline)).code);Path alias=tmp.resolve("linked-report.json");Files.createSymbolicLink(alias,baseline);assertEquals(2,run("explain","--report",alias.toString(),"--record","A.warc#1").code);
 }
 @Test void selectedSubsetCanCompleteDespiteExcludedDependencyBlocker()throws Exception{
  Path root=corpus();Files.writeString(root.resolve("bad.warc"),Files.readString(root.resolve("A.warc")).replace("000000000202","000000000999").replace("http://example.com/b","http://example.com/missing"));Path report=tmp.resolve("selected.json");
  assertEquals(0,run(plan(root,list("files.txt","A.warc\nB.warc\nC.warc\nbad.warc\n"),list("seeds.txt","C.warc\n"),report)).code);String json=Files.readString(report);assertTrue(json.contains("\"resolutionComplete\":false,\"closureComplete\":true,\"inputsVerified\":true,\"complete\":true"));assertTrue(json.contains("\"includedFiles\":[\"C.warc\"]"));
 }
}
