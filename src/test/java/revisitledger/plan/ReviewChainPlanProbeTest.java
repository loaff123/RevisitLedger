package revisitledger.plan;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;
import revisitledger.ingest.ArchiveScanner;
import revisitledger.model.*;
import revisitledger.resolve.*;
import static revisitledger.resolve.ResolutionFixtures.*;

/** Independent review probes for source binding, snapshot and failure boundaries. */
class ReviewChainPlanProbeTest {
 @TempDir Path temp;
 private ChoiceSet choices(RecordIndex index)throws IOException{return ChoiceSet.empty(index.corpusFingerprint());}
 private Path pair()throws IOException {
  Path root=Files.createDirectory(temp.resolve("corpus"));
  for(String file:List.of("response.warc","revisit.warc"))Files.copy(Path.of("tests/fixtures/derived/closed-pair",file),root.resolve(file));
  return root;
 }
 private RecordIndex scanned(Path root,List<String> files)throws IOException {
  var index=RecordIndex.create(temp,64L<<20);
  index.seal(new ArchiveScanner().scan(root,files,ScanLimits.defaults(),index));return index;
 }
 @Test void rootAwareVerificationIncludesExcludedCorpusFiles()throws Exception {
  Path root=pair();Files.copy(root.resolve("response.warc"),root.resolve("excluded.warc"));
  try(var index=scanned(root,List.of("response.warc","revisit.warc","excluded.warc"))){
   Files.writeString(root.resolve("excluded.warc"),"changed",StandardOpenOption.APPEND);
   try(var plan=new ClosurePlanner().plan(index,List.of("response.warc"),choices(index),root)){
    assertEquals(List.of("response.warc"),collect(plan.includedFiles()).stream().map(FileFacts::relativePath).toList());
    assertTrue(plan.closureComplete());assertFalse(plan.inputsVerified());assertFalse(plan.complete());
    assertTrue(collect(plan.blockers()).stream().anyMatch(f->f.code().equals("input_changed")&&f.evidence().get("file").equals("excluded.warc")));
   }
  }
 }
 @Test void equalBytesViaReplacementSymlinkCannotVerify()throws Exception {
  Path root=pair();
  try(var index=scanned(root,List.of("response.warc","revisit.warc"))){
   Files.move(root.resolve("response.warc"),root.resolve("copy.warc"));Files.createSymbolicLink(root.resolve("response.warc"),Path.of("copy.warc"));
   try(var plan=new ClosurePlanner().plan(index,List.of("revisit.warc"),choices(index),root)){
    assertTrue(plan.closureComplete());assertFalse(plan.inputsVerified());assertFalse(plan.complete());
   }
  }
 }
 @Test void earlierCompleteHandleFailsClosedAfterIndexQuotaFailure()throws Exception {
  Path root=pair();
  try(var index=scanned(root,List.of("response.warc","revisit.warc"))){
   var plan=new ClosurePlanner().plan(index,List.of("revisit.warc"),choices(index),root);assertTrue(plan.complete());
   var state=index.state("review.quota");boolean failed=false;
   for(int n=0;n<2000;n++)try{state.put(Integer.toString(n),new byte[65536]);}catch(IOException e){failed=true;break;}
   assertTrue(failed);assertFalse(plan.closureComplete());assertFalse(plan.inputsVerified());assertFalse(plan.complete());
   assertThrows(IOException.class,plan::witnessEdges);assertThrows(IOException.class,plan::close);
  }
 }
 @Test void partiallyConsumedPlanCursorsDoNotBlockNewPlansAndCloseSafely()throws Exception {
  try(var index=index(temp,revisit("a.warc",Map.of()),provider("b.warc","b","http://b/",DATE,DIGEST),provider("c.warc","c","http://c/",DATE,DIGEST))){
   var first=new ClosurePlanner().plan(index,List.of("a.warc"),new ChoiceSet(index.corpusFingerprint(),Map.of(key("a.warc"),key("b.warc"))));
   var files=first.includedFiles();var edges=first.witnessEdges();assertTrue(files.next());assertTrue(edges.next());
   try(var second=new ClosurePlanner().plan(index,List.of("a.warc"),new ChoiceSet(index.corpusFingerprint(),Map.of(key("a.warc"),key("c.warc"))))){
    assertEquals(key("b.warc"),edges.value().provider());assertTrue(first.closureComplete());assertTrue(second.closureComplete());
    assertTrue(files.next());assertEquals("b.warc",files.value().relativePath());first.close();
    assertFalse(first.closureComplete());assertThrows(IllegalStateException.class,edges::value);assertThrows(IOException.class,files::next);
    assertEquals(List.of("a.warc","c.warc"),collect(second.includedFiles()).stream().map(FileFacts::relativePath).toList());
   }
  }
 }
 @Test void selectedUnreferencedConcreteDigestErrorBlocksWholeFile()throws Exception {
  var good=provider("a.warc","a","http://a/",DATE,DIGEST);
  var bad=new RecordFacts(good.key(),good.type(),good.version(),good.rawHeaders(),Optional.of(new PayloadEvidence(9,Map.of("sha256",OTHER),true)),List.of());
  try(var index=index(temp,bad);var plan=new ClosurePlanner().plan(index,List.of("a.warc"),choices(index))){
   assertFalse(plan.closureComplete());assertEquals(List.of("digest_mismatch"),collect(plan.blockers()).stream().map(Finding::code).toList());
  }
 }
 @Test void midPlanInterruptionCleansOnlyItsNamespacesAndAllowsRetry()throws Exception {
  try(var index=index(temp,revisit("a.warc",Map.of("WARC-Refers-To","b")),provider("b.warc","b","http://b/",DATE,DIGEST))){
   var namespaces=new LinkedHashSet<String>();var injected=new AtomicBoolean();
   var interrupted=(RecordIndex)Proxy.newProxyInstance(RecordIndex.class.getClassLoader(),new Class<?>[]{RecordIndex.class},(proxy,method,args)->{
    try {
     Object result=method.invoke(index,args);
     if(method.getName().equals("state")&&((String)args[0]).startsWith("plan.")){
      String namespace=(String)args[0];namespaces.add(namespace);StateStore source=(StateStore)result;
      return new StateStore(){
       public Optional<byte[]> get(String key)throws IOException{return source.get(key);}
       public void put(String key,byte[] value)throws IOException{source.put(key,value);if(namespace.endsWith(".edges")&&injected.compareAndSet(false,true))Thread.currentThread().interrupt();}
       public void remove(String key)throws IOException{source.remove(key);}
       public void clear()throws IOException{source.clear();}
       public Cursor<StateEntry> scan()throws IOException{return source.scan();}
      };
     }
     return result;
    }catch(InvocationTargetException e){throw e.getCause();}
   });
   try{assertThrows(InterruptedIOException.class,()->new ClosurePlanner().plan(interrupted,List.of("a.warc"),choices(index)));}finally{Thread.interrupted();}
   assertTrue(injected.get());assertEquals(5,namespaces.size());
   for(String namespace:namespaces)try(var cursor=index.state(namespace).scan()){assertFalse(cursor.next(),namespace);}
   assertTrue(index.complete());
   try(var plan=new ClosurePlanner().plan(index,List.of("a.warc"),choices(index))){assertTrue(plan.closureComplete());}
  }
 }
}
