package revisitledger.resolve;
import java.util.*;
import revisitledger.model.*;
/** An immediate verified dependency plus terminal independently computed byte evidence. */
public record WitnessEdge(RecordKey consumer, RecordKey provider, String selectionBasis,
 RecordKey terminal, PayloadEvidence payloadEvidence) {
 public WitnessEdge {Objects.requireNonNull(consumer);Objects.requireNonNull(provider);Objects.requireNonNull(selectionBasis);Objects.requireNonNull(terminal);Objects.requireNonNull(payloadEvidence);}
}
