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

/** Independent release-boundary checks. */
class ReviewFinalProbeTest {
 @TempDir Path tmp;
 String report(String id)throws Exception{
  try(var index=index(tmp,provider("A.warc",id,"http://a/",DATE,DIGEST))){
   var choices=ChoiceSet.empty(index.corpusFingerprint());
   try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices)){
    return ReportWriterTest.write(index,plan,choices,false);
   }
  }
 }
 @Test void supplementaryFormatControlsAreEscapedByJsonWriter()throws Exception{
  String json=report("<a>"+new String(Character.toChars(0xe0001)));
  assertFalse(json.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT),"Supplementary FORMAT control was emitted literally in JSON");
 }
 @Test void supplementaryFormatControlsAreEscapedByExplanation()throws Exception{
  String control=new String(Character.toChars(0xe0001));
  String json=report("<a>").replace("http://a/","http://a/"+control);
  Path p=tmp.resolve("report.json");Files.writeString(p,json);var out=new ByteArrayOutputStream();
  Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out));
  assertFalse(out.toString(StandardCharsets.UTF_8).codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT),"Supplementary FORMAT control was emitted literally on terminal");
 }
 @Test void delayedMalformedTailDoesNotLeakAnEarlierRecord()throws Exception{
  String json=report("<a>");Path p=tmp.resolve("bad.json");
  Files.writeString(p,json+" \n\t".repeat(100000)+"garbage");var out=new ByteArrayOutputStream();
  assertThrows(IOException.class,()->Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out)));
  assertEquals(0,out.size());
 }
 @Test void malformedUtf8AfterMatchedRecordDoesNotLeakOutput()throws Exception{
  byte[] json=report("<a>").getBytes(StandardCharsets.UTF_8);byte[] malformed=Arrays.copyOf(json,json.length+1);malformed[json.length]=(byte)0xff;
  Path p=tmp.resolve("bad-utf8.json");Files.write(p,malformed);var out=new ByteArrayOutputStream();
  assertThrows(IOException.class,()->Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out)));
  assertEquals(0,out.size());
 }
 @Test void byteOrderMarkAndNegativeZeroVersionAreRejected()throws Exception{
  String json=report("<a>");for(String invalid:List.of("\ufeff"+json,json.replace("\"schemaVersion\":1","\"schemaVersion\":-0"))){
   Path p=tmp.resolve("bad-token.json");Files.writeString(p,invalid);var out=new ByteArrayOutputStream();
   assertThrows(IOException.class,()->Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out)));assertEquals(0,out.size());
  }
 }
 @Test void ordinarySupplementaryCharactersRemainReadable()throws Exception{
  String symbol=new String(Character.toChars(0x1f600));String json=report("<a>"+symbol);assertTrue(json.contains(symbol));
  Path p=tmp.resolve("supplementary.json");Files.writeString(p,json);var out=new ByteArrayOutputStream();Explain.render(p,new RecordKey("A.warc",1),new PrintStream(out));assertTrue(out.toString(StandardCharsets.UTF_8).contains(symbol));
 }
}
