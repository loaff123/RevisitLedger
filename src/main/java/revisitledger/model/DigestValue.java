package revisitledger.model;
import java.util.*;
/** Canonical digest bytes. Only the explicit SHA-1/SHA-256 allowlist is supported. */
public record DigestValue(String algorithm,String lowercaseHex) {
 public DigestValue {
  algorithm=canonicalAlgorithm(algorithm);int n=algorithm.equals("sha1")?40:64;
  if(lowercaseHex==null||lowercaseHex.length()!=n||!lowercaseHex.matches("[0-9a-f]+"))throw new IllegalArgumentException("Invalid canonical digest");
 }
 private static String canonicalAlgorithm(String a){return switch(a.toLowerCase(Locale.ROOT)){case "sha1","sha-1"->"sha1";case "sha256","sha-256"->"sha256";default->throw new IllegalArgumentException("Unsupported digest algorithm");};}
 public static DigestValue parse(String raw) {
  int colon=raw.indexOf(':');if(colon<1)throw new IllegalArgumentException("Invalid digest syntax");
  String alg=canonicalAlgorithm(raw.substring(0,colon)),v=raw.substring(colon+1);int n=alg.equals("sha1")?20:32;byte[] bytes;
  if(v.length()==n*2&&v.matches("[0-9a-fA-F]+"))bytes=HexFormat.of().parseHex(v);
  else if((v.length()==(n*8+4)/5||v.length()==((n+4)/5)*8)&&v.matches("[A-Za-z2-7]+={0,6}"))bytes=base32(v,n);
  else {
   if(!v.matches("[A-Za-z0-9+/]+={0,2}")||v.length()!=((n+2)/3)*4)throw new IllegalArgumentException("Invalid digest encoding");
   bytes=Base64.getDecoder().decode(v);if(!Base64.getEncoder().encodeToString(bytes).equals(v))throw new IllegalArgumentException("Noncanonical base64");
  }
  if(bytes.length!=n)throw new IllegalArgumentException("Invalid digest length");return new DigestValue(alg,HexFormat.of().formatHex(bytes));
 }
 private static byte[] base32(String v,int n) {
  String s=v.toUpperCase(Locale.ROOT);int eq=s.indexOf('=');int chars=eq<0?s.length():eq;
  if(chars!=(n*8+4)/5||(eq>=0&&s.length()!=((n+4)/5)*8))throw new IllegalArgumentException("Invalid base32 padding");
  byte[] out=new byte[n];int value=0,bits=0,pos=0;
  for(int i=0;i<chars;i++){char c=s.charAt(i);int x=c>='A'&&c<='Z'?c-'A':c-'2'+26;value=(value<<5)|x;bits+=5;if(bits>=8){bits-=8;out[pos++]=(byte)(value>>>bits);}}
  if((value&((1<<bits)-1))!=0)throw new IllegalArgumentException("Nonzero base32 pad bits");return out;
 }
}
