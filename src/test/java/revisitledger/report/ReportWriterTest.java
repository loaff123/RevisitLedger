package revisitledger.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;
import revisitledger.ingest.*;
import revisitledger.model.*;
import revisitledger.plan.*;
import revisitledger.resolve.*;
import static revisitledger.resolve.ResolutionFixtures.*;

class ReportWriterTest {
 @TempDir Path tmp;
 static Object call(String name,String method,Class<?>[] types,Object...args)throws Exception {
  Class<?> c=assertDoesNotThrow(()->Class.forName("revisitledger.report."+name),"Report implementation is missing");
  try{return c.getMethod(method,types).invoke(null,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
 }
 static String write(RecordIndex index,PlanHandle plan,ChoiceSet choices,boolean audit)throws Exception {
  var out=new ByteArrayOutputStream();call("ReportWriter",audit?"writeAudit":"write",new Class<?>[]{RecordIndex.class,PlanHandle.class,ChoiceSet.class,ScanLimits.class,OutputStream.class},index,plan,choices,ScanLimits.defaults(),out);return out.toString(StandardCharsets.UTF_8);
 }
 String realReport(boolean reverse)throws Exception {
  Path root=Path.of("tests/fixtures/derived/whole-file");
  try(var index=RecordIndex.create(tmp,64L<<20)){
   index.seal(new ArchiveScanner().scan(root,reverse?List.of("C.warc","B.warc","A.warc"):List.of("A.warc","B.warc","C.warc"),ScanLimits.defaults(),index));
   var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices,root)){return write(index,plan,choices,false);}
  }
 }
 @Test void deterministicOrderAndCompleteRootAwarePlan()throws Exception {
  String a=realReport(false);assertEquals(a,realReport(true));
  assertTrue(a.contains("\"includedFiles\":[\"A.warc\",\"B.warc\",\"C.warc\"]"));
  assertTrue(a.contains("\"scanComplete\":true,\"resolutionComplete\":true,\"closureComplete\":true,\"inputsVerified\":true,\"complete\":true"));
  assertFalse(a.contains(tmp.toString()));assertFalse(a.contains("some\\ntext"));
  assertTrue(a.contains("\"normalizedDigests\""));assertTrue(a.contains("\"witnessEdges\":[{"));
 }
 @Test void separatesGlobalResolutionFromSelectedClosure()throws Exception {
  var good=provider("A.warc","<a>","http://a/",DATE,DIGEST);var bad=revisit("Z.warc",Map.of("WARC-Refers-To","<missing>"));
  try(var index=index(tmp,good,bad)){var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices)){
   String report=write(index,plan,choices,false);assertTrue(report.contains("\"resolutionComplete\":false,\"closureComplete\":true,\"inputsVerified\":false,\"complete\":false"));
  }}
 }
 @Test void controlsAreEscapedAndOnlyRelevantRawHeadersAppear()throws Exception {
  var record=provider("A.warc","<a>\n\t\"\\", "http://a/",DATE,DIGEST);
  var headers=new LinkedHashMap<>(record.rawHeaders());headers.put("X-Payload-Secret",List.of("hidden-secret"));headers.put("WARC-IP-Address",List.of("127.0.0.1"));
  record=new RecordFacts(record.key(),record.type(),record.version(),headers,record.payload(),record.findings());
  try(var index=index(tmp,record)){var choices=ChoiceSet.empty(index.corpusFingerprint());try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices)){
   String report=write(index,plan,choices,false);assertTrue(report.contains("<a>\\n\\t\\\"\\\\"));assertFalse(report.contains("hidden-secret"));assertFalse(report.contains("127.0.0.1"));
  }}
 }
 @Test void writerDoesNotInvalidateExistingPlanCursors()throws Exception {
  Path root=Path.of("tests/fixtures/derived/whole-file");try(var index=RecordIndex.create(tmp,64L<<20)){
   index.seal(new ArchiveScanner().scan(root,List.of("A.warc","B.warc","C.warc"),ScanLimits.defaults(),index));var choices=ChoiceSet.empty(index.corpusFingerprint());
   try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),choices,root);var cursor=ChainResolver.witness(index,new RecordKey("A.warc",1))){
    write(index,plan,choices,false);assertTrue(cursor.next());assertTrue(plan.complete());
   }
  }
 }
 @Test void explainValidatesEntireInputBeforeEmittingAnything()throws Exception {
  String json=realReport(false);Path report=tmp.resolve("report.json");Files.writeString(report,json);var out=new ByteArrayOutputStream();
  call("Explain","render",new Class<?>[]{Path.class,RecordKey.class,PrintStream.class},report,new RecordKey("A.warc",1),new PrintStream(out));
  assertTrue(out.toString(StandardCharsets.UTF_8).contains("A.warc#1"));assertTrue(out.toString(StandardCharsets.UTF_8).contains("resolved"));
  for(String invalid:List.of(json+"{}",json.replace("\"schemaVersion\":1","\"schemaVersion\":2"),json.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),json.replace("\"warnings\":[","\"unknown\":0,\"warnings\":["),json.substring(0,json.length()-3))){
   Files.writeString(report,invalid);out.reset();assertThrows(IOException.class,()->call("Explain","render",new Class<?>[]{Path.class,RecordKey.class,PrintStream.class},report,new RecordKey("A.warc",1),new PrintStream(out)));assertEquals(0,out.size());
  }
 }
 @Test void choiceReaderRejectsDuplicateUnknownAndUnsafeInput()throws Exception {
  String valid="{\"version\":1,\"corpusFingerprint\":\""+"a".repeat(64)+"\",\"bindings\":[{\"consumer\":{\"path\":\"A.warc\",\"ordinal\":1},\"provider\":{\"path\":\"B.warc\",\"ordinal\":2}}]}";
  Path path=tmp.resolve("choices.json");Files.writeString(path,valid);ChoiceSet choices=(ChoiceSet)call("ChoiceReader","read",new Class<?>[]{Path.class},path);assertEquals(new RecordKey("B.warc",2),choices.choice(new RecordKey("A.warc",1)).orElseThrow());
  for(String invalid:List.of(valid+"null",valid.replace("\"version\":1","\"version\":1,\"version\":1"),valid.replace("\"version\":1","\"version\":1,\"extra\":0"),valid.replace("A.warc","../A.warc"),valid.replace("A.warc","/tmp/A.warc"),valid.replace("\"ordinal\":1","\"ordinal\":1.0"),valid.replace("\"ordinal\":1","\"ordinal\":0"),valid.replace("\"bindings\":[","\"bindings\":[{\"consumer\":{\"path\":\"A.warc\",\"ordinal\":1},\"provider\":{\"path\":\"B.warc\",\"ordinal\":2}},"))){Files.writeString(path,invalid);assertThrows(IOException.class,()->call("ChoiceReader","read",new Class<?>[]{Path.class},path));}
 }
}
