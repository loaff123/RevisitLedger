package revisitledger.report;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** UTF-8, duplicate rejecting, schema-directed streaming parser. Arrays never accumulate on heap. */
final class StrictJson implements AutoCloseable {
 interface Observer {default void before(ReportSchema.Node node,StrictJson input)throws IOException{}default void after(ReportSchema.Node node,Object value,StrictJson input)throws IOException{}}
 private final Reader reader;private final int maxString;private int look=-2;private long position;Writer capture;
 StrictJson(Path path,int maxString,long maxBytes)throws IOException{
  this.maxString=maxString;InputStream in=Files.newInputStream(path);if(maxBytes!=Long.MAX_VALUE)in=new FilterInputStream(in){long left=maxBytes;public int read()throws IOException{int c=super.read();if(c>=0&&--left<0)throw new IOException("JSON byte limit exceeded");return c;}public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,(int)Math.min(len,Math.max(1,left+1)));if(n>0&&(left-=n)<0)throw new IOException("JSON byte limit exceeded");return n;}};
  reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),8192);
 }
 Object read(ReportSchema.Node schema,Observer observer)throws IOException{Object value=value(schema,observer,0);space();if(peek()!=-1)throw error("Trailing JSON content");return value;}
 private Object value(ReportSchema.Node n,Observer observer,int depth)throws IOException{
  ReportWriter.checkInterrupt();if(depth>24)throw error("JSON nesting limit exceeded");space();observer.before(n,this);Object result;
  if(peek()=='n'&&n.nullable){literal("null");result=null;}
  else result=switch(n.kind){
   case STRING->{String s=string(Math.min(maxString,n.maxLength));if(n.pattern!=null&&!s.matches(n.pattern)||n.extra!=null&&!n.extra.test(s))throw error("JSON string is outside schema");yield s;}
   case INTEGER->{long v=integer();if(v<n.min||v>n.max)throw error("JSON integer is outside schema");yield v;}
   case BOOLEAN->{if(peek()=='t'){literal("true");yield true;}literal("false");yield false;}
   case ARRAY->{expect('[');long count=0;space();if(peek()!=']')while(true){if(++count>n.maxItems)throw error("JSON array limit exceeded");value(n.item,observer,depth+1);space();if(peek()!=',')break;take();space();}expect(']');yield null;}
   case OBJECT->{
    expect('{');var seen=new HashSet<String>();var data=n.fields==null&&!"digests".equals(n.tag)?null:new LinkedHashMap<String,Object>();space();
    if(peek()!='}')while(true){String key=string(65536);if(!seen.add(key))throw error("Duplicate JSON member");if(seen.size()>(n.fields==null?n.maxProperties:n.fields.size()))throw error("JSON object member limit exceeded");ReportSchema.Node child=n.fields==null?n.item:n.fields.get(key);if(child==null||n.fields==null&&n.extra!=null&&!n.extra.test(key))throw error("Unknown JSON member");space();expect(':');Object v=value(child,observer,depth+1);if(data!=null)data.put(key,v);space();if(peek()!=',')break;take();space();}
    expect('}');if(n.fields!=null&&!seen.equals(n.fields.keySet()))throw error("Missing required JSON member");yield data;
   }
  };
  if(result!=null&&n.enumeration!=null&&!n.enumeration.contains(result))throw error("Unsupported JSON enum value");if(result!=null&&"digest".equals(n.tag)){var m=ChoiceReader.map(result);if(((String)m.get("hex")).length()!=(m.get("algorithm").equals("sha1")?40:64))throw error("Digest algorithm and length disagree");}
  if(result!=null&&"digests".equals(n.tag))for(var e:ChoiceReader.map(result).entrySet())if(((String)e.getValue()).length()!=(e.getKey().equals("sha1")?40:64))throw error("Computed digest algorithm and length disagree");
  observer.after(n,result,this);return result;
 }
 private long integer()throws IOException{
  var s=new StringBuilder();int c=peek();if(c=='-'){s.append((char)take());c=peek();}if(c<'0'||c>'9')throw error("Expected JSON integer");s.append((char)take());if(c=='0'&&peek()>='0'&&peek()<='9')throw error("Leading zero in JSON number");while(peek()>='0'&&peek()<='9'){if(s.length()>19)throw error("JSON integer overflow");s.append((char)take());}if(peek()=='.'||peek()=='e'||peek()=='E')throw error("Expected integral JSON number");try{return Long.parseLong(s.toString());}catch(NumberFormatException e){throw error("JSON integer overflow");}
 }
 private String string(int limit)throws IOException{
  expect('"');var s=new StringBuilder();while(true){int c=take();if(c==-1)throw error("Truncated JSON string");if(c=='"')break;if(c<32)throw error("Unescaped JSON control character");if(c=='\\'){
   c=take();c=switch(c){case '"','\\','/'->c;case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'u'->unicode();default->throw error("Invalid JSON escape");};
  }
  if(Character.isHighSurrogate((char)c)){s.append((char)c);int low=take();if(low=='\\'){if(take()!='u')throw error("Unpaired JSON surrogate");low=unicode();}if(low<0||!Character.isLowSurrogate((char)low))throw error("Unpaired JSON surrogate");s.append((char)low);}else{if(Character.isLowSurrogate((char)c))throw error("Unpaired JSON surrogate");s.append((char)c);}if(s.length()>limit)throw error("JSON string limit exceeded");}
  return s.toString();
 }
 private int unicode()throws IOException{int result=0;for(int i=0;i<4;i++){int c=take(),v=Character.digit(c,16);if(v<0||c>127)throw error("Invalid JSON Unicode escape");result=result*16+v;}return result;}
 private void literal(String text)throws IOException{for(int i=0;i<text.length();i++)if(take()!=text.charAt(i))throw error("Invalid JSON literal");}
 private void expect(int c)throws IOException{if(take()!=c)throw error("Unexpected JSON token");}
 private void space()throws IOException{while(peek()==' '||peek()=='\n'||peek()=='\r'||peek()=='\t')take();}
 private int peek()throws IOException{if(look==-2)look=reader.read();return look;}
 private int take()throws IOException{int c=peek();look=-2;if(c!=-1){position++;if((position&8191)==0)ReportWriter.checkInterrupt();if(capture!=null)capture.write(c);}return c;}
 private IOException error(String text){return new IOException(text+" at character "+position);}
 public void close()throws IOException{reader.close();}
}
