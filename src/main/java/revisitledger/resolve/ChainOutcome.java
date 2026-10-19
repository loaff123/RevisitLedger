package revisitledger.resolve;
import java.util.*;
import revisitledger.model.*;
/** One node's result. A chosen immediate record is a verified edge only when status is resolved. */
public record ChainOutcome(String status, RecordKey consumer, Optional<RecordKey> immediateProvider,
 String selectionBasis, Optional<RecordKey> terminal, Optional<PayloadEvidence> payloadEvidence) {
 public ChainOutcome {Objects.requireNonNull(status);Objects.requireNonNull(consumer);Objects.requireNonNull(immediateProvider);Objects.requireNonNull(selectionBasis);Objects.requireNonNull(terminal);Objects.requireNonNull(payloadEvidence);}
}
