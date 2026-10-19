package revisitledger.resolve;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import revisitledger.index.*;
import revisitledger.model.*;

/** Conjunctive exact selectors. Digest candidates are used only if every explicit selector is absent. */
public final class SelectorResolver {
 private static final Set<String> PROFILES=Set.of(
  "http://netpreserve.org/warc/1.0/revisit/identical-payload-digest",
  "http://netpreserve.org/warc/1.1/revisit/identical-payload-digest");
 private static final CandidateResolution.Candidates EMPTY=()->new Cursor<>(){
  public boolean next(){return false;}public RecordFacts value(){throw new IllegalStateException("No current candidate");}public void close(){}
 };
 public CandidateResolution resolve(RecordIndex index,RecordKey key,ChoiceSet choices)throws IOException {
  Objects.requireNonNull(index);Objects.requireNonNull(key);Objects.requireNonNull(choices);
  var findings=new ArrayList<Finding>();var evidence=new LinkedHashMap<String,String>();
  if(!index.complete())return result("input_incomplete",EMPTY,null,"",evidence,findings);
  if(!index.corpusFingerprint().equals(choices.corpusFingerprint())){
   findings.add(finding(key,"stale_choice","Choices do not identify the current indexed corpus"));
   return result("input_incomplete",EMPTY,null,"",evidence,findings);
  }
  Optional<RecordFacts> source=index.record(key);
  if(source.isEmpty())return result("missing_in_corpus",EMPTY,null,"",evidence,findings);
  RecordFacts r=source.get();
  for(String field:List.of("WARC-Refers-To","WARC-Refers-To-Target-URI","WARC-Refers-To-Date","WARC-Payload-Digest","WARC-Profile"))r.header(field).ifPresent(v->evidence.put(field,v));
  String issue=recordIssue(r);
  if(!issue.equals("resolved"))return result(issue,EMPTY,null,"",evidence,r.findings());
  if(!r.type().equals("revisit")||!PROFILES.contains(r.header("WARC-Profile").orElse("")))return result("unsupported",EMPTY,null,"",evidence,findings);
  Optional<String> id=r.header("WARC-Refers-To"),target=r.header("WARC-Refers-To-Target-URI"),date=r.header("WARC-Refers-To-Date");
  if(date.isPresent()&&instant(date.get()).isEmpty())return result("unsupported",EMPTY,null,"",evidence,findings);
  Optional<DigestValue> digest;
  try{digest=r.header("WARC-Payload-Digest").map(DigestValue::parse);}catch(IllegalArgumentException e){return result("unsupported",EMPTY,null,"",evidence,findings);}
  boolean explicit=id.isPresent()||target.isPresent()||date.isPresent(),partial=target.isPresent()!=date.isPresent();
  CandidateResolution.Candidates candidates;String basis;
  if(partial){
   findings.add(finding(key,"partial_selector","Only one component of the URI/date selector was supplied"));
   if(id.isEmpty())return result("unsupported",EMPTY,null,"partial",evidence,findings);
   int idCount=atMostTwo(()->index.byId(id.get()));
   if(idCount==0)return result("missing_in_corpus",()->index.byId(id.get()),null,"record_id",evidence,findings);
   if(idCount>1)return result("ambiguous",()->index.byId(id.get()),null,"record_id",evidence,findings);
  }
  if(explicit){
   candidates=()->index.candidates(id,target,date);
   basis=id.isPresent()?(target.isPresent()||date.isPresent()?"combined":"record_id"):"uri_date";
  }else{
   if(digest.isEmpty())return result("unsupported",EMPTY,null,"digest",evidence,findings);
   candidates=()->index.concretePayloads(digest.get());basis="digest";
  }
  Optional<RecordKey> requested=choices.choice(key);RecordFacts chosen=null;int count=0;
  try(Cursor<RecordFacts> cursor=candidates.open()){
   while(cursor.next()){
    RecordFacts candidate=cursor.value();count=Math.min(2,count+1);
    if(requested.isPresent()){if(candidate.key().equals(requested.get()))chosen=candidate;}
    else if(count==1)chosen=candidate;
    if(requested.isEmpty()&&count==2)break;
   }
  }
  if(count==0){
   boolean conflicting=false;
   if(explicit&&id.isPresent()&&(target.isPresent()||date.isPresent())){
    conflicting=atMostTwo(()->index.byId(id.get()))>0||atMostTwo(()->index.candidates(Optional.empty(),target,date))>0;
   }
   return result(conflicting?"selector_conflict":"missing_in_corpus",candidates,null,basis,evidence,findings);
  }
  if(requested.isPresent()&&chosen==null){findings.add(finding(key,"invalid_choice","Choice is outside the enumerated candidates"));return result("selector_conflict",candidates,null,basis,evidence,findings);}
  if(requested.isEmpty()&&count>1)return result("ambiguous",candidates,null,basis,evidence,findings);
  if(requested.isPresent())basis="choice:"+basis;
  String candidateIssue=chosen.type().equals("revisit")?revisitIssue(chosen):concreteIssue(chosen);
  if(!candidateIssue.equals("resolved"))return result(candidateIssue,candidates,chosen.key(),basis,evidence,findings);
  if(chosen.payload().isPresent()&&digest.isPresent()&&!matches(digest.get(),chosen.payload().get()))return result("digest_mismatch",candidates,chosen.key(),basis,evidence,findings);
  return result("resolved",candidates,chosen.key(),basis,evidence,findings);
 }
 private static int atMostTwo(CandidateResolution.Candidates source)throws IOException{int n=0;try(var c=source.open()){while(n<2&&c.next())n++;}return n;}
 private static CandidateResolution result(String status,CandidateResolution.Candidates candidates,RecordKey chosen,String basis,Map<String,String> evidence,List<Finding> findings){return new CandidateResolution(status,candidates,Optional.ofNullable(chosen),basis,evidence,findings);}
 private static Finding finding(RecordKey key,String code,String message){return new Finding(code,Optional.of(key),message,Map.of());}
 static Optional<Instant> instant(String text){if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,9})?Z"))return Optional.empty();try{return Optional.of(Instant.parse(text));}catch(RuntimeException e){return Optional.empty();}}
 static String recordIssue(RecordFacts r){
  for(Finding f:r.findings())if(Set.of("input_incomplete","input_changed","resource_limit","http_framing","duplicate_singleton").contains(f.code()))return "input_incomplete";
  if(r.findings().stream().anyMatch(f->f.code().equals("digest_mismatch")))return "digest_mismatch";
  if(r.findings().stream().anyMatch(f->f.code().startsWith("unsupported")))return "unsupported";
  for(String field:List.of("WARC-Payload-Digest","WARC-Block-Digest"))if(r.header(field).isPresent())try{DigestValue.parse(r.header(field).get());}catch(IllegalArgumentException e){return "unsupported";}
  return "resolved";
 }
 static String revisitIssue(RecordFacts r){String issue=recordIssue(r);if(!issue.equals("resolved"))return issue;return PROFILES.contains(r.header("WARC-Profile").orElse(""))?"resolved":"unsupported";}
 static String concreteIssue(RecordFacts r){
  String issue=recordIssue(r);if(!issue.equals("resolved"))return issue;
  if(!Set.of("resource","response").contains(r.type())||r.payload().isEmpty()||!r.payload().get().complete())return "unsupported";
  if(r.header("WARC-Payload-Digest").isPresent()&&!matches(DigestValue.parse(r.header("WARC-Payload-Digest").get()),r.payload().get()))return "digest_mismatch";
  return "resolved";
 }
 static boolean matches(DigestValue digest,PayloadEvidence payload){return digest.lowercaseHex().equals(payload.hexDigests().get(digest.algorithm()));}
}
