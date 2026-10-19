package revisitledger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;
import revisitledger.ingest.*;
import revisitledger.model.*;
import revisitledger.plan.*;
import revisitledger.resolve.*;

class RootSymlinkTest {
 @TempDir Path temp;
 private static final List<String> FILES=List.of("A.warc","B.warc","C.warc");
 private Path corpus()throws IOException {
  Path parent=Files.createDirectory(temp.resolve("real"));Path root=Files.createDirectory(parent.resolve("corpus"));
  for(String file:FILES)Files.copy(Path.of("tests/fixtures/derived/whole-file",file),root.resolve(file));
  return root;
 }
 private Path alias(Path root,boolean ancestor)throws IOException {
  return ancestor?Files.createSymbolicLink(temp.resolve("alias"),root.getParent()).resolve("corpus"):Files.createSymbolicLink(temp.resolve("alias"),root);
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void inputResolutionRejectsRootAndAncestorSymlinks(boolean ancestor)throws Exception {
  Path root=corpus(),linked=alias(root,ancestor);
  ScanException failure=assertThrows(ScanException.class,()->InputFiles.resolve(linked,FILES));
  assertEquals("invalid_path",failure.code());
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void outputResolutionRejectsRootAndAncestorSymlinks(boolean ancestor)throws Exception {
  Path root=corpus(),linked=alias(root,ancestor);
  assertThrows(ScanException.class,()->InputFiles.checkOutput(linked,"report.json",List.of(),false));
  assertFalse(Files.exists(root.resolve("report.json")));
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void scannerRejectsRootAndAncestorSymlinksBeforeEmittingFacts(boolean ancestor)throws Exception {
  Path root=corpus(),linked=alias(root,ancestor);var facts=new ArrayList<Object>();
  RecordSink sink=new RecordSink(){public void record(RecordFacts r){facts.add(r);}public void file(FileFacts f){facts.add(f);}public void finding(Finding f){facts.add(f);}};
  assertThrows(ScanException.class,()->new ArchiveScanner().scan(linked,FILES,ScanLimits.defaults(),sink));
  assertTrue(facts.isEmpty());
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void rootAwarePlanCannotVerifyViaRootOrAncestorSymlink(boolean ancestor)throws Exception {
  Path root=corpus(),linked=alias(root,ancestor);
  try(var index=RecordIndex.create(temp,64L<<20)){
   index.seal(new ArchiveScanner().scan(root,FILES,ScanLimits.defaults(),index));
   try(var plan=new ClosurePlanner().plan(index,List.of("A.warc"),ChoiceSet.empty(index.corpusFingerprint()),linked)){
    assertTrue(plan.closureComplete());assertFalse(plan.inputsVerified());assertFalse(plan.complete());
    try(var blockers=plan.blockers()){assertTrue(blockers.next());assertEquals("input_changed",blockers.value().code());}
   }
  }
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void auditAndPlanRejectLinkedRootWithoutClobberOrPathDisclosure(boolean ancestor)throws Exception {
  Path root=corpus(),linked=alias(root,ancestor),files=Files.writeString(temp.resolve("files.txt"),String.join("\n",FILES)+"\n"),seeds=Files.writeString(temp.resolve("seeds.txt"),"A.warc\n");
  for(String command:List.of("audit","plan")){
   Path output=Files.writeString(temp.resolve(command+".json"),"sentinel");
   var args=new ArrayList<>(List.of(command,"--root",linked.toString(),"--files",files.toString(),"--json",output.toString(),"--overwrite","--scratch",temp.toString()));
   if(command.equals("plan"))args.addAll(List.of("--seeds",seeds.toString()));
   var stdout=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();
   assertEquals(2,Cli.run(args.toArray(String[]::new),new PrintStream(stdout),new PrintStream(stderr)));
   assertEquals("sentinel",Files.readString(output));assertFalse((stdout.toString()+stderr).contains(temp.toString()));
  }
 }
 @Test void lexicalParentCannotEraseSymlinkEvidence()throws Exception {
  Path root=corpus();Path linked=alias(root,true).resolve("..").resolve("corpus");
  assertThrows(ScanException.class,()->InputFiles.resolve(linked,FILES));
 }
}
