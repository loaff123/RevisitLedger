package revisitledger.resolve;
import java.util.*;import revisitledger.model.RecordKey;
/** Caller-selected, corpus-bound bindings. A choice never overrides selector or byte evidence. */
public record ChoiceSet(String corpusFingerprint, Map<RecordKey,RecordKey> bindings) {
 public ChoiceSet {Objects.requireNonNull(corpusFingerprint);bindings=Map.copyOf(bindings);}
 public static ChoiceSet empty(String fingerprint){return new ChoiceSet(fingerprint,Map.of());}
 /** Validate once before a whole-corpus operation; per-record resolution still checks enumeration. */
 public boolean validFor(revisitledger.index.RecordIndex index)throws java.io.IOException {
  if(!index.complete()||!corpusFingerprint.equals(index.corpusFingerprint()))return false;
  for(var e:bindings.entrySet()){
   var consumer=index.record(e.getKey());
   if(consumer.isEmpty()||!consumer.get().type().equals("revisit")||index.record(e.getValue()).isEmpty())return false;
  }
  return true;
 }
 public Optional<RecordKey> choice(RecordKey record){return Optional.ofNullable(bindings.get(record));}
}
