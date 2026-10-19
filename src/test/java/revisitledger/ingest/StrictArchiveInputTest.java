package revisitledger.ingest;
import org.junit.jupiter.api.*;import java.io.*;import java.util.*;import revisitledger.model.*;
import static org.junit.jupiter.api.Assertions.*;import static revisitledger.ingest.IngestFixtures.*;
class StrictArchiveInputTest {
 byte[] read(byte[] b)throws Exception{try(var s=StrictArchiveInput.open(new ByteArrayInputStream(b),ScanLimits.defaults())){return s.readAllBytes();}}
 @Test void gzipCrcTailAndTruncationFail()throws Exception {
  byte[] g=gzip(ascii("WARC/1.1\r\n"));byte[] bad=g.clone();bad[bad.length-8]^=1;assertThrows(IOException.class,()->read(bad));
  for(int n=1;n<=8;n++){byte[] cut=Arrays.copyOf(g,g.length-n);assertThrows(IOException.class,()->read(cut));}
  for(byte[] tail:List.of(ascii("bad"),new byte[]{0},new byte[]{31,(byte)139}))assertThrows(IOException.class,()->read(cat(g,tail)));
 }
 @Test void concatenatedAndWholeGzipAgree()throws Exception {byte[] a=ascii("WARC/1.1\r\n"),b=ascii("second");assertArrayEquals(cat(a,b),read(cat(gzip(a),gzip(b))));assertArrayEquals(read(gzip(cat(a,b))),read(cat(gzip(a),gzip(b))));}
 @Test void exactExpansionBudgetAllowsEofButNotNextByte()throws Exception {
  for(byte[] b:List.of(ascii("1234"),gzip(ascii("1234"))))try(var s=StrictArchiveInput.open(new ByteArrayInputStream(b),new ScanLimits(4,1,10,1000,1))){assertArrayEquals(ascii("1234"),s.readAllBytes());}
  try(var s=StrictArchiveInput.open(new ByteArrayInputStream(gzip(ascii("12345"))),new ScanLimits(4,1,10,1000,1))){assertThrows(IOException.class,s::readAllBytes);}
 }
 @Test void enormousGzipMetadataFailsWithinBound()throws Exception {
  byte[] g=gzip(ascii("x"));g[3]=8;byte[] metadata=new byte[200000];Arrays.fill(metadata,(byte)'x');
  byte[] attack=cat(Arrays.copyOf(g,10),metadata,new byte[]{0},Arrays.copyOfRange(g,10,g.length));
  assertThrows(IOException.class,()->read(attack));
 }
 @Test void headerCrcMembersAreExplicitlyUnsupportedEvenInLaterMembers()throws Exception {
  byte[] g=gzip(ascii("hello"));g[3]=2;var crc=new java.util.zip.CRC32();crc.update(g,0,10);byte[] check={(byte)crc.getValue(),(byte)(crc.getValue()>>>8)};
  byte[] headerCrc=cat(Arrays.copyOf(g,10),check,Arrays.copyOfRange(g,10,g.length));
  for(byte[] prefix:List.of(new byte[0],gzip(ascii("first")))){
   var e=assertThrows(ScanException.class,()->read(cat(prefix,headerCrc)));assertEquals("unsupported_compression",e.code());
   byte[] corrupt=headerCrc.clone();corrupt[10]^=1;assertThrows(ScanException.class,()->read(cat(prefix,corrupt)));
  }
 }
 @Test void largeCallerReadDoesNotTriggerNoProgressBudget()throws Exception {
  byte[] payload=new byte[1<<20];new Random(9).nextBytes(payload);byte[] compressed=gzip(payload);byte[] decoded=new byte[payload.length];int position=0;
  try(var in=StrictArchiveInput.open(new ByteArrayInputStream(compressed),ScanLimits.defaults())){while(position<decoded.length){int n=in.read(decoded,position,decoded.length-position);assertTrue(n>0);position+=n;}assertEquals(-1,in.read());}
  assertArrayEquals(payload,decoded);
 }
}
