package revisitledger.resolve;
import java.util.*;
/** Counts each revisit once and each concrete-provider digest error once. */
public record ResolutionSummary(Map<String,Long> counts, boolean complete) {
 public ResolutionSummary {counts=Collections.unmodifiableMap(new TreeMap<>(counts));}
}
