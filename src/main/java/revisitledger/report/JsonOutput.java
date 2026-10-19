package revisitledger.report;

import java.io.*;
import java.util.*;

/** Small canonical JSON encoder. Callers stream arrays rather than building a document tree. */
final class JsonOutput {
 private final Writer out;
 JsonOutput(Writer out){this.out=out;}
 void raw(String value)throws IOException{out.write(value);}
 void string(String value)throws IOException{
  out.write('"');for(int i=0;i<value.length();){
   int c=value.codePointAt(i);int width=Character.charCount(c);
   if(width==1&&Character.isSurrogate(value.charAt(i)))throw new IOException("Unpaired surrogate in report text");i+=width;
   switch(c){case '"'->out.write("\\\"");case '\\'->out.write("\\\\");case '\b'->out.write("\\b");case '\f'->out.write("\\f");case '\n'->out.write("\\n");case '\r'->out.write("\\r");case '\t'->out.write("\\t");default->{
    if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)out.write(escaped(c));else out.write(Character.toChars(c));
   }}
  }out.write('"');
 }
 static String escaped(int codePoint){var text=new StringBuilder();for(char c:Character.toChars(codePoint))text.append(String.format(Locale.ROOT,"\\u%04x",(int)c));return text.toString();}
 void name(String name)throws IOException{string(name);raw(":");}
 void value(Object value)throws IOException{
  if(value==null){raw("null");return;}if(value instanceof String s){string(s);return;}if(value instanceof Number||value instanceof Boolean){raw(value.toString());return;}
  if(value instanceof Map<?,?> map){raw("{");boolean first=true;for(var e:map.entrySet()){if(!first)raw(",");first=false;name((String)e.getKey());value(e.getValue());}raw("}");return;}
  if(value instanceof Iterable<?> list){raw("[");boolean first=true;for(Object v:list){if(!first)raw(",");first=false;value(v);}raw("]");return;}throw new IOException("Unsupported JSON value");
 }
}
