package revisitledger.ingest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.netpreserve.jwarc.HttpParser;
import revisitledger.model.PayloadEvidence;

/** Streams entity bytes, removing transfer framing but never Content-Encoding. */
public final class PayloadHasher {
 private PayloadHasher() {}
 public static PayloadEvidence resource(InputStream input,long length)throws IOException {
  if(length<0)throw new IllegalArgumentException("Negative payload length");
  var digests=new Hashes();copy(input,length,digests);if(input.read()!=-1)throw framing();return digests.finish();
 }
 public static PayloadEvidence http(InputStream input,long length,int maxHeaders)throws IOException {
  Framing.Block block=new Framing.Block(input,length);HttpParser parser=Framing.http(Framing.header(block,maxHeaders,false));
  HttpFraming framing=validateHeaders(parser);var digests=new Hashes();
  int status=parser.status();
  if(status<200)throw new ScanException("unsupported_provider","Interim HTTP response sequences are unsupported");
  if(status==204||status==304){
   if(framing.chunked||block.remaining!=0||(status==204&&framing.length!=null))throw framing();
  }else if(framing.chunked)chunks(block,maxHeaders,digests);
  else {
   long n=framing.length==null?block.remaining:framing.length;
   if(n!=block.remaining)throw framing();copy(block,n,digests);
  }
  if(block.remaining!=0||input.read()!=-1)throw framing();return digests.finish();
 }
 static void revisit(InputStream input,long length,int maxHeaders)throws IOException {
  if(length==0)return;
  Framing.Block block=new Framing.Block(input,length);HttpParser parser=Framing.http(Framing.header(block,maxHeaders,false));
  validateHeaders(parser);if(block.remaining!=0||input.read()!=-1)throw framing();
 }
 private record HttpFraming(Long length,boolean chunked) {}
 private static HttpFraming validateHeaders(HttpParser p)throws IOException {
  var lengths=p.headers().all("Content-Length");var encodings=p.headers().all("Transfer-Encoding");
  if(lengths.size()>1||encodings.size()>1)throw framing();
  Long length=lengths.isEmpty()?null:Framing.length(lengths.get(0),"http_framing");
  boolean chunked=!encodings.isEmpty();
  if(chunked&&(!encodings.get(0).equalsIgnoreCase("chunked")||length!=null))throw framing();
  String version=p.version().toString();if(!version.equals("HTTP/1.0")&&!version.equals("HTTP/1.1"))throw new ScanException("unsupported_provider","Unsupported HTTP version");
  return new HttpFraming(length,chunked);
 }
 private static void chunks(Framing.Block input,int max,Hashes hashes)throws IOException {
  while(true){
   String line=line(input,max);long size=chunkSize(line);
   if(size==0){
    int total=0;
    while(true){String trailer=line(input,max-total);total+=trailer.length()+2;if(trailer.isEmpty())break;
     int colon=trailer.indexOf(':');if(colon<=0)throw framing();String name=trailer.substring(0,colon);for(int i=0;i<name.length();i++)if(!token(name.charAt(i)))throw framing();
     if(Set.of("content-length","transfer-encoding","trailer","host","content-encoding").contains(name.toLowerCase(Locale.ROOT)))throw framing();
     for(int i=colon+1;i<trailer.length();i++){char c=trailer.charAt(i);if(c!=9&&(c<32||c==127))throw framing();}
    }
    if(input.remaining!=0)throw framing();return;
   }
   if(size>input.remaining)throw framing();copy(input,size,hashes);if(input.read()!=13||input.read()!=10)throw framing();
  }
 }
 private static String line(InputStream in,int max)throws IOException {
  var out=new ByteArrayOutputStream(Math.min(Math.max(max,0),4096));int count=0;
  while(true){if(count>=max)throw new ScanException("resource_limit","HTTP framing header limit reached");int c=in.read();count++;if(c<0)throw framing();
   if(c==13){if(count>=max)throw new ScanException("resource_limit","HTTP framing header limit reached");if(in.read()!=10)throw framing();return out.toString(StandardCharsets.ISO_8859_1);}
   if(c==10||c==0)throw framing();out.write(c);
  }
 }
 private static long chunkSize(String s)throws IOException {
  int p=0;long n=0;
  while(p<s.length()){int v=Character.digit(s.charAt(p),16);if(v<0||s.charAt(p)>127)break;if(n>(Long.MAX_VALUE-v)/16)throw framing();n=n*16+v;p++;}
  if(p==0)throw framing();
  while(p<s.length()){
   p=ows(s,p);if(p==s.length()||s.charAt(p++)!=';')throw framing();p=ows(s,p);int start=p;while(p<s.length()&&token(s.charAt(p)))p++;if(p==start)throw framing();
   p=ows(s,p);if(p<s.length()&&s.charAt(p)=='='){
    p=ows(s,p+1);if(p==s.length())throw framing();
    if(s.charAt(p)=='"'){
     p++;boolean closed=false;while(p<s.length()){char c=s.charAt(p++);if(c=='"'){closed=true;break;}if(c=='\\'){if(p==s.length())throw framing();c=s.charAt(p++);if(c!=9&&(c<32||c==127))throw framing();}else if(c!=9&&(c<32||c==127))throw framing();}if(!closed)throw framing();
    }else {start=p;while(p<s.length()&&token(s.charAt(p)))p++;if(p==start)throw framing();}
   }
  }
  return n;
 }
 private static int ows(String s,int p){while(p<s.length()&&(s.charAt(p)==' '||s.charAt(p)=='\t'))p++;return p;}
 private static boolean token(char c){return c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"!#$%&'*+-.^_`|~".indexOf(c)>=0;}
 private static void copy(InputStream in,long count,Hashes hashes)throws IOException {
  byte[] buffer=new byte[8192];while(count>0){StrictArchiveInput.checkInterrupted();int n=in.read(buffer,0,(int)Math.min(buffer.length,count));if(n<0)throw Framing.incomplete();if(n==0)continue;hashes.update(buffer,n);count-=n;}
 }
 static MessageDigest digest(String name){try{return MessageDigest.getInstance(name);}catch(NoSuchAlgorithmException e){throw new IllegalStateException("Required JDK digest unavailable",e);}}
 private static ScanException framing(){return new ScanException("http_framing","HTTP payload framing is incomplete, conflicting or unsupported");}
 private static final class Hashes {
  final MessageDigest sha1=digest("SHA-1"),sha256=digest("SHA-256");long length;
  void update(byte[] b,int n){sha1.update(b,0,n);sha256.update(b,0,n);length+=n;}
  PayloadEvidence finish(){return new PayloadEvidence(length,Map.of("sha1",HexFormat.of().formatHex(sha1.digest()),"sha256",HexFormat.of().formatHex(sha256.digest())),true);}
 }
}
