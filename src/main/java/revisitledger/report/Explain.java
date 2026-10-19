package revisitledger.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import revisitledger.model.RecordKey;

/** Validates the entire saved report before emitting a staged, bounded explanation. No corpus paths are opened. */
public final class Explain {
 /** A single selected record or explanation cannot exceed four million UTF-16 units (at most 16 MiB UTF-8). */
 public static final int MAX_EXPLANATION_CHARS=4<<20;
 private Explain(){}
 public static void render(Path report,RecordKey requested,PrintStream out)throws IOException{
  Objects.requireNonNull(report);Objects.requireNonNull(requested);Objects.requireNonNull(out);
  if(!ReportSchema.safePath(requested.relativePath()))throw new IOException("Invalid requested record path");
  Path row=Files.createTempFile("revisitledger-explain-row-",".tmp"),staged=null;
  try{
   staged=Files.createTempFile("revisitledger-explain-",".tmp");
   try(var text=new CappedWriter(Files.newBufferedWriter(staged,StandardCharsets.UTF_8),MAX_EXPLANATION_CHARS);var input=new StrictJson(report,1<<20,Long.MAX_VALUE)){
    class Reader implements StrictJson.Observer {
     CappedWriter capture;RecordKey priorRecord;String priorFile;boolean found;
     public void before(ReportSchema.Node n,StrictJson source)throws IOException{
      if(n==ReportSchema.RECORD||n==ReportSchema.EDGE){capture=new CappedWriter(Files.newBufferedWriter(row,StandardCharsets.UTF_8),MAX_EXPLANATION_CHARS);source.capture=new TerminalWriter(capture);}
     }
     public void after(ReportSchema.Node n,Object value,StrictJson source)throws IOException{
      if(value==null)return;
      if(n==ReportSchema.DIGEST){var m=ChoiceReader.map(value);if(((String)m.get("hex")).length()!=(m.get("algorithm").equals("sha1")?40:64))throw new IOException("Digest algorithm and length disagree");}
      if(n==ReportSchema.PAYLOAD){/* The schema validates the fixed supported digest alphabet and names. */}
      if(n==ReportSchema.FILE){var m=ChoiceReader.map(value);String path=(String)m.get("path");if(priorFile!=null&&comparePaths(priorFile,path)>=0)throw new IOException("Report files are not strictly sorted");priorFile=path;if(Boolean.TRUE.equals(m.get("complete"))&&((String)m.get("sha256")).isEmpty())throw new IOException("Complete file omitted fingerprint");}
      if(n==ReportSchema.RECORD||n==ReportSchema.EDGE){
       source.capture=null;capture.close();var m=ChoiceReader.map(value);boolean keep;
       if(n==ReportSchema.RECORD){
        RecordKey key=ChoiceReader.key(m.get("key"));if(priorRecord!=null&&compare(priorRecord,key)>=0)throw new IOException("Report records are not strictly sorted");priorRecord=key;
        keep=key.equals(requested);if(keep){found=true;var outcome=ChoiceReader.map(m.get("outcome"));text.write("Record "+locator(key)+"\nStatus: "+outcome.get("status")+"\n");
         if(outcome.get("immediateProvider")!=null)text.write("Immediate provider: "+locator(ChoiceReader.key(outcome.get("immediateProvider")))+"\n");
         if(outcome.get("terminal")!=null)text.write("Terminal provider: "+locator(ChoiceReader.key(outcome.get("terminal")))+"\n");
         text.write("Selection: "+safeText((String)outcome.get("selectionBasis"))+"\nRecorded selectors, candidates and byte evidence:\n");
        }
       }else{
        var consumer=ChoiceReader.key(m.get("consumer"));var provider=ChoiceReader.key(m.get("provider"));keep=consumer.equals(requested)||provider.equals(requested)||!consumer.relativePath().equals(provider.relativePath())&&(consumer.relativePath().equals(requested.relativePath())||provider.relativePath().equals(requested.relativePath()));if(keep)text.write("Direct whole-file dependency witness involving this file: ");
       }
       if(keep){if(capture.overflow)throw new IOException("Explanation size limit exceeded");try(var r=Files.newBufferedReader(row,StandardCharsets.UTF_8)){r.transferTo(text);}text.write('\n');}capture=null;
      }
      if(n==ReportSchema.REPORT){
       var m=ChoiceReader.map(value);boolean scan=(Boolean)m.get("scanComplete"),resolution=(Boolean)m.get("resolutionComplete"),closure=(Boolean)m.get("closureComplete"),verified=(Boolean)m.get("inputsVerified"),complete=(Boolean)m.get("complete");
       if(complete!=(closure&&verified)||!scan&&(resolution||closure||verified)||m.get("mode").equals("audit")&&closure!=resolution)throw new IOException("Inconsistent report completeness flags");
       text.write("Scope: "+m.get("mode")+"; scanComplete="+scan+", resolutionComplete="+resolution+", closureComplete="+closure+", inputsVerified="+verified+", complete="+complete+"\n");
       text.write("This displays edges incident to the requested record and cross-file edges involving its file. Unrelated same-file edges are omitted. The full report retains the complete witness graph.\nThis describes saved evidence only. No archive was reopened or reverified. Completion is limited to the stated revisit-payload support profile.\n");
      }
     }
     void closeCapture()throws IOException{input.capture=null;if(capture!=null){capture.close();capture=null;}}
    }
    var observer=new Reader();try{input.read(ReportSchema.REPORT,observer);}finally{observer.closeCapture();}
    if(!observer.found)throw new IOException("Record was not found in the report");if(text.overflow)throw new IOException("Explanation size limit exceeded");
   }
   ReportWriter.checkInterrupt();try(var bytes=Files.newInputStream(staged)){bytes.transferTo(out);}if(out.checkError())throw new IOException("Explanation output failed");
  }finally{Files.deleteIfExists(row);if(staged!=null)Files.deleteIfExists(staged);}
 }
 private static int compare(RecordKey a,RecordKey b){int c=comparePaths(a.relativePath(),b.relativePath());return c!=0?c:Long.compare(a.ordinal(),b.ordinal());}
 static int comparePaths(String a,String b){
  int i=0,j=0;while(i<a.length()&&j<b.length()){int x=a.codePointAt(i),y=b.codePointAt(j);if(x!=y)return Integer.compare(x,y);i+=Character.charCount(x);j+=Character.charCount(y);}return Integer.compare(a.length()-i,b.length()-j);
 }
 private static String locator(RecordKey key){return safeText(key.relativePath())+"#"+key.ordinal();}
 private static String safeText(String text){var result=new StringBuilder();for(int i=0;i<text.length();){int c=text.codePointAt(i);i+=Character.charCount(c);if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)result.append(JsonOutput.escaped(c));else result.appendCodePoint(c);}return result.toString();}
 private static final class TerminalWriter extends Writer {
  final Writer out;char high;TerminalWriter(Writer out){this.out=out;}
  public void write(char[] chars,int offset,int length)throws IOException{for(int i=offset;i<offset+length;i++){
   char c=chars[i];if(high!=0){if(Character.isLowSurrogate(c)){int point=Character.toCodePoint(high,c);if(Character.getType(point)==Character.FORMAT)out.write(JsonOutput.escaped(point));else{out.write(high);out.write(c);}high=0;continue;}out.write(JsonOutput.escaped(high));high=0;}
   if(Character.isHighSurrogate(c)){high=c;continue;}if((Character.isISOControl(c)&&c!='\n'&&c!='\t')||Character.getType(c)==Character.FORMAT)out.write(JsonOutput.escaped(c));else out.write(c);
  }}
  public void flush()throws IOException{out.flush();}public void close()throws IOException{if(high!=0){out.write(JsonOutput.escaped(high));high=0;}out.close();}
 }
 private static final class CappedWriter extends Writer {
  final Writer delegate;final long limit;long size;boolean overflow,closed;
  CappedWriter(Writer delegate,long limit){this.delegate=delegate;this.limit=limit;}
  public void write(char[] chars,int offset,int length)throws IOException{long remaining=limit-size;int n=(int)Math.min(remaining,length);if(n>0){delegate.write(chars,offset,n);size+=n;}if(n<length)overflow=true;}
  public void flush()throws IOException{delegate.flush();}
  public void close()throws IOException{if(!closed){closed=true;delegate.close();}}
 }
}
