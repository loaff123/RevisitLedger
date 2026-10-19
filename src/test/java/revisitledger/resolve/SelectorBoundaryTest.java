package revisitledger.resolve;
import java.lang.reflect.Proxy;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;import revisitledger.model.*;import static revisitledger.resolve.ResolutionFixtures.*;
/** Resolver unit boundary: real immutable record data with a fixed candidate source.
 * SQLite correctness is separately tested with the same cases in SelectorResolverTest. */
class SelectorBoundaryTest {
 static final String FP="f".repeat(64);
 RecordFacts p=provider("p.warc","<p>","http://p/",DATE,DIGEST);
 RecordIndex index(RecordFacts r){return (RecordIndex)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RecordIndex.class},(proxy,m,args)->switch(m.getName()){
  case "complete"->true;case "corpusFingerprint"->FP;case "record"->Optional.of(args[0].equals(r.key())?r:p);
  case "byId","candidates","concretePayloads"->cursor(List.of(p));
  default->throw new UnsupportedOperationException(m.getName());});}
 static Cursor<RecordFacts> cursor(List<RecordFacts> rows){return new Cursor<>(){int n=-1;public boolean next(){return ++n<rows.size();}public RecordFacts value(){return rows.get(n);}public void close(){}};}
 @Test void knownUniqueIdentityResolves()throws Exception{var r=revisit("r.warc",Map.of("WARC-Refers-To","<p>"));assertEquals("resolved",new SelectorResolver().resolve(index(r),r.key(),ChoiceSet.empty(FP)).status());}
 @Test void staleBindingIsIncomplete()throws Exception{var r=revisit("r.warc",Map.of("WARC-Refers-To","<p>"));assertEquals("input_incomplete",new SelectorResolver().resolve(index(r),r.key(),new ChoiceSet("old",Map.of(r.key(),p.key()))).status());}
 @Test void profileCannotBeWaived()throws Exception{var r=revisit("r.warc",Map.of("WARC-Profile","unknown"));assertEquals("unsupported",new SelectorResolver().resolve(index(r),r.key(),ChoiceSet.empty(FP)).status());}
 @Test void digestCannotBeWaived()throws Exception{var r=revisit("r.warc",Map.of("WARC-Refers-To","<p>","WARC-Payload-Digest","sha256:"+OTHER));assertEquals("digest_mismatch",new SelectorResolver().resolve(index(r),r.key(),ChoiceSet.empty(FP)).status());}
 @Test void outsideCandidateBindingCannotBeWaived()throws Exception{var r=revisit("r.warc",Map.of("WARC-Refers-To","<p>"));assertEquals("selector_conflict",new SelectorResolver().resolve(index(r),r.key(),new ChoiceSet(FP,Map.of(r.key(),key("none.warc")))).status());}
}
