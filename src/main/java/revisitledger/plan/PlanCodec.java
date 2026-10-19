package revisitledger.plan;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import revisitledger.model.*;
/** Small internal disk values; never object deserialization or archive-authored instructions. */
final class PlanCodec {
 private PlanCodec(){}
 static String key(RecordKey key){return key.relativePath()+"\0"+String.format(Locale.ROOT,"%020d",key.ordinal());}
 static void text(DataOutputStream out,String s)throws IOException{byte[] bytes=s.getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw new IOException("Plan metadata exceeds bound");out.writeInt(bytes.length);out.write(bytes);}
 static String text(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>16384||n>in.available())throw new IOException("Invalid plan value");return new String(in.readNBytes(n),StandardCharsets.UTF_8);}
 static byte[] finding(Finding f)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);text(out,f.code());out.writeBoolean(f.record().isPresent());if(f.record().isPresent()){text(out,f.record().get().relativePath());out.writeLong(f.record().get().ordinal());}text(out,f.message());out.writeInt(f.evidence().size());for(var e:new TreeMap<>(f.evidence()).entrySet()){text(out,e.getKey());text(out,e.getValue());}}if(bytes.size()>65536)throw new IOException("Plan metadata exceeds bound");return bytes.toByteArray();}
 static Finding finding(byte[] bytes)throws IOException{if(bytes.length>65536)throw new IOException("Plan metadata exceeds bound");try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=1)throw new IOException("Unsupported plan value");String code=text(in);Optional<RecordKey> key=in.readBoolean()?Optional.of(new RecordKey(text(in),in.readLong())):Optional.empty();String message=text(in);int n=in.readInt();if(n<0||n>64)throw new IOException("Invalid plan value");var evidence=new LinkedHashMap<String,String>();for(int x=0;x<n;x++)evidence.put(text(in),text(in));if(in.available()!=0)throw new IOException("Invalid plan value");return new Finding(code,key,message,evidence);}}
}
