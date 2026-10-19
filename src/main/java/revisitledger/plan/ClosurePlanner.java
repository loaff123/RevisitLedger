package revisitledger.plan;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import revisitledger.index.*;
import revisitledger.ingest.InputFiles;
import revisitledger.model.*;
import revisitledger.resolve.*;

/** Least whole-file fixed point for the chosen, verified chains; not a minimum-size archive set. */
public final class ClosurePlanner {
 private static final byte[] MARKER={1};
 public PlanHandle plan(RecordIndex index,List<String> seeds,ChoiceSet choices)throws IOException{return compute(index,seeds,choices,null);}
 public PlanHandle plan(RecordIndex index,List<String> seeds,ChoiceSet choices,Path root)throws IOException{return compute(index,seeds,choices,Objects.requireNonNull(root));}
 private PlanHandle compute(RecordIndex index,List<String> seeds,ChoiceSet choices,Path root)throws IOException {
  Objects.requireNonNull(index);Objects.requireNonNull(seeds);Objects.requireNonNull(choices);checkInterrupt();
  PlanHandle plan=new PlanHandle(index);
  try{
   boolean choiceValid=choices.validFor(index);
   for(String seed:seeds){checkInterrupt();if(index.file(seed).isEmpty())throw new IOException("Seed file is not present in the indexed corpus");plan.seeds.put(seed,MARKER);include(plan,seed);}
   new ChainResolver().resolveAll(index,choices);
   if(!index.complete()||!choiceValid)issue(plan,"0",new Finding("input_incomplete",Optional.empty(),"The indexed corpus or choices are incomplete",Map.of()));
   String file;
   while((file=pop(plan.queue))!=null){
    checkInterrupt();
    try(Cursor<RecordFacts> records=index.recordsInFile(file)){
     while(records.next()){
      checkInterrupt();RecordFacts r=records.value();Optional<ChainOutcome> result=ChainResolver.outcome(index,r.key());
      if(r.type().equals("revisit")){
       ChainOutcome outcome=result.orElseThrow(()->new IOException("Missing completed chain outcome"));
       if(!outcome.status().equals("resolved")){recordIssue(plan,r.key(),outcome.status());continue;}
       RecordKey provider=outcome.immediateProvider().orElseThrow(()->new IOException("Resolved revisit lacks an immediate provider"));
       WitnessEdge edge=edge(outcome);plan.edges.put(PlanCodec.key(r.key()),ChainStateCodec.encodeEdge(edge));
       include(plan,provider.relativePath());
      }else if(result.isPresent()&&Set.of("resource","response").contains(r.type())&&result.get().status().equals("digest_mismatch"))recordIssue(plan,r.key(),"digest_mismatch");
     }
    }
   }
   // Second independent membership pass over every record in every included file.
   // This rechecks the selected subset against the same immutable corpus and bindings.
   verifySubset(plan,index);
   plan.graphComplete=!hasAny(plan.issues)&&index.complete()&&choiceValid;
   plan.verified=root!=null&&verifyInputs(plan,index,root);
   return plan;
  }catch(IOException|RuntimeException e){try{plan.close();}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e;}
 }
 private static WitnessEdge edge(ChainOutcome o)throws IOException{return new WitnessEdge(o.consumer(),o.immediateProvider().orElseThrow(()->new IOException("Missing verified edge")),o.selectionBasis(),o.terminal().orElseThrow(()->new IOException("Missing terminal evidence")),o.payloadEvidence().orElseThrow(()->new IOException("Missing terminal evidence")));}
 private static void include(PlanHandle plan,String file)throws IOException{if(plan.index.file(file).isEmpty())throw new IOException("Provider file is absent from index");if(plan.included.get(file).isEmpty()){plan.included.put(file,MARKER);plan.queue.put(file,MARKER);}}
 private static String pop(StateStore queue)throws IOException{String value=null;try(var cursor=queue.scan()){if(cursor.next())value=cursor.value().key();}if(value!=null)queue.remove(value);return value;}
 private static boolean hasAny(StateStore store)throws IOException{try(var c=store.scan()){return c.next();}}
 private static void issue(PlanHandle plan,String key,Finding finding)throws IOException{plan.issues.put(key,PlanCodec.finding(finding));}
 private static void recordIssue(PlanHandle plan,RecordKey key,String code)throws IOException{issue(plan,"1"+PlanCodec.key(key),new Finding(code,Optional.of(key),"Selected file contains an unresolved dependency or integrity error",Map.of()));}
 private static void verifySubset(PlanHandle plan,RecordIndex index)throws IOException{
  try(var files=plan.included.scan()){
   while(files.next()){
    checkInterrupt();String file=files.value().key();
    if(!index.file(file).orElseThrow().complete())issue(plan,"0file:"+file,new Finding("input_incomplete",Optional.empty(),"Included file was not completely scanned",Map.of("file",file)));
    try(var records=index.recordsInFile(file)){
     while(records.next()){
      checkInterrupt();var r=records.value();var result=ChainResolver.outcome(index,r.key());
      if(r.type().equals("revisit")){
       var o=result.orElseThrow(()->new IOException("Missing completed chain outcome"));
       if(!o.status().equals("resolved")){recordIssue(plan,r.key(),o.status());continue;}
       var provider=o.immediateProvider().orElseThrow(()->new IOException("Missing verified edge"));
       if(plan.included.get(provider.relativePath()).isEmpty()||plan.edges.get(PlanCodec.key(r.key())).isEmpty())recordIssue(plan,r.key(),"input_incomplete");
      }else if(result.isPresent()&&Set.of("resource","response").contains(r.type())&&result.get().status().equals("digest_mismatch"))recordIssue(plan,r.key(),"digest_mismatch");
     }
    }
   }
  }
 }
 private static boolean verifyInputs(PlanHandle plan,RecordIndex index,Path root)throws IOException{
  boolean verified=true;Path base;
  try{base=InputFiles.checkedRoot(root);}catch(IOException e){issue(plan,"0root",new Finding("input_changed",Optional.empty(),"Corpus root cannot be reverified",Map.of()));return false;}
  try(var files=index.files()){
   while(files.next()){
    checkInterrupt();FileFacts f=files.value();boolean match=false;
    try{
     Path path=InputFiles.resolve(base,List.of(f.relativePath())).get(0);
     BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
     if(!before.isRegularFile())throw new IOException("Input is not regular");
     MessageDigest digest=MessageDigest.getInstance("SHA-256");
     try(InputStream in=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){checkInterrupt();digest.update(buffer,0,n);}}
     InputFiles.resolve(base,List.of(f.relativePath()));
     BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
     match=after.isRegularFile()&&Objects.equals(before.fileKey(),after.fileKey())&&before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())&&HexFormat.of().formatHex(digest.digest()).equals(f.sha256());
    }catch(InterruptedIOException e){throw e;}catch(IOException|IllegalArgumentException e){match=false;}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    if(!match){verified=false;issue(plan,"0changed:"+f.relativePath(),new Finding("input_changed",Optional.empty(),"Corpus bytes changed or a file could not be stably reverified",Map.of("file",f.relativePath())));}
   }
  }
  return verified&&index.complete();
 }
 private static void checkInterrupt()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Dependency planning interrupted");}
}
