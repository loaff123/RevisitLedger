package revisitledger.resolve;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import revisitledger.index.*;
import revisitledger.model.*;

/** Iterative functional-graph traversal. Stack, visiting marks and one outcome per node live on disk. */
public final class ChainResolver {
 private static final String OUTCOMES="chain_outcomes",META="chain_meta",STACK="chain_stack",ACTIVE="chain_active";
 private static final String READY="ready";
 private final SelectorResolver selectors=new SelectorResolver();

 /** Replace prior chain state. Any failed or interrupted pass leaves outcomes unavailable. */
 public ResolutionSummary resolveAll(RecordIndex index,ChoiceSet choices)throws IOException{
  Objects.requireNonNull(index);Objects.requireNonNull(choices);
  StateStore meta=index.state(META);meta.clear(); // Invalidate old success before checking interruption.
  checkInterrupted();
  StateStore outcomes=index.state(OUTCOMES),stack=index.state(STACK),active=index.state(ACTIVE);
  outcomes.clear();stack.clear();active.clear();
  boolean valid=choices.validFor(index),complete=index.complete()&&valid;
  var counts=new TreeMap<String,Long>();
  try(Cursor<RecordFacts> records=index.records()){
   while(records.next()){
    checkInterrupted();RecordFacts record=records.value();
    Optional<ChainOutcome> existing=read(outcomes,record.key());
    ChainOutcome result;
    if(existing.isPresent())result=existing.get();
    else if(record.type().equals("revisit")&&!valid){result=blocked(record.key(),"input_incomplete",Optional.empty(),"");write(outcomes,result);}
    else{resolveRoot(index,record.key(),choices,outcomes,stack,active);result=read(outcomes,record.key()).orElseThrow(()->new IOException("Missing completed chain state"));}
    if(record.type().equals("revisit")||(Set.of("resource","response").contains(record.type())&&result.status().equals("digest_mismatch"))){
     counts.merge(result.status(),1L,Long::sum);if(!result.status().equals("resolved"))complete=false;
    }
   }
  }
  checkInterrupted();
  // UUID is private cursor invalidation state, never deterministic report content.
  meta.put(READY,UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
  return new ResolutionSummary(counts,complete);
 }

 private void resolveRoot(RecordIndex index,RecordKey root,ChoiceSet choices,StateStore outcomes,StateStore stack,StateStore active)throws IOException{
  RecordKey current=root;long depth=0;ChainOutcome result;
  while(true){
   checkInterrupted();
   Optional<ChainOutcome> known=read(outcomes,current);
   if(known.isPresent()){result=known.get();break;}
   String stateKey=ChainStateCodec.key(current);Optional<byte[]> visiting=active.get(stateKey);
   if(visiting.isPresent()){
    if(visiting.get().length!=Long.BYTES)throw new IOException("Invalid chain visitation state");
    long cycleDepth=ByteBuffer.wrap(visiting.get()).getLong();
    ChainOutcome frame=readFrame(stack,cycleDepth);
    if(!frame.consumer().equals(current))throw new IOException("Chain locator key collision");
    result=blocked(current,"cycle",Optional.empty(),"");break;
   }
   Optional<RecordFacts> found=index.record(current);
   if(found.isEmpty()){result=blocked(current,"missing_in_corpus",Optional.empty(),"");write(outcomes,result);break;}
   RecordFacts record=found.get();
   if(!record.type().equals("revisit")){
    String status=SelectorResolver.concreteIssue(record);
    boolean knownBytes=Set.of("resource","response").contains(record.type())&&record.payload().isPresent()&&record.payload().get().complete();
    result=new ChainOutcome(status,current,Optional.empty(),"",knownBytes?Optional.of(current):Optional.empty(),knownBytes?record.payload():Optional.empty());
    write(outcomes,result);break;
   }
   CandidateResolution selected=selectors.resolve(index,current,choices);
   if(!selected.status().equals("resolved")){
    result=blocked(current,selected.status(),selected.optionalChosen(),selected.selectionBasis());write(outcomes,result);break;
   }
   RecordKey provider=selected.optionalChosen().orElseThrow(()->new IOException("Resolved selector omitted provider"));
   var frame=new ChainOutcome("pending",current,Optional.of(provider),selected.selectionBasis(),Optional.empty(),Optional.empty());
   stack.put(Long.toString(depth),ChainStateCodec.encodeOutcome(frame));
   active.put(stateKey,ByteBuffer.allocate(Long.BYTES).putLong(depth).array());
   if(depth==Long.MAX_VALUE)throw new IOException("Chain depth exceeds limit");
   depth++;current=provider;
  }
  while(depth>0){
   checkInterrupted();depth--;ChainOutcome frame=readFrame(stack,depth);stack.remove(Long.toString(depth));active.remove(ChainStateCodec.key(frame.consumer()));
   String status=result.status();
   if(status.equals("resolved")){
    PayloadEvidence payload=result.payloadEvidence().orElseThrow(()->new IOException("Resolved chain omitted payload evidence"));
    RecordFacts source=index.record(frame.consumer()).orElseThrow(()->new IOException("Missing chain consumer"));
    Optional<String> declared=source.header("WARC-Payload-Digest");
    if(declared.isPresent()){
     try{if(!SelectorResolver.matches(DigestValue.parse(declared.get()),payload))status="digest_mismatch";}catch(IllegalArgumentException e){status="unsupported";}
    }
   }
   result=new ChainOutcome(status,frame.consumer(),frame.immediateProvider(),frame.selectionBasis(),result.terminal(),result.payloadEvidence());
   write(outcomes,result);
  }
 }
 private static ChainOutcome readFrame(StateStore stack,long depth)throws IOException{return ChainStateCodec.decodeOutcome(stack.get(Long.toString(depth)).orElseThrow(()->new IOException("Missing chain stack frame")));}
 private static ChainOutcome blocked(RecordKey key,String status,Optional<RecordKey> provider,String basis){return new ChainOutcome(status,key,provider,basis,Optional.empty(),Optional.empty());}
 private static Optional<ChainOutcome> read(StateStore outcomes,RecordKey key)throws IOException{
  Optional<byte[]> bytes=outcomes.get(ChainStateCodec.key(key));if(bytes.isEmpty())return Optional.empty();
  ChainOutcome outcome=ChainStateCodec.decodeOutcome(bytes.get());if(!outcome.consumer().equals(key))throw new IOException("Chain locator key collision");return Optional.of(outcome);
 }
 private static void write(StateStore outcomes,ChainOutcome outcome)throws IOException{outcomes.put(ChainStateCodec.key(outcome.consumer()),ChainStateCodec.encodeOutcome(outcome));}
 private static byte[] ready(RecordIndex index)throws IOException{return index.state(META).get(READY).orElseThrow(()->new IOException("Chain resolution has no completed pass"));}
 private static void checkInterrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Chain resolution interrupted");}

 /** Outcomes remain valid until a new resolveAll pass starts or the index closes. */
 public static Optional<ChainOutcome> outcome(RecordIndex index,RecordKey key)throws IOException{ready(index);return read(index.state(OUTCOMES),key);}

 /**
  * Diagnostic trace: tail, one cycle, then the repeated cycle entry. Non-cycle outcomes
  * yield no rows. Floyd's algorithm determines a finite length using constant live heap;
  * neither this cursor nor the stored graph retains a path-sized collection.
  */
 public static Cursor<RecordKey> cycleWitness(RecordIndex index,RecordKey key)throws IOException{
  byte[] generation=ready(index);
  return new Cursor<>(){
   RecordKey current=key,value;long remaining=-1;boolean closed,done;
   public boolean next()throws IOException{
    sameGeneration(index,generation);
    if(closed){if(done)return false;throw new IOException("Cycle cursor is closed");}
    if(remaining<0)remaining=cycleLength(index,key,generation);
    if(remaining==0){done=true;close();return false;}
    value=current;remaining--;
    if(remaining>0)current=cycleNext(index,current,generation);
    return true;
   }
   public RecordKey value(){if(closed||value==null)throw new IllegalStateException("Cycle cursor has no current record");return value;}
   public void close(){closed=true;value=null;current=null;}
  };
 }
 private static void sameGeneration(RecordIndex index,byte[] generation)throws IOException{
  checkInterrupted();if(!Arrays.equals(generation,ready(index)))throw new IOException("Chain resolution changed during witness traversal");
 }
 private static RecordKey cycleNext(RecordIndex index,RecordKey key,byte[] generation)throws IOException{
  sameGeneration(index,generation);
  ChainOutcome outcome=read(index.state(OUTCOMES),key).orElseThrow(()->new IOException("Missing cycle outcome"));
  if(!outcome.status().equals("cycle"))throw new IOException("Inconsistent cycle outcome");
  return outcome.immediateProvider().orElseThrow(()->new IOException("Missing cycle dependency"));
 }
 private static long cycleLength(RecordIndex index,RecordKey root,byte[] generation)throws IOException{
  sameGeneration(index,generation);
  Optional<ChainOutcome> first=read(index.state(OUTCOMES),root);
  if(first.isEmpty()||!first.get().status().equals("cycle"))return 0;
  RecordKey slow=cycleNext(index,root,generation),fast=cycleNext(index,cycleNext(index,root,generation),generation);
  while(!slow.equals(fast)){slow=cycleNext(index,slow,generation);fast=cycleNext(index,cycleNext(index,fast,generation),generation);}
  long tail=0;slow=root;
  while(!slow.equals(fast)){slow=cycleNext(index,slow,generation);fast=cycleNext(index,fast,generation);tail=increment(tail);}
  long cycle=1;fast=cycleNext(index,slow,generation);
  while(!slow.equals(fast)){fast=cycleNext(index,fast,generation);cycle=increment(cycle);}
  try{return Math.addExact(Math.addExact(tail,cycle),1);}catch(ArithmeticException e){throw new IOException("Cycle trace exceeds length limit",e);}
 }
 private static long increment(long value)throws IOException{if(value==Long.MAX_VALUE)throw new IOException("Cycle trace exceeds length limit");return value+1;}

 /** Lazy immediate-edge sequence; blocked chains have no verified witness. Constant live heap per step. */
 public static Cursor<WitnessEdge> witness(RecordIndex index,RecordKey key)throws IOException{
  byte[] generation=ready(index);
  return new Cursor<>(){
   RecordKey next=key;WitnessEdge value;boolean closed,done;
   public boolean next()throws IOException{
    checkInterrupted();if(!Arrays.equals(generation,ready(index)))throw new IOException("Chain resolution changed during witness traversal");
    if(closed){if(done)return false;throw new IOException("Witness cursor is closed");}
    if(next==null){done=true;close();return false;}
    ChainOutcome result=read(index.state(OUTCOMES),next).orElseThrow(()->new IOException("Missing chain outcome"));
    if(!result.status().equals("resolved")||result.immediateProvider().isEmpty()){done=true;close();return false;}
    value=new WitnessEdge(result.consumer(),result.immediateProvider().get(),result.selectionBasis(),result.terminal().orElseThrow(()->new IOException("Missing chain terminal")),result.payloadEvidence().orElseThrow(()->new IOException("Missing chain evidence")));
    next=value.provider();return true;
   }
   public WitnessEdge value(){if(closed||value==null)throw new IllegalStateException("Witness cursor has no current edge");return value;}
   public void close(){closed=true;value=null;next=null;}
  };
 }
}
