package revisitledger.resolve;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static revisitledger.resolve.ResolutionFixtures.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import revisitledger.index.*;
import revisitledger.model.*;

class ChainResolverTest {
 @TempDir Path tmp;
 RecordFacts p=provider("p.warc","<p>","http://p/",DATE,DIGEST);
 RecordFacts a=revisit("a.warc",Map.of("WARC-Refers-To","<b.warc>"));
 RecordFacts b=revisit("b.warc",Map.of("WARC-Refers-To","<p>"));
 ResolutionSummary resolve(RecordIndex index)throws IOException{return new ChainResolver().resolveAll(index,ChoiceSet.empty(index.corpusFingerprint()));}
 ChainOutcome outcome(RecordIndex index,String path)throws IOException{return ChainResolver.outcome(index,key(path)).orElseThrow();}
 @Test void resolvesEveryIntermediateAndRetainsImmediateEdges()throws Exception{
  try(var i=index(tmp,a,b,p)){
   var summary=resolve(i);assertTrue(summary.complete());assertEquals(Map.of("resolved",2L),summary.counts());
   var r=outcome(i,"a.warc");assertEquals("resolved",r.status());assertEquals(a.key(),r.consumer());assertEquals(Optional.of(b.key()),r.immediateProvider());assertEquals(Optional.of(p.key()),r.terminal());assertEquals(p.payload(),r.payloadEvidence());assertEquals("record_id",r.selectionBasis());
   var edges=collect(ChainResolver.witness(i,a.key()));assertEquals(List.of(a.key(),b.key()),edges.stream().map(WitnessEdge::consumer).toList());assertEquals(List.of(b.key(),p.key()),edges.stream().map(WitnessEdge::provider).toList());assertTrue(edges.stream().allMatch(e->e.terminal().equals(p.key())&&e.payloadEvidence().equals(p.payload().orElseThrow())));
  }
 }
 @Test void intermediateDigestIsCheckedAgainstTerminal()throws Exception{
  var wrong=revisit("b.warc",Map.of("WARC-Refers-To","<p>","WARC-Payload-Digest","sha256:"+OTHER));
  try(var i=index(tmp,a,wrong,p)){assertFalse(resolve(i).complete());assertEquals("digest_mismatch",outcome(i,"a.warc").status());assertEquals("digest_mismatch",outcome(i,"b.warc").status());assertTrue(collect(ChainResolver.witness(i,a.key())).isEmpty());}
 }
 @Test void firstRevisitDigestIsCheckedAgainstTerminal()throws Exception{
  var wrong=revisit("a.warc",Map.of("WARC-Refers-To","<b.warc>","WARC-Payload-Digest","sha256:"+OTHER));
  try(var i=index(tmp,wrong,b,p)){assertFalse(resolve(i).complete());assertEquals("digest_mismatch",outcome(i,"a.warc").status());assertEquals("resolved",outcome(i,"b.warc").status());}
 }
 @Test void unsupportedIntermediateBlocksAncestors()throws Exception{
  var bad=revisit("b.warc",Map.of("WARC-Refers-To","<p>","WARC-Profile","http://unknown/"));
  try(var i=index(tmp,a,bad,p)){assertFalse(resolve(i).complete());assertEquals("unsupported",outcome(i,"a.warc").status());assertTrue(collect(ChainResolver.witness(i,a.key())).isEmpty());}
 }
 @Test void missingTerminalBlocksAncestors()throws Exception{
  try(var i=index(tmp,a,b)){assertFalse(resolve(i).complete());assertEquals("missing_in_corpus",outcome(i,"a.warc").status());assertEquals("missing_in_corpus",outcome(i,"b.warc").status());}
 }
 @Test void cyclesAndPrefixesNeverProduceVerifiedWitnesses()throws Exception{
  var loop=revisit("b.warc",Map.of("WARC-Refers-To","<c.warc>"));var back=revisit("c.warc",Map.of("WARC-Refers-To","<b.warc>"));
  try(var i=index(tmp,a,loop,back)){assertEquals(Map.of("cycle",3L),resolve(i).counts());for(String path:List.of("a.warc","b.warc","c.warc")){assertEquals("cycle",outcome(i,path).status());assertTrue(collect(ChainResolver.witness(i,key(path))).isEmpty());}}
 }
 @Test void selfCycleBlocks()throws Exception{try(var i=index(tmp,revisit("a.warc",Map.of("WARC-Refers-To","<a.warc>")))){assertFalse(resolve(i).complete());assertEquals("cycle",outcome(i,"a.warc").status());}}
 @Test void providerOwnDigestMismatchBlocksEvenUnreferenced()throws Exception{
  var bad=withPayload(p,new PayloadEvidence(9,Map.of("sha256",OTHER),true));
  try(var i=index(tmp,bad)){assertEquals(Map.of("digest_mismatch",1L),resolve(i).counts());assertEquals("digest_mismatch",outcome(i,"p.warc").status());}
 }
 @Test void unrelatedUnsupportedRecordDoesNotBlock()throws Exception{
  var r=record("x.warc",1,"request","<x>","http://x/",DATE,null,Map.of());
  try(var i=index(tmp,r)){var s=resolve(i);assertTrue(s.complete());assertEquals(Map.of(),s.counts());assertEquals("unsupported",outcome(i,"x.warc").status());}
 }
 @Test void incompleteCorpusBlocksAllRevisitsButRetainsKnownProviderEvidence()throws Exception{
  try(var i=RecordIndex.create(tmp,64L<<20)){i.record(a);i.record(b);i.record(p);for(var r:List.of(a,b,p))i.file(new FileFacts(r.key().relativePath(),"f".repeat(64),1,!r.equals(a)));i.seal(new ScanSummary(3,3,false));var s=resolve(i);assertFalse(s.complete());assertEquals(Map.of("input_incomplete",2L),s.counts());assertEquals("input_incomplete",outcome(i,"a.warc").status());assertEquals(p.payload(),outcome(i,"p.warc").payloadEvidence());}
 }
 @Test void validatesChoicesGloballyEvenWithoutRevisits()throws Exception{
  try(var i=index(tmp,p)){var s=new ChainResolver().resolveAll(i,new ChoiceSet(i.corpusFingerprint(),Map.of(key("absent.warc"),p.key())));assertFalse(s.complete());}
 }
 @Test void staleEmptyChoicesInvalidateEmptyGraph()throws Exception{try(var i=index(tmp)){assertFalse(new ChainResolver().resolveAll(i,ChoiceSet.empty("stale")).complete());}}
 @Test void invalidGlobalBindingBlocksOtherwiseResolvableRevisits()throws Exception{
  try(var i=index(tmp,b,p)){var s=new ChainResolver().resolveAll(i,new ChoiceSet(i.corpusFingerprint(),Map.of(key("absent.warc"),p.key())));assertFalse(s.complete());assertEquals("input_incomplete",outcome(i,"b.warc").status());}
 }
 @Test void choiceChangesReplaceOutcomesAndInvalidateOldWitness()throws Exception{
  var q=provider("q.warc","<q>","http://q/",DATE,DIGEST);var r=revisit("r.warc",Map.of());
  try(var i=index(tmp,p,q,r)){var resolver=new ChainResolver();resolver.resolveAll(i,new ChoiceSet(i.corpusFingerprint(),Map.of(r.key(),p.key())));try(var witness=ChainResolver.witness(i,r.key())){resolver.resolveAll(i,new ChoiceSet(i.corpusFingerprint(),Map.of(r.key(),q.key())));assertThrows(IOException.class,witness::next);}assertEquals(Optional.of(q.key()),outcome(i,"r.warc").terminal());assertEquals("choice:digest",outcome(i,"r.warc").selectionBasis());}
 }
 @Test void outcomesUnavailableUntilCompletedAndAfterInterruptedReplacement()throws Exception{
  try(var i=index(tmp,a,b,p)){assertThrows(IOException.class,()->ChainResolver.outcome(i,a.key()));resolve(i);Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->resolve(i));}finally{Thread.interrupted();}assertThrows(IOException.class,()->ChainResolver.outcome(i,a.key()));assertTrue(resolve(i).complete());}
 }
 @Test void binaryCodecPreservesUnicodeDelimitersAndRejectsTruncation()throws Exception{
  RecordKey c=new RecordKey("☃|\n#.warc",17);var r=new ChainOutcome("resolved",c,Optional.of(p.key()),"record_id",Optional.of(p.key()),p.payload());byte[] bytes=ChainStateCodec.encodeOutcome(r);assertEquals(r,ChainStateCodec.decodeOutcome(bytes));assertThrows(IOException.class,()->ChainStateCodec.decodeOutcome(Arrays.copyOf(bytes,bytes.length-1)));
  var edge=new WitnessEdge(c,p.key(),"record_id",p.key(),p.payload().orElseThrow());assertEquals(edge,ChainStateCodec.decodeEdge(ChainStateCodec.encodeEdge(edge)));assertNotEquals(ChainStateCodec.key(c),ChainStateCodec.key(new RecordKey("☃|\n#.warc",18)));
 }
 @Test void tenThousandRecordChainUsesBoundedHeapAndIterativeTraversal()throws Exception{
  try(var i=longGraph(10000,false)){var s=resolve(i);assertTrue(s.complete());assertEquals(9999L,s.counts().get("resolved"));long n=0;try(var witness=ChainResolver.witness(i,new RecordKey("long.warc",1))){while(witness.next())n++;}assertEquals(9999,n);}
 }
 @Test void tenThousandRecordCycleUsesBoundedHeapAndIterativeTraversal()throws Exception{
  try(var i=longGraph(10000,true)){var s=resolve(i);assertFalse(s.complete());assertEquals(Map.of("cycle",10000L),s.counts());assertEquals("cycle",ChainResolver.outcome(i,new RecordKey("long.warc",9999)).orElseThrow().status());}
 }
 @Test void scratchExhaustionDuringResolutionCannotLeaveReadableOutcomes()throws Exception{
  try(var i=longGraph(2000,false,5L<<19)){assertTrue(i.complete());assertThrows(IOException.class,()->resolve(i));assertFalse(i.complete());assertThrows(IOException.class,()->ChainResolver.outcome(i,new RecordKey("long.warc",1)));}
  try(var children=Files.list(tmp)){assertEquals(0,children.count());}
 }
 @Test void codecRejectsTrailingWrongVersionAndOversizedState()throws Exception{
  var r=new ChainOutcome("resolved",p.key(),Optional.empty(),"",Optional.of(p.key()),p.payload());byte[] bytes=ChainStateCodec.encodeOutcome(r);
  assertThrows(IOException.class,()->ChainStateCodec.decodeOutcome(Arrays.copyOf(bytes,bytes.length+1)));
  bytes[3]=9;assertThrows(IOException.class,()->ChainStateCodec.decodeOutcome(bytes));
  assertThrows(IOException.class,()->ChainStateCodec.decodeEdge(new byte[65537]));
  var huge=new ChainOutcome("resolved",new RecordKey("x".repeat(70000),1),Optional.empty(),"",Optional.empty(),Optional.empty());assertThrows(IOException.class,()->ChainStateCodec.encodeOutcome(huge));
 }
 @Test void checksDifferentDeclaredAlgorithmsAgainstSameTerminalBytes()throws Exception{
  var terminal=withPayload(p,new PayloadEvidence(9,Map.of("sha1","c".repeat(40),"sha256",DIGEST),true));
  for(String sha1:List.of("c".repeat(40),"d".repeat(40))){
   var first=revisit("a.warc",Map.of("WARC-Refers-To","<b.warc>","WARC-Payload-Digest","sha1:"+sha1));
   try(var i=index(tmp,first,b,terminal)){resolve(i);assertEquals(sha1.startsWith("c")?"resolved":"digest_mismatch",outcome(i,"a.warc").status());assertEquals("resolved",outcome(i,"b.warc").status());}
  }
 }
 @Test void absentIntermediateDeclarationDoesNotInventDigestFailure()throws Exception{
  var intermediate=record("b.warc",1,"revisit","<b.warc>","http://b/",DATE,null,Map.of("WARC-Refers-To","<p>"));
  try(var i=index(tmp,a,intermediate,p)){assertTrue(resolve(i).complete());assertEquals(p.payload(),outcome(i,"a.warc").payloadEvidence());}
 }
 @Test void interruptionAfterStoredOutcomesFailsClosedAndCanRetry()throws Exception{
  try(var i=index(tmp,a,b,p)){
   java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
   RecordIndex interrupted=(RecordIndex)java.lang.reflect.Proxy.newProxyInstance(RecordIndex.class.getClassLoader(),new Class<?>[]{RecordIndex.class},(proxy,method,args)->{
    try{
     Object result=method.invoke(i,args);
     if(method.getName().equals("state")&&args[0].equals("chain_outcomes")){
      StateStore delegate=(StateStore)result;
      return new StateStore(){
       public Optional<byte[]> get(String key)throws IOException{return delegate.get(key);}
       public void put(String key,byte[] value)throws IOException{delegate.put(key,value);if(writes.incrementAndGet()==2)Thread.currentThread().interrupt();}
       public void remove(String key)throws IOException{delegate.remove(key);}
       public void clear()throws IOException{delegate.clear();}
       public Cursor<StateEntry> scan()throws IOException{return delegate.scan();}
      };
     }
     return result;
    }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   try{assertThrows(InterruptedIOException.class,()->resolve(interrupted));}finally{Thread.interrupted();}
   assertTrue(writes.get()>=2);assertTrue(i.complete());assertThrows(IOException.class,()->ChainResolver.outcome(i,a.key()));assertTrue(resolve(i).complete());
  }
 }
 @Test void diagnosticCycleWitnessIncludesSelfRepeatedEntry()throws Exception{
  var loop=revisit("a.warc",Map.of("WARC-Refers-To","<a.warc>"));
  try(var i=index(tmp,loop)){resolve(i);assertEquals(List.of(loop.key(),loop.key()),collect(ChainResolver.cycleWitness(i,loop.key())));}
 }
 @Test void diagnosticCycleWitnessIncludesTailAndOneFiniteCycle()throws Exception{
  var loop=revisit("b.warc",Map.of("WARC-Refers-To","<c.warc>"));var back=revisit("c.warc",Map.of("WARC-Refers-To","<b.warc>"));
  try(var i=index(tmp,a,loop,back)){resolve(i);assertEquals(List.of(a.key(),loop.key(),back.key(),loop.key()),collect(ChainResolver.cycleWitness(i,a.key())));assertEquals(List.of(loop.key(),back.key(),loop.key()),collect(ChainResolver.cycleWitness(i,loop.key())));assertTrue(collect(ChainResolver.witness(i,a.key())).isEmpty());}
 }
 @Test void diagnosticCycleWitnessIsEmptyForNonCycleOutcomes()throws Exception{
  try(var i=index(tmp,a,b,p)){resolve(i);assertTrue(collect(ChainResolver.cycleWitness(i,a.key())).isEmpty());assertTrue(collect(ChainResolver.cycleWitness(i,p.key())).isEmpty());}
  try(var i=index(tmp,a,b)){resolve(i);assertTrue(collect(ChainResolver.cycleWitness(i,a.key())).isEmpty());}
 }
 @Test void diagnosticCycleWitnessObeysGenerationCloseAndInterruption()throws Exception{
  var loop=revisit("a.warc",Map.of("WARC-Refers-To","<a.warc>"));
  try(var i=index(tmp,loop)){assertThrows(IOException.class,()->ChainResolver.cycleWitness(i,loop.key()));resolve(i);
   try(var old=ChainResolver.cycleWitness(i,loop.key())){assertThrows(IllegalStateException.class,old::value);assertTrue(old.next());resolve(i);assertThrows(IOException.class,old::next);}
   try(var interrupted=ChainResolver.cycleWitness(i,loop.key())){Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,interrupted::next);}finally{Thread.interrupted();}}
   var closed=ChainResolver.cycleWitness(i,loop.key());closed.close();assertThrows(IOException.class,closed::next);
  }
 }
 @Test void diagnosticCycleWitnessWalksTenThousandNodesWithoutRetainingPath()throws Exception{
  try(var i=longGraph(10000,true)){resolve(i);long count=0;RecordKey first=null,last=null;try(var cursor=ChainResolver.cycleWitness(i,new RecordKey("long.warc",1))){while(cursor.next()){if(first==null)first=cursor.value();last=cursor.value();count++;}}assertEquals(10001,count);assertEquals(first,last);}
 }
 private RecordIndex longGraph(int count,boolean cycle)throws Exception{return longGraph(count,cycle,128L<<20);}
 private RecordIndex longGraph(int count,boolean cycle,long budget)throws Exception{
  var i=RecordIndex.create(tmp,budget);for(int n=1;n<=count;n++){boolean concrete=n==count&&!cycle;i.record(record("long.warc",n,concrete?"resource":"revisit","<"+n+">","http://x/",DATE,DIGEST,concrete?Map.of():Map.of("WARC-Refers-To","<"+(n==count?1:n+1)+">")));}i.file(new FileFacts("long.warc","f".repeat(64),count,true));i.seal(new ScanSummary(1,count,true));return i;
 }
 private static RecordFacts withPayload(RecordFacts r,PayloadEvidence p){return new RecordFacts(r.key(),r.type(),r.version(),r.rawHeaders(),Optional.of(p),r.findings());}
}
