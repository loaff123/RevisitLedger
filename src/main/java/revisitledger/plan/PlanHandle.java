package revisitledger.plan;

import java.io.*;
import java.util.*;
import revisitledger.index.*;
import revisitledger.model.*;
import revisitledger.resolve.*;

/** Disk-backed plan snapshot. Close before its index; later plans do not change this one. */
public final class PlanHandle implements AutoCloseable {
 final RecordIndex index;
 final StateStore included,seeds,queue,edges,issues;
 private final Set<Cursor<?>> cursors=Collections.newSetFromMap(new IdentityHashMap<>());
 boolean graphComplete,verified,closed;
 PlanHandle(RecordIndex index)throws IOException{
  this.index=index;String name="plan."+UUID.randomUUID();
  included=index.state(name+".included");seeds=index.state(name+".seeds");queue=index.state(name+".queue");edges=index.state(name+".edges");issues=index.state(name+".issues");
 }
 /** Whether the selected sealed metadata has a sufficient, verified dependency closure. */
 public boolean closureComplete(){return graphComplete&&!closed&&index.complete();}
 /** Whether all corpus source bytes were rehashed in the explicit root-aware planning path. */
 public boolean inputsVerified(){return verified&&!closed&&index.complete();}
 /** Requires both metadata closure and current-source byte checks; never an atomic snapshot claim. */
 public boolean complete(){return closureComplete()&&inputsVerified();}
 private void requireOpen()throws IOException{if(closed)throw new IOException("Plan is closed");index.corpusFingerprint();}
 @FunctionalInterface private interface Mapper<T>{T map(StateEntry row)throws IOException;}
 private <T> Cursor<T> mapped(StateStore store,Mapper<T> mapper)throws IOException{
  requireOpen();Cursor<StateEntry> source=store.scan();
  Cursor<T> result=new Cursor<>(){
   T value;boolean shut;
   public boolean next()throws IOException{
    requireOpen();if(shut)return false;
    try{while(source.next()){T row=mapper.map(source.value());if(row!=null){value=row;return true;}}close();return false;}
    catch(IOException|RuntimeException e){try{close();}catch(IOException close){e.addSuppressed(close);}throw e;}
   }
   public T value(){if(shut||value==null)throw new IllegalStateException("Plan cursor has no current row");return value;}
   public void close()throws IOException{if(shut)return;shut=true;value=null;cursors.remove(this);source.close();}
  };
  cursors.add(result);return result;
 }
 public Cursor<FileFacts> includedFiles()throws IOException{return mapped(included,row->index.file(row.key()).orElseThrow(()->new IOException("Included file is absent from index")));}
 public Cursor<FileFacts> addedFiles()throws IOException{return mapped(included,row->seeds.get(row.key()).isPresent()?null:index.file(row.key()).orElseThrow(()->new IOException("Included file is absent from index")));}
 public Cursor<WitnessEdge> witnessEdges()throws IOException{return mapped(edges,row->ChainStateCodec.decodeEdge(row.value()));}
 public Cursor<Finding> blockers()throws IOException{return mapped(issues,row->PlanCodec.finding(row.value()));}
 @Override public void close()throws IOException{
  if(closed)return;IOException error=null;
  for(var cursor:List.copyOf(cursors))try{cursor.close();}catch(IOException e){if(error==null)error=e;else error.addSuppressed(e);}
  closed=true;
  for(var store:List.of(included,seeds,queue,edges,issues))try{store.clear();}catch(IOException e){if(error==null)error=e;else error.addSuppressed(e);}
  if(error!=null)throw error;
 }
}
