package revisitledger.report;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/** Executable closed schema. Its published JSON Schema is generated from these same nodes. */
final class ReportSchema {
 enum Kind {OBJECT,ARRAY,STRING,INTEGER,BOOLEAN}
 static final class Node {
  final Kind kind; Map<String,Node> fields;Node item;boolean nullable;long min=Long.MIN_VALUE,max=Long.MAX_VALUE,maxItems=Long.MAX_VALUE;int maxLength=1<<20,maxProperties=128;String pattern;List<Object> enumeration;Predicate<String> extra;String tag;
  Node(Kind kind){this.kind=kind;}
  Node nullable(){var n=new Node(kind);n.fields=fields;n.item=item;n.nullable=true;n.min=min;n.max=max;n.maxItems=maxItems;n.maxLength=maxLength;n.maxProperties=maxProperties;n.pattern=pattern;n.enumeration=enumeration;n.extra=extra;n.tag=tag;return n;}
 }
 static Node text(int max){var n=new Node(Kind.STRING);n.maxLength=max;return n;}
 static Node pattern(String pattern,int max){var n=text(max);n.pattern=pattern;return n;}
 static Node integer(long min,long max){var n=new Node(Kind.INTEGER);n.min=min;n.max=max;return n;}
 static Node choices(Object...values){var n=values[0] instanceof Number?integer(0,Long.MAX_VALUE):text(256);n.enumeration=List.of(values);return n;}
 static Node bool(){return new Node(Kind.BOOLEAN);}
 static Node array(Node item){var n=new Node(Kind.ARRAY);n.item=item;return n;}
 static Node object(Object...pairs){var n=new Node(Kind.OBJECT);n.fields=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)n.fields.put((String)pairs[i],(Node)pairs[i+1]);return n;}
 static Node map(Node value,int max){var n=new Node(Kind.OBJECT);n.item=value;n.maxProperties=max;return n;}
 static final Node PATH=path();
 static Node path(){var n=text(65536);n.pattern="^(?!\\.{1,2}(?:/|$))(?!/)(?!.*?/\\.{1,2}(?:/|$))(?!.*//)(?!.*?/$)[^\\\\:\\x00-\\x1f\\x7f]+$";n.extra=ReportSchema::safePath;return n;}
 static boolean safePath(String path){if(path.isEmpty()||path.startsWith("/")||path.endsWith("/")||path.contains("\\")||path.contains(":")||path.chars().anyMatch(c->c<32||c==127))return false;for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;return true;}
 static final Node KEY=object("path",PATH,"ordinal",integer(1,Long.MAX_VALUE));
 static final Node SHA256=pattern("^[0-9a-f]{64}$",64);
 static final Node HEX=pattern("^(?:[0-9a-f]{40}|[0-9a-f]{64})$",64);
 static final Node DIGEST=tag(object("algorithm",choices("sha1","sha256"),"hex",HEX),"digest");
 static final Node PAYLOAD=object("length",integer(0,Long.MAX_VALUE),"hexDigests",digestMap(),"complete",bool());
 static Node digestMap(){var n=tag(map(HEX,2),"digests");n.extra=s->s.equals("sha1")||s.equals("sha256");return n;}
 static final Node FINDING=object("code",text(128),"record",KEY.nullable(),"message",text(4096),"evidence",map(text(1<<20),64));
 static final Node STATUS=choices("resolved","missing_in_corpus","ambiguous","selector_conflict","digest_mismatch","unsupported","cycle","input_incomplete");
 static final Node SELECTOR=object("status",STATUS,"selectionBasis",text(128),"chosen",KEY.nullable(),"evidence",map(text(1<<20),64),"candidates",array(KEY),"findings",array(FINDING));
 static final Node OUTCOME=object("status",STATUS,"immediateProvider",KEY.nullable(),"selectionBasis",text(128),"terminal",KEY.nullable(),"payloadEvidence",PAYLOAD.nullable());
 static final Node RECORD=tag(object("key",KEY,"type",text(1<<20),"version",choices("WARC/1.0","WARC/1.1"),"rawHeaders",map(array(text(1<<20)),32),"normalizedDigests",object("block",DIGEST.nullable(),"payload",DIGEST.nullable()),"payloadEvidence",PAYLOAD.nullable(),"selector",SELECTOR.nullable(),"outcome",OUTCOME,"findings",array(FINDING)),"record");
 static final Node FILE=tag(object("path",PATH,"sha256",pattern("^(?:[0-9a-f]{64})?$",64),"recordCount",integer(0,Long.MAX_VALUE),"complete",bool()),"file");
 static final Node BINDING=tag(object("consumer",KEY,"provider",KEY),"binding");
 static final Node EDGE=tag(object("consumer",KEY,"provider",KEY,"selectionBasis",text(128),"terminal",KEY,"payloadEvidence",PAYLOAD),"edge");
 static Node tag(Node n,String tag){n.tag=tag;return n;}
 static final Node LIMITS=object("maxExpandedBytes",integer(1,Long.MAX_VALUE),"maxRecords",integer(1,Long.MAX_VALUE),"maxBlockBytes",integer(1,Long.MAX_VALUE),"maxHeaderBytes",integer(1,Integer.MAX_VALUE),"maxScratchBytes",integer(1,Long.MAX_VALUE));
 static final Node REPORT=tag(object("schemaVersion",choices(1L),"toolVersion",choices(ReportWriter.TOOL_VERSION),"supportProfile",choices(ReportWriter.SUPPORT_PROFILE),"mode",choices("audit","plan"),"limits",LIMITS,"corpusFingerprint",SHA256,"scanComplete",bool(),"resolutionComplete",bool(),"closureComplete",bool(),"inputsVerified",bool(),"complete",bool(),"files",array(FILE),"records",array(RECORD),"choices",array(BINDING),"includedFiles",array(PATH),"addedFiles",array(PATH),"witnessEdges",array(EDGE),"blockers",array(FINDING),"findings",array(FINDING),"warnings",array(text(4096))),"report");
 static final Node CHOICES=choiceSchema();
 static Node choiceSchema(){var bindings=array(BINDING);bindings.maxItems=100000;return object("version",choices(1L),"corpusFingerprint",SHA256,"bindings",bindings);}
 static Object json(Node n){
  var m=new LinkedHashMap<String,Object>();m.put("type",n.nullable?List.of(n.kind.name().toLowerCase(Locale.ROOT),"null"):n.kind.name().toLowerCase(Locale.ROOT));
  if(n.enumeration!=null)m.put("enum",n.enumeration);
  switch(n.kind){
   case STRING->{m.put("maxLength",n.maxLength);if(n.pattern!=null)m.put("pattern",n.pattern);}
   case INTEGER->{m.put("minimum",n.min);m.put("maximum",n.max);}
   case BOOLEAN->{}
   case ARRAY->{m.put("items",json(n.item));if(n.maxItems!=Long.MAX_VALUE)m.put("maxItems",n.maxItems);}
   case OBJECT->{if(n.fields!=null){var fields=new LinkedHashMap<String,Object>();n.fields.forEach((k,v)->fields.put(k,json(v)));m.put("properties",fields);m.put("required",new ArrayList<>(fields.keySet()));m.put("additionalProperties",false);}else{m.put("additionalProperties",json(n.item));m.put("maxProperties",n.maxProperties);if("digests".equals(n.tag)){m.put("propertyNames",Map.of("enum",List.of("sha1","sha256")));m.put("properties",Map.of("sha1",Map.of("pattern","^[0-9a-f]{40}$"),"sha256",Map.of("pattern","^[0-9a-f]{64}$")));}}}
  }
  if("digest".equals(n.tag))m.put("allOf",List.of(Map.of("if",Map.of("properties",Map.of("algorithm",Map.of("const","sha1"))),"then",Map.of("properties",Map.of("hex",Map.of("pattern","^[0-9a-f]{40}$")))),Map.of("if",Map.of("properties",Map.of("algorithm",Map.of("const","sha256"))),"then",Map.of("properties",Map.of("hex",Map.of("pattern","^[0-9a-f]{64}$"))))));
  if("report".equals(n.tag))m.put("allOf",List.of(
   implication(Map.of("complete",true),Map.of("closureComplete",true,"inputsVerified",true)),
   implication(Map.of("closureComplete",true,"inputsVerified",true),Map.of("complete",true)),
   implication(Map.of("scanComplete",false),Map.of("resolutionComplete",false,"closureComplete",false,"inputsVerified",false)),
   implication(Map.of("mode","audit","resolutionComplete",true),Map.of("closureComplete",true)),
   implication(Map.of("mode","audit","resolutionComplete",false),Map.of("closureComplete",false))));
  return m;
 }
 static Object implication(Map<String,Object> when,Map<String,Object> then){return Map.of("if",constantProperties(when),"then",constantProperties(then));}
 static Object constantProperties(Map<String,Object> values){var properties=new TreeMap<String,Object>();values.forEach((k,v)->properties.put(k,Map.of("const",v)));return Map.of("properties",properties);}
 static Object canonical(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();for(var e:map.entrySet())sorted.put((String)e.getKey(),canonical(e.getValue()));return sorted;}if(value instanceof List<?> list)return list.stream().map(ReportSchema::canonical).toList();return value;}
 public static void main(String[] args)throws IOException{
  @SuppressWarnings("unchecked") var schema=(Map<String,Object>)json(args.length>0&&args[0].equals("choices")?CHOICES:REPORT);
  var root=new LinkedHashMap<String,Object>();root.put("$schema","https://json-schema.org/draft/2020-12/schema");root.put("title",args.length>0?"RevisitLedger choices v1":"RevisitLedger report v1");root.putAll(schema);
  var writer=new OutputStreamWriter(System.out,java.nio.charset.StandardCharsets.UTF_8);new JsonOutput(writer).value(canonical(root));writer.write('\n');writer.flush();
 }
}
