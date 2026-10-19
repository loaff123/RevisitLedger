package revisitledger.plan;
import java.io.*;import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;import revisitledger.ingest.*;import revisitledger.model.*;import revisitledger.resolve.*;
import static revisitledger.resolve.ResolutionFixtures.*;
class ClosurePlannerTest {
 @TempDir Path tmp;
 static RecordFacts ref(String file,long ordinal,String id,String targetId){return record(file,ordinal,"revisit",id,"http://"+id+"/",DATE,DIGEST,Map.of("WARC-Refers-To",targetId));}
 List<String> paths(PlanHandle plan)throws Exception{return collect(plan.includedFiles()).stream().map(FileFacts::relativePath).toList();}
 ChoiceSet choices(RecordIndex i)throws Exception{return ChoiceSet.empty(i.corpusFingerprint());}
 @Test void addedFileIntroducesNewRevisit()throws Exception{
  var a=ref("A.warc",1,"a","b");var b=provider("B.warc","b","http://b/",DATE,DIGEST);var extra=ref("B.warc",2,"extra","c");var c=provider("C.warc","c","http://c/",DATE,DIGEST);
  try(var i=index(tmp,a,b,extra,c);var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertEquals(List.of("A.warc","B.warc","C.warc"),paths(p));assertTrue(p.closureComplete());assertEquals(2,collect(p.addedFiles()).size());assertEquals(2,collect(p.witnessEdges()).size());}
 }
 @Test void unrelatedFileExcluded()throws Exception{try(var i=index(tmp,ref("A.warc",1,"a","b"),provider("B.warc","b","http://b/",DATE,DIGEST),provider("Z.warc","z","http://z/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertEquals(List.of("A.warc","B.warc"),paths(p));}}
 @Test void allSeedsRetained()throws Exception{try(var i=index(tmp,provider("B.warc","b","http://b/",DATE,DIGEST),provider("A.warc","a","http://a/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("B.warc","A.warc","A.warc"),choices(i))){assertEquals(List.of("A.warc","B.warc"),paths(p));assertTrue(collect(p.addedFiles()).isEmpty());}}
 @Test void chosenAlternativeStillRequiresItsWholeFile()throws Exception{
  var a=ref("A.warc",1,"a","unused");a=record("A.warc",1,"revisit","a","http://a/",DATE,DIGEST,Map.of());
  try(var i=index(tmp,a,provider("B.warc","b","http://b/",DATE,DIGEST),ref("B.warc",2,"extra","d"),provider("C.warc","c","http://c/",DATE,DIGEST),provider("D.warc","d","http://d/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("A.warc"),new ChoiceSet(i.corpusFingerprint(),Map.of(key("A.warc"),key("B.warc"))))){assertEquals(List.of("A.warc","B.warc","D.warc"),paths(p));assertTrue(p.closureComplete());}
 }
 @Test void chainIncludesIntermediateFileAndItsOtherRevisits()throws Exception{try(var i=index(tmp,ref("A.warc",1,"a","b"),ref("B.warc",1,"b","c"),ref("B.warc",2,"extra","d"),provider("C.warc","c","http://c/",DATE,DIGEST),provider("D.warc","d","http://d/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertEquals(List.of("A.warc","B.warc","C.warc","D.warc"),paths(p));assertTrue(p.closureComplete());}}
 @Test void unresolvedWholeChainDoesNotAddSpeculativeFiles()throws Exception{try(var i=index(tmp,ref("A.warc",1,"a","b"),ref("B.warc",1,"b","absent"));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertEquals(List.of("A.warc"),paths(p));assertFalse(p.closureComplete());var blockers=collect(p.blockers());assertEquals(1,blockers.size());assertEquals("missing_in_corpus",blockers.get(0).code());}}
 @Test void unrelatedUnresolvedRevisitDoesNotBlockSubset()throws Exception{try(var i=index(tmp,provider("A.warc","a","http://a/",DATE,DIGEST),ref("Z.warc",1,"z","absent"));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertTrue(p.closureComplete());assertTrue(collect(p.blockers()).isEmpty());}}
 @Test void selectedUnsupportedRevisitBlocks()throws Exception{try(var i=index(tmp,record("A.warc",1,"revisit","a","http://a/",DATE,DIGEST,Map.of("WARC-Profile","unknown")));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertFalse(p.closureComplete());var blockers=collect(p.blockers());assertEquals(1,blockers.size());assertEquals("unsupported",blockers.get(0).code());}}
 @Test void staleChoicesBlockEvenNoSeeds()throws Exception{try(var i=index(tmp,provider("A.warc","a","http://a/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of(),ChoiceSet.empty("stale"))){assertFalse(p.closureComplete());}}
 @Test void unknownSeedIsRejected()throws Exception{try(var i=index(tmp,provider("A.warc","a","http://a/",DATE,DIGEST))){assertThrows(IOException.class,()->new ClosurePlanner().plan(i,List.of("none.warc"),choices(i)));}}
 @Test void graphOnlyCompletenessDoesNotAssertCurrentInputBytes()throws Exception{try(var i=index(tmp,provider("A.warc","a","http://a/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertTrue(p.closureComplete());assertFalse(p.inputsVerified());assertFalse(p.complete());}}
 @Test void unchangedRootAwarePlanIsComplete()throws Exception{Path root=Path.of("tests/fixtures/derived/closed-pair");try(var i=RecordIndex.create(tmp,64L<<20)){i.seal(new ArchiveScanner().scan(root,List.of("response.warc","revisit.warc"),ScanLimits.defaults(),i));try(var p=new ClosurePlanner().plan(i,List.of("revisit.warc"),choices(i),root)){assertTrue(p.closureComplete());assertTrue(p.inputsVerified());assertTrue(p.complete());assertEquals(List.of("response.warc","revisit.warc"),paths(p));}}}
 @Test void modificationsBetweenScanAndPlanInvalidateInputVerification()throws Exception{
  Path root=Files.createDirectory(tmp.resolve("corpus"));for(String file:List.of("response.warc","revisit.warc"))Files.copy(Path.of("tests/fixtures/derived/closed-pair",file),root.resolve(file));
  try(var i=RecordIndex.create(tmp,64L<<20)){i.seal(new ArchiveScanner().scan(root,List.of("response.warc","revisit.warc"),ScanLimits.defaults(),i));Files.writeString(root.resolve("response.warc"),"changed",StandardOpenOption.APPEND);try(var p=new ClosurePlanner().plan(i,List.of("revisit.warc"),choices(i),root)){assertTrue(p.closureComplete());assertFalse(p.inputsVerified());assertFalse(p.complete());assertTrue(collect(p.blockers()).stream().anyMatch(f->f.code().equals("input_changed")));}}
 }
 @Test void repeatedPlansKeepEarlierHandlesStable()throws Exception{try(var i=index(tmp,record("A.warc",1,"revisit","a","http://a/",DATE,DIGEST,Map.of()),provider("B.warc","b","http://b/",DATE,DIGEST),provider("C.warc","c","http://c/",DATE,DIGEST));var first=new ClosurePlanner().plan(i,List.of("A.warc"),new ChoiceSet(i.corpusFingerprint(),Map.of(key("A.warc"),key("B.warc"))));var second=new ClosurePlanner().plan(i,List.of("A.warc"),new ChoiceSet(i.corpusFingerprint(),Map.of(key("A.warc"),key("C.warc"))))){assertEquals(List.of("A.warc","B.warc"),paths(first));assertEquals(List.of("A.warc","C.warc"),paths(second));assertEquals(key("B.warc"),collect(first.witnessEdges()).get(0).provider());}}
 @Test void interruptCannotReturnCompletedPlan()throws Exception{try(var i=index(tmp,provider("A.warc","a","http://a/",DATE,DIGEST))){Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->new ClosurePlanner().plan(i,List.of("A.warc"),choices(i)));}finally{Thread.interrupted();}}}
 @Test void planCannotOutliveItsIndex()throws Exception{var i=index(tmp,provider("A.warc","a","http://a/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i));i.close();assertThrows(IOException.class,p::includedFiles);}

 @Test void witnessAndBlockerCursorsUseLocatorOrder()throws Exception{
  try(var i=index(tmp,ref("A.warc",1,"a","p"),ref("B.warc",1,"b","p"),ref("C.warc",1,"c","p"),provider("P.warc","p","http://p/",DATE,DIGEST));var p=new ClosurePlanner().plan(i,List.of("C.warc","B.warc","A.warc"),choices(i))){assertEquals(List.of(key("A.warc"),key("B.warc"),key("C.warc")),collect(p.witnessEdges()).stream().map(WitnessEdge::consumer).toList());}
  try(var i=index(tmp,ref("A.warc",1,"a","missing"),ref("B.warc",1,"b","missing"),ref("C.warc",1,"c","missing"));var p=new ClosurePlanner().plan(i,List.of("C.warc","B.warc","A.warc"),choices(i))){assertEquals(List.of(key("A.warc"),key("B.warc"),key("C.warc")),collect(p.blockers()).stream().map(f->f.record().orElseThrow()).toList());}
 }

 @Test void twoThousandWholeFilesReachFixedPointWithDiskState()throws Exception{
  int size=2000;try(var i=RecordIndex.create(tmp,64L<<20)){
   for(int n=0;n<size;n++){String file=String.format(java.util.Locale.ROOT,"f%04d.warc",n);i.record(n==size-1?provider(file,"n"+n,"http://p/",DATE,DIGEST):ref(file,1,"n"+n,"n"+(n+1)));i.file(new FileFacts(file,"f".repeat(64),1,true));}
   i.seal(new ScanSummary(size,size,true));try(var p=new ClosurePlanner().plan(i,List.of("f0000.warc"),choices(i))){int files=0,edges=0;try(var c=p.includedFiles()){while(c.next())files++;}try(var c=p.witnessEdges()){while(c.next())edges++;}assertEquals(size,files);assertEquals(size-1,edges);assertTrue(p.closureComplete());}
  }
 }
 @Test void digestMismatchInUnreferencedIncludedProviderBlocks()throws Exception{
  var p=provider("A.warc","a","http://a/",DATE,DIGEST);p=new RecordFacts(p.key(),p.type(),p.version(),p.rawHeaders(),Optional.of(new PayloadEvidence(9,Map.of("sha256",OTHER),true)),List.of());
  try(var i=index(tmp,p);var plan=new ClosurePlanner().plan(i,List.of("A.warc"),choices(i))){assertFalse(plan.closureComplete());assertEquals("digest_mismatch",collect(plan.blockers()).get(0).code());}
 }
}
