package revisitledger.model;
import java.util.*;
public record PayloadEvidence(long length,Map<String,String> hexDigests,boolean complete) {
 public PayloadEvidence {if(length<0)throw new IllegalArgumentException("Negative payload length");hexDigests=Map.copyOf(hexDigests);}
}
