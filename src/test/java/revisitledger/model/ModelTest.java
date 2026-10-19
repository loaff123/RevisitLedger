package revisitledger.model;
import org.junit.jupiter.api.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class ModelTest {
 @Test void digestEncodingsCompareByBytes(){String hex="0fa09f05a9dc781d6ef76455951d6f3271aa938e";assertEquals(DigestValue.parse("sha1:"+hex),DigestValue.parse("SHA-1:B6QJ6BNJ3R4B23XXMRKZKHLPGJY2VE4O"));assertEquals(DigestValue.parse("sha1:"+hex),DigestValue.parse("sha1:D6CfBanceB1u92RVlR1vMnGqk44="));}
 @Test void digestAlphabetLengthAndPaddingAreStrict(){for(String bad:List.of("md5:deadbeef","sha1:ff","sha1:B6QJ6BNJ3R4B23XXMRKZKHLPGJY2VE4!","sha1:D6CfBan ceB1u92RVlR1vMnGqk44="))assertThrows(IllegalArgumentException.class,()->DigestValue.parse(bad));}
 @Test void recordsAndEvidenceAreDeeplyImmutable(){var list=new ArrayList<>(List.of("value"));var map=new HashMap<String,List<String>>();map.put("X",list);var r=new RecordFacts(new RecordKey("a.warc",1),"resource","WARC/1.1",map,Optional.empty(),List.of());list.add("changed");map.clear();assertEquals(List.of("value"),r.rawHeaders().get("X"));assertThrows(UnsupportedOperationException.class,()->r.rawHeaders().get("X").add("x"));}
 @Test void limitsAndLocatorsRejectInvalidValues(){assertThrows(IllegalArgumentException.class,()->new RecordKey("a.warc",0));assertThrows(IllegalArgumentException.class,()->new ScanLimits(0,1,1,1,1));}
 @Test void sha256AllEncodingsAndPadBitsAreStrict(){
  String hex="bd12a7c765ee097e8fbb8b8b11c8d84274f168e3be2d03b5c1d63e8ebbf61556";
  var d=DigestValue.parse("sha256:"+hex);
  assertEquals(d,DigestValue.parse("sha-256:vRKnx2XuCX6Pu4uLEcjYQnTxaOO+LQO1wdY+jrv2FVY="));
  assertEquals(d,DigestValue.parse("sha256:XUJKPR3F5YEX5D53ROFRDSGYIJ2PC2HDXYWQHNOB2Y7I5O7WCVLA".replace(" ","")));
  assertThrows(IllegalArgumentException.class,()->DigestValue.parse("sha256:XUJKPR3F5YEX5D53ROFRDSGYIJ2PC2HDXYWQHNOB2Y7I5O7WCVLB"));
 }
}
