package revisitledger.report;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import revisitledger.model.RecordKey;
import revisitledger.resolve.ChoiceSet;

/** Strict bounded version-1 choices. The resolver separately validates corpus and candidate membership. */
public final class ChoiceReader {
 public static final long MAX_BYTES=16L<<20;public static final int MAX_BINDINGS=100000,MAX_STRING_CHARS=65536;
 private ChoiceReader(){}
 public static ChoiceSet read(Path path)throws IOException{
  var bindings=new HashMap<RecordKey,RecordKey>();
  try(var input=new StrictJson(path,MAX_STRING_CHARS,MAX_BYTES)){
   Object result=input.read(ReportSchema.CHOICES,new StrictJson.Observer(){public void after(ReportSchema.Node n,Object value,StrictJson source)throws IOException{
    if(n==ReportSchema.BINDING){var pair=map(value);RecordKey consumer=key(pair.get("consumer"));if(bindings.putIfAbsent(consumer,key(pair.get("provider")))!=null)throw new IOException("Duplicate choice consumer");}
   }});
   return new ChoiceSet((String)map(result).get("corpusFingerprint"),bindings);
  }
 }
 @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
 static RecordKey key(Object value){var m=map(value);return new RecordKey((String)m.get("path"),(Long)m.get("ordinal"));}
}
