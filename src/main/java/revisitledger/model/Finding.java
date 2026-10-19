package revisitledger.model;
import java.util.*;
/** Messages are fixed diagnostic text, never parser errors containing archive data. */
public record Finding(String code,Optional<RecordKey> record,String message,Map<String,String> evidence) {
 public Finding {Objects.requireNonNull(code);Objects.requireNonNull(record);Objects.requireNonNull(message);evidence=Map.copyOf(evidence);}
}
