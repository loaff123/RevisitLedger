package revisitledger.plan;
import java.io.*;import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;import revisitledger.ingest.*;import revisitledger.model.*;import revisitledger.resolve.*;
import static revisitledger.resolve.ResolutionFixtures.*;
class PlannerIntegrationTest {
 @TempDir Path tmp;
 @Test void actualWholeFileFixtureCanBeRescannedAsClosedSubset()throws Exception{
  Path root=Files.createDirectory(tmp.resolve("corpus"));for(String f:List.of("A.warc","B.warc","C.warc"))Files.copy(Path.of("tests/fixtures/derived/whole-file",f),root.resolve(f));Files.copy(Path.of("tests/fixtures/upstream-warcio/RESOURCE_RECORD.warc"),root.resolve("D.warc"));
  List<String> subset;
  try(var index=RecordIndex.create(tmp,64L<<20)){index.seal(new ArchiveScanner().scan(root,List.of("D.warc","C.warc","A.warc","B.warc"),ScanLimits.defaults(),index));try(var p=new ClosurePlanner().plan(index,List.of("A.warc"),ChoiceSet.empty(index.corpusFingerprint()),root)){assertTrue(p.complete());subset=collect(p.includedFiles()).stream().map(FileFacts::relativePath).toList();assertEquals(List.of("A.warc","B.warc","C.warc"),subset);}}
  try(var index=RecordIndex.create(tmp,64L<<20)){index.seal(new ArchiveScanner().scan(root,subset,ScanLimits.defaults(),index));try(var p=new ClosurePlanner().plan(index,subset,ChoiceSet.empty(index.corpusFingerprint()),root)){assertTrue(p.complete());assertTrue(collect(p.blockers()).isEmpty());}}
 }
 @Test void permutingInputFilesPreservesFingerprintAndPlanWitnesses()throws Exception{
  Path root=Path.of("tests/fixtures/derived/whole-file");String fingerprint=null;List<WitnessEdge> edges=null;
  for(var files:List.of(List.of("A.warc","B.warc","C.warc"),List.of("C.warc","B.warc","A.warc"))){try(var i=RecordIndex.create(tmp,64L<<20)){i.seal(new ArchiveScanner().scan(root,files,ScanLimits.defaults(),i));try(var p=new ClosurePlanner().plan(i,List.of("A.warc"),ChoiceSet.empty(i.corpusFingerprint()),root)){assertTrue(p.complete());if(fingerprint==null){fingerprint=i.corpusFingerprint();edges=collect(p.witnessEdges());}else{assertEquals(fingerprint,i.corpusFingerprint());assertEquals(edges,collect(p.witnessEdges()));}}}}
 }
 @Test void changingUnselectedCorpusFileStillInvalidatesCorpusBoundPlan()throws Exception{
  Path root=Files.createDirectory(tmp.resolve("corpus"));Files.copy(Path.of("tests/fixtures/derived/closed-pair/response.warc"),root.resolve("response.warc"));Files.copy(Path.of("tests/fixtures/upstream-warcio/RESOURCE_RECORD.warc"),root.resolve("unselected.warc"));
  try(var i=RecordIndex.create(tmp,64L<<20)){i.seal(new ArchiveScanner().scan(root,List.of("response.warc","unselected.warc"),ScanLimits.defaults(),i));Files.writeString(root.resolve("unselected.warc"),"x",StandardOpenOption.APPEND);try(var p=new ClosurePlanner().plan(i,List.of("response.warc"),ChoiceSet.empty(i.corpusFingerprint()),root)){assertTrue(p.closureComplete());assertFalse(p.inputsVerified());assertFalse(p.complete());assertEquals("unselected.warc",collect(p.blockers()).get(0).evidence().get("file"));}}
 }
 @Test void movingUnrelatedRevisitOutOfProviderFileRemovesItsFileDependency()throws Exception{
  var a=ClosurePlannerTest.ref("A.warc",1,"a","b");var b=provider("B.warc","b","http://b/",DATE,DIGEST);var c=provider("C.warc","c","http://c/",DATE,DIGEST);
  try(var combined=index(tmp,a,b,ClosurePlannerTest.ref("B.warc",2,"extra","c"),c);var p=new ClosurePlanner().plan(combined,List.of("A.warc"),ChoiceSet.empty(combined.corpusFingerprint()))){assertEquals(List.of("A.warc","B.warc","C.warc"),collect(p.includedFiles()).stream().map(FileFacts::relativePath).toList());}
  try(var split=index(tmp,a,b,ClosurePlannerTest.ref("Extra.warc",1,"extra","c"),c);var p=new ClosurePlanner().plan(split,List.of("A.warc"),ChoiceSet.empty(split.corpusFingerprint()))){assertEquals(List.of("A.warc","B.warc"),collect(p.includedFiles()).stream().map(FileFacts::relativePath).toList());}
 }
}
