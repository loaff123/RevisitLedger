package revisitledger.resolve;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import revisitledger.model.*;

/** Versioned bounded state encoding, with no object deserialization or executable content. */
public final class ChainStateCodec {
 private static final int MAX_BYTES=65536, OUTCOME_VERSION=1, EDGE_VERSION=2;
 private ChainStateCodec(){}
 @FunctionalInterface private interface Writer {void write(DataOutputStream out)throws IOException;}
 /** Fixed-sized key. Readers verify the embedded locator to fail closed on any key collision. */
 public static String key(RecordKey key)throws IOException{
  try{
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){writeKey(out,key);}
   return HexFormat.of().formatHex(digest.digest());
  }catch(NoSuchAlgorithmException e){throw new IOException("Required digest unavailable",e);}
 }
 public static byte[] encodeOutcome(ChainOutcome r)throws IOException{
  return encode(out->{out.writeInt(OUTCOME_VERSION);writeString(out,r.status());writeKey(out,r.consumer());writeOptionalKey(out,r.immediateProvider());writeString(out,r.selectionBasis());writeOptionalKey(out,r.terminal());out.writeBoolean(r.payloadEvidence().isPresent());if(r.payloadEvidence().isPresent())writePayload(out,r.payloadEvidence().get());});
 }
 public static ChainOutcome decodeOutcome(byte[] bytes)throws IOException{
  try(var in=input(bytes)){
   if(in.readInt()!=OUTCOME_VERSION)throw new IOException("Unsupported chain state version");
   var result=new ChainOutcome(readString(in),readKey(in),readOptionalKey(in),readString(in),readOptionalKey(in),in.readBoolean()?Optional.of(readPayload(in)):Optional.empty());end(in);return result;
  }catch(IllegalArgumentException e){throw new IOException("Invalid chain state value",e);}
 }
 public static byte[] encodeEdge(WitnessEdge e)throws IOException{
  return encode(out->{out.writeInt(EDGE_VERSION);writeKey(out,e.consumer());writeKey(out,e.provider());writeString(out,e.selectionBasis());writeKey(out,e.terminal());writePayload(out,e.payloadEvidence());});
 }
 public static WitnessEdge decodeEdge(byte[] bytes)throws IOException{
  try(var in=input(bytes)){
   if(in.readInt()!=EDGE_VERSION)throw new IOException("Unsupported witness state version");
   var result=new WitnessEdge(readKey(in),readKey(in),readString(in),readKey(in),readPayload(in));end(in);return result;
  }catch(IllegalArgumentException e){throw new IOException("Invalid witness state value",e);}
 }
 private static byte[] encode(Writer writer)throws IOException{
  var bytes=new ByteArrayOutputStream();
  var bounded=new FilterOutputStream(bytes){int count;private void check(int n)throws IOException{if(n>MAX_BYTES-count)throw new IOException("Chain state exceeds metadata limit");count+=n;}@Override public void write(int n)throws IOException{check(1);out.write(n);}@Override public void write(byte[] b,int offset,int n)throws IOException{check(n);out.write(b,offset,n);}};
  try(var out=new DataOutputStream(bounded)){writer.write(out);}return bytes.toByteArray();
 }
 private static DataInputStream input(byte[] bytes)throws IOException{if(bytes.length>MAX_BYTES)throw new IOException("Chain state exceeds metadata limit");return new DataInputStream(new ByteArrayInputStream(bytes));}
 private static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Trailing chain state bytes");}
 private static void writeString(DataOutputStream out,String value)throws IOException{if(value.length()>MAX_BYTES)throw new IOException("Chain text exceeds metadata limit");byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("Chain text exceeds metadata limit");out.writeInt(bytes.length);out.write(bytes);}
 private static String readString(DataInputStream in)throws IOException{int length=in.readInt();if(length<0||length>in.available())throw new IOException("Invalid chain text length");try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString();}catch(CharacterCodingException e){throw new IOException("Invalid chain text",e);}}
 private static void writeKey(DataOutputStream out,RecordKey key)throws IOException{writeString(out,key.relativePath());out.writeLong(key.ordinal());}
 private static RecordKey readKey(DataInputStream in)throws IOException{return new RecordKey(readString(in),in.readLong());}
 private static void writeOptionalKey(DataOutputStream out,Optional<RecordKey> key)throws IOException{out.writeBoolean(key.isPresent());if(key.isPresent())writeKey(out,key.get());}
 private static Optional<RecordKey> readOptionalKey(DataInputStream in)throws IOException{return in.readBoolean()?Optional.of(readKey(in)):Optional.empty();}
 private static void writePayload(DataOutputStream out,PayloadEvidence payload)throws IOException{out.writeLong(payload.length());out.writeBoolean(payload.complete());out.writeInt(payload.hexDigests().size());for(var e:new TreeMap<>(payload.hexDigests()).entrySet()){writeString(out,e.getKey());writeString(out,e.getValue());}}
 private static PayloadEvidence readPayload(DataInputStream in)throws IOException{long length=in.readLong();boolean complete=in.readBoolean();int count=in.readInt();if(count<0||count>in.available()/8)throw new IOException("Invalid chain digest count");var digests=new LinkedHashMap<String,String>();for(int n=0;n<count;n++)if(digests.put(readString(in),readString(in))!=null)throw new IOException("Duplicate chain digest key");return new PayloadEvidence(length,digests,complete);}
}
