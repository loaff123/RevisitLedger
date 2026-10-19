package revisitledger.model;
import java.util.*;
/** Header spelling and value strings are retained as returned by strict jwarc. */
public record RecordFacts(RecordKey key,String type,String version,Map<String,List<String>> rawHeaders,Optional<PayloadEvidence> payload,List<Finding> findings) {
 public RecordFacts {
  Objects.requireNonNull(key);Objects.requireNonNull(type);Objects.requireNonNull(version);Objects.requireNonNull(payload);
  var copy=new LinkedHashMap<String,List<String>>();rawHeaders.forEach((k,v)->copy.put(k,List.copyOf(v)));rawHeaders=Collections.unmodifiableMap(copy);findings=List.copyOf(findings);
 }
 public Optional<String> header(String key){return rawHeaders.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).flatMap(e->e.getValue().stream()).findFirst();}
}
