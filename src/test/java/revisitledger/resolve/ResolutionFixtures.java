package revisitledger.resolve;
import java.io.*;import java.nio.file.*;import java.util.*;
import revisitledger.model.*;import revisitledger.index.*;
public final class ResolutionFixtures {
 public static final String PROFILE="http://netpreserve.org/warc/1.1/revisit/identical-payload-digest";
 public static final String DIGEST="a".repeat(64), OTHER="b".repeat(64), DATE="2020-01-01T00:00:00Z";
 public static RecordKey key(String file){return new RecordKey(file,1);}
 public static RecordFacts record(String file,long ordinal,String type,String id,String target,String date,String payloadDigest,Map<String,String> extras){
  var h=new LinkedHashMap<String,List<String>>();h.put("WARC-Record-ID",List.of(id));h.put("WARC-Date",List.of(date));
  if(target!=null)h.put("WARC-Target-URI",List.of(target));if(payloadDigest!=null)h.put("WARC-Payload-Digest",List.of("sha256:"+payloadDigest));
  if(type.equals("revisit"))h.put("WARC-Profile",List.of(PROFILE));extras.forEach((k,v)->h.put(k,List.of(v)));
  return new RecordFacts(new RecordKey(file,ordinal),type,"WARC/1.1",h,type.equals("resource")?Optional.of(new PayloadEvidence(9,Map.of("sha256",payloadDigest),true)):Optional.empty(),List.of());
 }
 public static RecordFacts provider(String file,String id,String target,String date,String digest){return record(file,1,"resource",id,target,date,digest,Map.of());}
 public static RecordFacts revisit(String file,Map<String,String> selectors){return record(file,1,"revisit","<"+file+">","http://capture/",DATE,DIGEST,selectors);}
 public static RecordIndex index(Path scratch,RecordFacts...records)throws IOException{
  RecordIndex index=RecordIndex.create(scratch,64L<<20);var counts=new TreeMap<String,Long>();
  for(var r:records){index.record(r);counts.merge(r.key().relativePath(),1L,Long::sum);}
  for(var e:counts.entrySet())index.file(new FileFacts(e.getKey(),"f".repeat(64),e.getValue(),true));
  index.seal(new ScanSummary(counts.size(),records.length,true));return index;
 }
 public static <T> List<T> collect(Cursor<T> cursor)throws IOException {try(cursor){var out=new ArrayList<T>();while(cursor.next())out.add(cursor.value());return out;}}
}
