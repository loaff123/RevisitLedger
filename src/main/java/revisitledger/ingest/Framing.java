package revisitledger.ingest;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.netpreserve.jwarc.*;

/** Bounded framing adapter; jwarc remains the WARC and HTTP header grammar parser. */
final class Framing {
 private Framing() {}
 static byte[] header(InputStream input,int max,boolean eofAllowed)throws IOException {
  var bytes=new ByteArrayOutputStream(Math.min(max,4096));int state=0;
  while(true){
   int c=input.read();if(c<0){if(bytes.size()==0&&eofAllowed)return null;throw incomplete();}
   if(bytes.size()>=max)throw new ScanException("resource_limit","Header-byte limit reached");bytes.write(c);
   state=switch(state){case 0->c==13?1:0;case 1->c==10?2:(c==13?1:0);case 2->c==13?3:0;default->c==10?4:(c==13?1:0);};
   if(state==4)return bytes.toByteArray();
  }
 }
 static WarcParser warc(byte[] b)throws IOException {
  if(b.length<10)throw incomplete();
  String first=new String(b,0,10,java.nio.charset.StandardCharsets.US_ASCII);
  if(!first.equals("WARC/1.0\r\n")&&!first.equals("WARC/1.1\r\n"))throw new ScanException("input_incomplete","Only exact WARC 1.0 and 1.1 version lines are supported");
  try{java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));}
  catch(java.nio.charset.CharacterCodingException e){throw new ScanException("input_incomplete","Malformed UTF-8 in WARC header");}
  var parser=new WarcParser();parser.setLenient(false);ByteBuffer buffer=ByteBuffer.wrap(b);parser.parse(buffer);
  if(parser.isError()||!parser.isFinished()||buffer.hasRemaining())throw new ScanException("input_incomplete","Malformed WARC header");return parser;
 }
 static HttpParser http(byte[] b)throws IOException {
  var parser=new HttpParser();parser.strictResponse();ByteBuffer buffer=ByteBuffer.wrap(b);parser.parse(buffer);
  if(parser.isError()||!parser.isFinished()||buffer.hasRemaining())throw new ScanException("http_framing","Malformed HTTP header");return parser;
 }
 static long length(String s,String code)throws IOException {
  if(!s.matches("[0-9]+"))throw new ScanException(code,"Invalid nonnegative content length");
  try{return Long.parseLong(s);}catch(NumberFormatException e){throw new ScanException(code,"Content length exceeds supported range");}
 }
 static ScanException incomplete(){return new ScanException("input_incomplete","Unexpected end of archive framing");}
 static void trailer(InputStream in)throws IOException {
  for(int c:new int[]{13,10,13,10})if(in.read()!=c)throw new ScanException("input_incomplete","Missing or invalid WARC record terminator");
 }
 static void drain(InputStream in)throws IOException {byte[] buffer=new byte[8192];while(in.read(buffer)>=0)StrictArchiveInput.checkInterrupted();}
 static final class Block extends InputStream {
  final InputStream in;long remaining;
  Block(InputStream in,long length){this.in=in;this.remaining=length;}
  public int read()throws IOException {if(remaining==0)return -1;int c=in.read();if(c<0)throw incomplete();remaining--;return c;}
  public int read(byte[] b,int off,int len)throws IOException {Objects.checkFromIndexSize(off,len,b.length);if(len==0)return 0;if(remaining==0)return -1;int n=in.read(b,off,(int)Math.min(len,remaining));if(n<0)throw incomplete();remaining-=n;return n;}
 }
}
