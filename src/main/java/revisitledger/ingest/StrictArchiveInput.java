package revisitledger.ingest;

import java.io.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import revisitledger.model.ScanLimits;

/** Strict concatenated gzip, magic detection, expansion and no-progress input budgets. */
public final class StrictArchiveInput {
 private StrictArchiveInput() {}
 static final class Budget {
  long remaining;
  Budget(long remaining){this.remaining=remaining;}
 }
 public static InputStream open(InputStream raw,ScanLimits limits)throws IOException {
  return open(raw,limits,new Budget(limits.maxExpandedBytes()));
 }
 static InputStream open(InputStream raw,ScanLimits limits,Budget budget)throws IOException {
  ProgressInput guarded=new ProgressInput(raw,(long)limits.maxHeaderBytes()+16384L);
  PushbackInputStream peek=new PushbackInputStream(guarded,2);
  try {
   int a=peek.read(),b=peek.read();if(b>=0)peek.unread(b);if(a>=0)peek.unread(a);
   InputStream decoded=a==31&&b==139?GzipCompressorInputStream.builder().setInputStream(peek).setDecompressConcatenated(true).setOnMemberStart(member->{
    if(member.getMetaData().getHeaderCRC())throw new ScanException("unsupported_compression","Gzip header-checksum members are unsupported because this decoder does not verify FHCRC");
   }).get():peek;
   return new InputStream(){
    private final byte[] one=new byte[1];
    public int read()throws IOException {return read(one,0,1)<0?-1:one[0]&255;}
    public int read(byte[] b,int off,int len)throws IOException {
     java.util.Objects.checkFromIndexSize(off,len,b.length);if(len==0)return 0;checkInterrupted();guarded.resetProgress();
     int n=decoded.read(b,off,(int)Math.min(Math.min(len,8192),Math.max(1,budget.remaining)));
     if(n>budget.remaining)throw new ScanException("resource_limit","Expanded-byte limit reached");
     if(n>0)budget.remaining-=n;return n;
    }
    public void close()throws IOException {decoded.close();}
   };
  }catch(IOException|RuntimeException e){peek.close();throw e;}
 }
 static void checkInterrupted()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Scan interrupted");}
 /** Bounds gzip metadata allocation and arbitrarily many empty members before output. */
 private static final class ProgressInput extends FilterInputStream {
  final long limit;long count;
  ProgressInput(InputStream in,long limit){super(in);this.limit=limit;}
  void resetProgress(){count=0;}
  public int read()throws IOException {checkInterrupted();int n=in.read();if(n>=0&&++count>limit)throw exceeded();return n;}
  public int read(byte[] b,int off,int len)throws IOException {
   checkInterrupted();int n=in.read(b,off,(int)Math.min(len,Math.max(1,limit-count)));
   if(n>0&&(count+=n)>limit)throw exceeded();return n;
  }
  private ScanException exceeded(){return new ScanException("resource_limit","Compressed stream made insufficient bounded progress");}
 }
}
