package revisitledger.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import revisitledger.model.*;
import revisitledger.plan.*;
import revisitledger.resolve.*;
import static revisitledger.resolve.ResolutionFixtures.*;

class StrictReportTest {
 @TempDir Path tmp;
 String report()throws Exception{
  try(var index=index(tmp,provider("A.warc","<a>","http://a/",DATE,DIGEST))){var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices)){return ReportWriterTest.write(index,plan,choices,false);}}
 }
 void rejects(String json)throws Exception{Path p=tmp.resolve("bad.json");Files.writeString(p,json);var out=new ByteArrayOutputStream();assertThrows(IOException.class,()->Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out)));assertEquals(0,out.size());}
 @Test void rejectsNestedUnknownFieldsMissingMembersAndControlCharacters()throws Exception{
  String json=report();
  for(String bad:List.of(json.replace("\"ordinal\":1","\"ordinal\":1,\"offset\":0"),json.replace("\"ordinal\":1","\"ordinal\":1,\"ordinal\":1"),json.replace("\"maxRecords\":1000000,",""),json.replace("http://a/","http://a/\n"),json.replace("WARC/1.1","WARC/9.9"),json.replace("\"maxScratchBytes\":4294967296","\"maxScratchBytes\":0"),json.replace("\"records\":[","\"records\":[[] ,")))rejects(bad);
 }
 @Test void rejectsDigestLengthMismatchAndImpossibleFlags()throws Exception{
  String json=report();rejects(json.replace("\"algorithm\":\"sha256\"","\"algorithm\":\"sha1\""));rejects(json.replace("\"inputsVerified\":false,\"complete\":false","\"inputsVerified\":false,\"complete\":true"));
  rejects(json.replace("\"scanComplete\":true","\"scanComplete\":false"));
 }
 @Test void rejectsMalformedUtf8UnpairedEscapesAndOversizedTokens()throws Exception{
  Path p=tmp.resolve("malformed.json");byte[] valid=report().getBytes(StandardCharsets.UTF_8);byte[] invalid=Arrays.copyOf(valid,valid.length+2);invalid[valid.length]=(byte)0xc0;invalid[valid.length+1]=(byte)0xaf;Files.write(p,invalid);var out=new ByteArrayOutputStream();assertThrows(IOException.class,()->Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out)));assertEquals(0,out.size());
  rejects(report().replace("http://a/","\\uD800"));rejects(report().replace("http://a/","x".repeat((1<<20)+1)));
 }
 @Test void allowsUnicodePathsInSqliteBinaryOrder()throws Exception{
  String first="\ue000.warc",second="\ud800\udc00.warc";
  try(var index=index(tmp,provider(second,"<second>","http://second/",DATE,DIGEST),provider(first,"<first>","http://first/",DATE,DIGEST))){var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of(first,second),choices)){
   String json=ReportWriterTest.write(index,plan,choices,false);Path p=tmp.resolve("unicode.json");Files.writeString(p,json);var out=new ByteArrayOutputStream();Explain.render(p,new RecordKey(second,1),new PrintStream(out));assertTrue(out.toString(StandardCharsets.UTF_8).contains(second+"#1"));
  }}
 }
 @Test void explainEscapesDecodedAndLiteralTerminalControls()throws Exception{
  String json=report().replace("\"selectionBasis\":\"\"","\"selectionBasis\":\"\\u001b[2J\"").replace("http://a/","http://a/"+(char)0x9b+"31m"+(char)0x7f+(char)0x202e);
  Path p=tmp.resolve("terminal.json");Files.writeString(p,json);var out=new ByteArrayOutputStream();Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out));String text=out.toString(StandardCharsets.UTF_8);
  assertFalse(text.chars().anyMatch(c->c!=10&&c!=9&&(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)));assertTrue(text.contains("\\u001b[2J"));assertTrue(text.contains("\\u009b"));
 }
 @Test void writerEscapesC1AndDirectionalHeaderControls()throws Exception{
  try(var index=index(tmp,provider("A.warc","<a>"+(char)0x9b+(char)0x7f+(char)0x202e,"http://a/",DATE,DIGEST))){var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices)){
   String json=ReportWriterTest.write(index,plan,choices,false);assertFalse(json.chars().anyMatch(c->c!=10&&(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)));assertTrue(json.contains("\\u009b"));
  }}
 }
 @Test void explainOmitsUnrelatedSameFileEdges()throws Exception{
  var provider=provider("A.warc","<a>","http://a/",DATE,DIGEST);
  var two=record("A.warc",2,"revisit","<two>","http://two/",DATE,DIGEST,Map.of("WARC-Refers-To","<a>"));
  var three=record("A.warc",3,"revisit","<three>","http://three/",DATE,DIGEST,Map.of("WARC-Refers-To","<a>"));
  try(var index=index(tmp,provider,two,three)){var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices)){
   Path p=tmp.resolve("same-file.json");Files.writeString(p,ReportWriterTest.write(index,plan,choices,false));var out=new ByteArrayOutputStream();Explain.render(p,new RecordKey("A.warc",2),new PrintStream(out));String text=out.toString(StandardCharsets.UTF_8);assertTrue(text.contains("A.warc#2"));assertFalse(text.contains("\"ordinal\":3"));
  }}
 }
 @Test void generatedSchemasRemainExactlyInSync()throws Exception{
  for(String kind:List.of("report","choices")){
   var out=new ByteArrayOutputStream();PrintStream original=System.out;try{System.setOut(new PrintStream(out,true,StandardCharsets.UTF_8));ReportSchema.main(kind.equals("choices")?new String[]{"choices"}:new String[]{});}finally{System.setOut(original);}
   assertEquals(Files.readString(Path.of("schemas",kind+"-v1.schema.json")),out.toString(StandardCharsets.UTF_8));
  }
 }
 @Test void noOutputOnMissingRecordOrInterruptedValidation()throws Exception{
  Path p=tmp.resolve("report.json");Files.writeString(p,report());var out=new ByteArrayOutputStream();assertThrows(IOException.class,()->Explain.render(p,new RecordKey("missing.warc",1),new PrintStream(out)));assertEquals(0,out.size());
  Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out)));assertEquals(0,out.size());}finally{Thread.interrupted();}
 }
}
