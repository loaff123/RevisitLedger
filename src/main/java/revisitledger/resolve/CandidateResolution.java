package revisitledger.resolve;
import java.io.*;import java.util.*;import revisitledger.index.*;import revisitledger.model.*;
/** Stable lazy candidates. Valid only while the sealed index remains open. */
public final class CandidateResolution {
 @FunctionalInterface public interface Candidates {Cursor<RecordFacts> open()throws IOException;}
 private final String status,basis;private final Candidates candidates;private final Optional<RecordKey> chosen;
 private final Map<String,String> evidence;private final List<Finding> findings;
 public CandidateResolution(String status,Candidates candidates,Optional<RecordKey> chosen,String basis,Map<String,String> evidence,List<Finding> findings){this.status=status;this.candidates=candidates;this.chosen=chosen;this.basis=basis;this.evidence=Map.copyOf(evidence);this.findings=List.copyOf(findings);}
 public String status(){return status;}public Cursor<RecordFacts> sortedCandidates()throws IOException{return candidates.open();}
 public Optional<RecordKey> optionalChosen(){return chosen;}public String selectionBasis(){return basis;}
 public Map<String,String> selectorEvidence(){return evidence;}public List<Finding> findings(){return findings;}
}
