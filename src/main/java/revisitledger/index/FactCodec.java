package revisitledger.index;
import revisitledger.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Versioned, bounded value encoding. No Java object deserialization or executable content. */
final class FactCodec {
 static final int MAX_BYTES=2<<20;
 interface Writer {void write(DataOutputStream out)throws IOException;}
 static byte[] encode(Writer writer)throws IOException {
  var bytes=new ByteArrayOutputStream();var bounded=new FilterOutputStream(bytes){int count;private void check(int n)throws IOException{if(n>MAX_BYTES-count)throw new IOException("Index row exceeds metadata limit");count+=n;}public void write(int n)throws IOException{check(1);out.write(n);}public void write(byte[] b,int o,int n)throws IOException{check(n);out.write(b,o,n);}};
  try(var out=new DataOutputStream(bounded)){writer.write(out);}return bytes.toByteArray();
 }
 static DataInputStream input(byte[] bytes)throws IOException{if(bytes.length>MAX_BYTES)throw new IOException("Index row exceeds metadata limit");return new DataInputStream(new ByteArrayInputStream(bytes));}
 static void string(DataOutputStream o,String s)throws IOException{if(s.length()>MAX_BYTES/2)throw new IOException("Index text exceeds metadata limit");byte[] b=s.getBytes(StandardCharsets.UTF_8);o.writeInt(b.length);o.write(b);}
 static String string(DataInputStream i)throws IOException{int n=i.readInt();if(n<0||n>i.available())throw new IOException("Invalid index value");return new String(i.readNBytes(n),StandardCharsets.UTF_8);}
 static void key(DataOutputStream o,RecordKey k)throws IOException{string(o,k.relativePath());o.writeLong(k.ordinal());}
 static RecordKey key(DataInputStream i)throws IOException{return new RecordKey(string(i),i.readLong());}
 static void map(DataOutputStream o,Map<String,String> m)throws IOException{o.writeInt(m.size());for(var e:new TreeMap<>(m).entrySet()){string(o,e.getKey());string(o,e.getValue());}}
 static int count(DataInputStream i)throws IOException{int n=i.readInt();if(n<0||n>i.available()/4)throw new IOException("Invalid index item count");return n;}
 static Map<String,String> map(DataInputStream i)throws IOException{int n=count(i);var m=new LinkedHashMap<String,String>();for(int x=0;x<n;x++)m.put(string(i),string(i));return m;}
 static void finding(DataOutputStream o,Finding f)throws IOException{string(o,f.code());o.writeBoolean(f.record().isPresent());if(f.record().isPresent())key(o,f.record().get());string(o,f.message());map(o,f.evidence());}
 static Finding finding(DataInputStream i)throws IOException{return new Finding(string(i),i.readBoolean()?Optional.of(key(i)):Optional.empty(),string(i),map(i));}
 static byte[] finding(Finding f)throws IOException{return encode(o->finding(o,f));}
 static Finding finding(byte[] b)throws IOException{try(var i=input(b)){return finding(i);}}
 static byte[] record(RecordFacts r)throws IOException{return encode(o->{o.writeInt(1);key(o,r.key());string(o,r.type());string(o,r.version());o.writeInt(r.rawHeaders().size());for(var e:new TreeMap<>(r.rawHeaders()).entrySet()){string(o,e.getKey());o.writeInt(e.getValue().size());for(String s:e.getValue())string(o,s);}o.writeBoolean(r.payload().isPresent());if(r.payload().isPresent()){var p=r.payload().get();o.writeLong(p.length());o.writeBoolean(p.complete());map(o,p.hexDigests());}o.writeInt(r.findings().size());for(var f:r.findings())finding(o,f);});}
 static RecordFacts record(byte[] b)throws IOException{try(var i=input(b)){if(i.readInt()!=1)throw new IOException("Unsupported index value version");var k=key(i);String type=string(i),version=string(i);int n=count(i);var h=new LinkedHashMap<String,List<String>>();for(int x=0;x<n;x++){String name=string(i);int values=count(i);var list=new ArrayList<String>();for(int y=0;y<values;y++)list.add(string(i));h.put(name,list);}Optional<PayloadEvidence> payload=Optional.empty();if(i.readBoolean()){long length=i.readLong();boolean complete=i.readBoolean();payload=Optional.of(new PayloadEvidence(length,map(i),complete));}int fc=count(i);var findings=new ArrayList<Finding>();for(int x=0;x<fc;x++)findings.add(finding(i));if(i.available()!=0)throw new IOException("Trailing index value bytes");return new RecordFacts(k,type,version,h,payload,findings);}}
}
