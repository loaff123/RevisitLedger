package revisitledger.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import revisitledger.index.*;
import revisitledger.model.*;
import revisitledger.plan.*;
import revisitledger.resolve.*;

/** Deterministic bounded-memory report over an already resolved sealed index. Does not resolve again. */
public final class ReportWriter {
 public static final String TOOL_VERSION="0.1.0", SUPPORT_PROFILE="revisitledger/identical-payload-v1";
 private static final Set<String> HEADERS=Set.of("warc-type","warc-record-id","warc-date","warc-target-uri","warc-profile","warc-refers-to","warc-refers-to-target-uri","warc-refers-to-date","warc-block-digest","warc-payload-digest","warc-truncated","warc-segment-number","warc-segment-origin-id","warc-segment-total-length","content-type","content-length");
 public static final List<String> WARNINGS=List.of(
  "Completion covers supported local revisit payload dependencies only; it does not establish full WARC conformance, replayability, authenticity or provenance.",
  "Input verification compares each file when observed; it is not an atomic corpus snapshot and later changes are possible.",
  "SHA-1 consistency does not establish collision resistance. Additional SHA-256 evidence does not strengthen a historical SHA-1 declaration.",
  "Relevant raw WARC header values may themselves contain sensitive information. Payload bytes and environment-specific absolute paths are not added to this report.",
  "A complete chosen closure is sufficient for its bindings, not a globally minimum file set.");
 private ReportWriter(){}
 public static void write(RecordIndex index,PlanHandle plan,ChoiceSet choices,ScanLimits limits,OutputStream out)throws IOException{write(index,plan,choices,limits,out,plan==null?"audit":"plan");}
 public static void writeAudit(RecordIndex index,PlanHandle fullCorpusPlan,ChoiceSet choices,ScanLimits limits,OutputStream out)throws IOException{write(index,fullCorpusPlan,choices,limits,out,"audit");}
 private static void write(RecordIndex index,PlanHandle plan,ChoiceSet choices,ScanLimits limits,OutputStream output,String mode)throws IOException{
  Objects.requireNonNull(index);Objects.requireNonNull(choices);Objects.requireNonNull(limits);Objects.requireNonNull(output);
  boolean resolved=index.complete()&&choices.validFor(index);
  try(var records=index.records()){while(records.next()){checkInterrupt();var record=records.value();var outcome=ChainResolver.outcome(index,record.key()).orElseThrow(()->new IOException("Report requires completed chain outcomes"));if(record.type().equals("revisit")&&!outcome.status().equals("resolved")||Set.of("resource","response").contains(record.type())&&outcome.status().equals("digest_mismatch"))resolved=false;}}
  boolean closure=plan==null?resolved:plan.closureComplete(),verified=plan!=null&&plan.inputsVerified(),complete=closure&&verified;
  var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));var j=new JsonOutput(writer);
  j.raw("{");field(j,"schemaVersion",1);next(j,"toolVersion",TOOL_VERSION);next(j,"supportProfile",SUPPORT_PROFILE);next(j,"mode",mode);
  next(j,"limits",ordered("maxExpandedBytes",limits.maxExpandedBytes(),"maxRecords",limits.maxRecords(),"maxBlockBytes",limits.maxBlockBytes(),"maxHeaderBytes",limits.maxHeaderBytes(),"maxScratchBytes",limits.maxScratchBytes()));
  next(j,"corpusFingerprint",index.corpusFingerprint());next(j,"scanComplete",index.complete());next(j,"resolutionComplete",resolved);next(j,"closureComplete",closure);next(j,"inputsVerified",verified);next(j,"complete",complete);
  j.raw(",\"files\":");array(j,index.files(),f->file(f));
  j.raw(",\"records\":[");boolean first=true;var selector=new SelectorResolver();try(var records=index.records()){while(records.next()){checkInterrupt();if(!first)j.raw(",");first=false;record(j,index,records.value(),choices,selector);}}j.raw("]");
  j.raw(",\"choices\":[");first=true;var sorted=new TreeMap<RecordKey,RecordKey>(Comparator.comparing(RecordKey::relativePath,Explain::comparePaths).thenComparingLong(RecordKey::ordinal));sorted.putAll(choices.bindings());
  for(var e:sorted.entrySet()){if(!first)j.raw(",");first=false;j.value(ordered("consumer",key(e.getKey()),"provider",key(e.getValue())));}j.raw("]");
  j.raw(",\"includedFiles\":");if(plan==null)array(j,index.files(),FileFacts::relativePath);else array(j,plan.includedFiles(),FileFacts::relativePath);
  j.raw(",\"addedFiles\":");if(plan==null)j.raw("[]");else array(j,plan.addedFiles(),FileFacts::relativePath);
  j.raw(",\"witnessEdges\":");if(plan!=null)array(j,plan.witnessEdges(),ReportWriter::edge);else{
   j.raw("[");first=true;try(var records=index.records()){while(records.next()){var o=ChainResolver.outcome(index,records.value().key()).orElseThrow();if(!o.status().equals("resolved")||o.immediateProvider().isEmpty())continue;if(!first)j.raw(",");first=false;j.value(edge(new WitnessEdge(o.consumer(),o.immediateProvider().get(),o.selectionBasis(),o.terminal().orElseThrow(),o.payloadEvidence().orElseThrow())));}}j.raw("]");
  }
  j.raw(",\"blockers\":");if(plan!=null)array(j,plan.blockers(),ReportWriter::finding);else{
   j.raw("[");first=true;try(var records=index.records()){while(records.next()){var r=records.value();var o=ChainResolver.outcome(index,r.key()).orElseThrow();if(!(r.type().equals("revisit")&&!o.status().equals("resolved")||Set.of("resource","response").contains(r.type())&&o.status().equals("digest_mismatch")))continue;if(!first)j.raw(",");first=false;j.value(finding(new Finding(o.status(),Optional.of(r.key()),"Corpus contains an unresolved dependency or integrity error",Map.of())));}}j.raw("]");
  }
  j.raw(",\"findings\":");array(j,index.findings(),ReportWriter::finding);next(j,"warnings",WARNINGS);j.raw("}\n");writer.flush();
 }
 private static void record(JsonOutput j,RecordIndex index,RecordFacts r,ChoiceSet choices,SelectorResolver selectors)throws IOException{
  var headers=new TreeMap<String,Object>();r.rawHeaders().forEach((k,v)->{if(HEADERS.contains(k.toLowerCase(Locale.ROOT)))headers.put(k,v);});
  j.raw("{");field(j,"key",key(r.key()));next(j,"type",r.type());next(j,"version",r.version());next(j,"rawHeaders",headers);
  next(j,"normalizedDigests",ordered("block",digest(r.header("WARC-Block-Digest")),"payload",digest(r.header("WARC-Payload-Digest"))));next(j,"payloadEvidence",r.payload().map(ReportWriter::payload).orElse(null));
  j.raw(",\"selector\":");if(!r.type().equals("revisit"))j.raw("null");else{
   CandidateResolution c=selectors.resolve(index,r.key(),choices);j.raw("{");field(j,"status",c.status());next(j,"selectionBasis",c.selectionBasis());next(j,"chosen",c.optionalChosen().map(ReportWriter::key).orElse(null));next(j,"evidence",new TreeMap<>(c.selectorEvidence()));
   j.raw(",\"candidates\":");array(j,c.sortedCandidates(),x->key(x.key()));next(j,"findings",c.findings().stream().map(ReportWriter::finding).toList());j.raw("}");
  }
  var o=ChainResolver.outcome(index,r.key()).orElseThrow(()->new IOException("Missing completed chain outcome"));
  next(j,"outcome",ordered("status",o.status(),"immediateProvider",o.immediateProvider().map(ReportWriter::key).orElse(null),"selectionBasis",o.selectionBasis(),"terminal",o.terminal().map(ReportWriter::key).orElse(null),"payloadEvidence",o.payloadEvidence().map(ReportWriter::payload).orElse(null)));
  next(j,"findings",r.findings().stream().map(ReportWriter::finding).toList());j.raw("}");
 }
 static Map<String,Object> ordered(Object...pairs){var map=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
 static Map<String,Object> key(RecordKey k){return ordered("path",k.relativePath(),"ordinal",k.ordinal());}
 static Map<String,Object> file(FileFacts f){return ordered("path",f.relativePath(),"sha256",f.sha256(),"recordCount",f.recordCount(),"complete",f.complete());}
 static Map<String,Object> payload(PayloadEvidence p){return ordered("length",p.length(),"hexDigests",new TreeMap<>(p.hexDigests()),"complete",p.complete());}
 static Object digest(Optional<String> raw){if(raw.isEmpty())return null;try{DigestValue d=DigestValue.parse(raw.get());return ordered("algorithm",d.algorithm(),"hex",d.lowercaseHex());}catch(IllegalArgumentException e){return null;}}
 static Map<String,Object> finding(Finding f){return ordered("code",f.code(),"record",f.record().map(ReportWriter::key).orElse(null),"message",f.message(),"evidence",new TreeMap<>(f.evidence()));}
 static Map<String,Object> edge(WitnessEdge e){return ordered("consumer",key(e.consumer()),"provider",key(e.provider()),"selectionBasis",e.selectionBasis(),"terminal",key(e.terminal()),"payloadEvidence",payload(e.payloadEvidence()));}
 private static void field(JsonOutput j,String name,Object value)throws IOException{j.name(name);j.value(value);}
 private static void next(JsonOutput j,String name,Object value)throws IOException{j.raw(",");field(j,name,value);}
 @FunctionalInterface private interface Mapper<T>{Object map(T value)throws IOException;}
 private static <T> void array(JsonOutput j,Cursor<T> cursor,Mapper<T> mapper)throws IOException{j.raw("[");boolean first=true;try(cursor){while(cursor.next()){checkInterrupt();if(!first)j.raw(",");first=false;j.value(mapper.map(cursor.value()));}}j.raw("]");}
 static void checkInterrupt()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Report operation interrupted");}
}
