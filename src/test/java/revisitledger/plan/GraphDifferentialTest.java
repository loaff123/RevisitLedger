package revisitledger.plan;
import java.nio.file.*;import java.util.*;import java.io.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
import revisitledger.index.*;import revisitledger.model.*;import revisitledger.resolve.*;
import static revisitledger.resolve.ResolutionFixtures.collect;
/** Consumes independently generated expectations; does not compute expected closure in Java. */
class GraphDifferentialTest {
 @TempDir Path temp;
 record Node(String file,long ordinal,String id,String kind,String declared,String concrete,List<String> candidates){}
 record Case(List<String> seeds,List<String> files,boolean complete,Map<String,String> blockers){}
 static final class Graph {String name;boolean scanComplete;List<String> files=new ArrayList<>();List<Node> nodes=new ArrayList<>();Map<String,String> choices=new LinkedHashMap<>(),outcomes=new LinkedHashMap<>();List<Case> cases=new ArrayList<>();}
 static List<String> list(String text){return text.equals("-")?List.of():List.of(text.split(","));}
 static Map<String,String> pairs(String text){var map=new TreeMap<String,String>();for(String token:list(text)){var p=token.split("=",2);map.put(p[0],p[1]);}return map;}
 static String nullable(String s){return s.equals("-")?null:s;}
 static List<Graph> graphs()throws IOException{
  var out=new ArrayList<Graph>();Graph g=null;
  for(String line:Files.readAllLines(Path.of("verification/graph_cases/cases.tsv"))){if(line.isBlank()||line.startsWith("#"))continue;String[] x=line.split("\t",-1);switch(x[0]){
   case "GRAPH"->{g=new Graph();g.name=x[1];g.scanComplete=Boolean.parseBoolean(x[2]);out.add(g);}
   case "FILE"->g.files.add(x[1]);
   case "RECORD"->g.nodes.add(new Node(x[1],Long.parseLong(x[2]),x[3],x[4],nullable(x[5]),nullable(x[6]),list(x[7])));
   case "CHOICE"->g.choices.put(x[1],x[2]);case "OUTCOME"->g.outcomes.put(x[1],x[2]);
   case "CASE"->g.cases.add(new Case(list(x[1]),list(x[2]),Boolean.parseBoolean(x[3]),pairs(x[4])));
   case "END"->g=null;default->throw new IOException("Unknown independent graph row");
  }}return out;
 }
 static RecordKey key(Node n){return new RecordKey(n.file,n.ordinal);}
 static String id(String id){return "<urn:graph:"+id+">";}
 static RecordFacts fact(Node n){
  var h=new LinkedHashMap<String,List<String>>();h.put("WARC-Record-ID",List.of(id(n.id)));h.put("WARC-Date",List.of("2020-01-01T00:00:00Z"));h.put("WARC-Target-URI",List.of("https://graph/"+n.id));
  if(n.declared!=null)h.put("WARC-Payload-Digest",List.of("sha256:"+n.declared));
  String type=n.kind.equals("provider")?"resource":"revisit";
  if(type.equals("revisit")){
   h.put("WARC-Profile",List.of(n.kind.equals("unsupported")?"https://unsupported/":ResolutionFixtures.PROFILE));
   if(n.candidates.size()<2)h.put("WARC-Refers-To",List.of(n.candidates.isEmpty()?"<urn:missing:"+n.id+">":id(n.candidates.get(0))));
  }
  return new RecordFacts(key(n),type,"WARC/1.1",h,n.concrete==null?Optional.empty():Optional.of(new PayloadEvidence(9,Map.of("sha256",n.concrete),true)),List.of());
 }
 @Test void allIndependentSeedSubsetsAndOutcomesAgree()throws Exception{
  int graphCount=0,caseCount=0,outcomeCount=0;
  for(Graph g:graphs()){
   var ids=new HashMap<String,RecordKey>();var byKey=new HashMap<RecordKey,String>();for(Node n:g.nodes){ids.put(n.id,key(n));byKey.put(key(n),n.id);}
   try(var index=RecordIndex.create(temp,64L<<20)){
    for(Node n:g.nodes)index.record(fact(n));for(String f:g.files)index.file(new FileFacts(f,"f".repeat(64),g.nodes.stream().filter(n->n.file.equals(f)).count(),true));index.seal(new ScanSummary(g.files.size(),g.nodes.size(),g.scanComplete));
    var bindings=new LinkedHashMap<RecordKey,RecordKey>();g.choices.forEach((a,b)->bindings.put(ids.get(a),ids.get(b)));var choices=new ChoiceSet(index.corpusFingerprint(),bindings);
    new ChainResolver().resolveAll(index,choices);
    for(var e:g.outcomes.entrySet()){assertEquals(e.getValue(),ChainResolver.outcome(index,ids.get(e.getKey())).orElseThrow().status(),g.name+" outcome "+e.getKey());outcomeCount++;}
    for(Case c:g.cases){
     try(var plan=new ClosurePlanner().plan(index,c.seeds,choices)){
      assertEquals(c.files,collect(plan.includedFiles()).stream().map(FileFacts::relativePath).toList(),g.name+" seeds "+c.seeds);
      assertEquals(c.complete,plan.closureComplete(),g.name+" completeness seeds "+c.seeds);
      var blockers=new TreeMap<String,String>();for(var f:collect(plan.blockers()))blockers.put(f.record().map(byKey::get).orElse("$input"),f.code());
      assertEquals(c.blockers,blockers,g.name+" blockers seeds "+c.seeds);
      for(var edge:collect(plan.witnessEdges())){assertTrue(c.files.contains(edge.consumer().relativePath()));assertTrue(c.files.contains(edge.provider().relativePath()));assertTrue(c.files.contains(edge.terminal().relativePath()));assertTrue(edge.payloadEvidence().complete());}
      assertFalse(plan.inputsVerified());assertFalse(plan.complete());
     }caseCount++;
    }
   }graphCount++;
  }
  assertEquals(43,graphCount);assertEquals(524,caseCount);System.out.println("Independent graph comparison: "+graphCount+" graphs / "+caseCount+" seed subsets / "+outcomeCount+" record outcomes; zero disagreements");
 }
}
