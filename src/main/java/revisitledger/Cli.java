package revisitledger;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import revisitledger.index.*;
import revisitledger.ingest.*;
import revisitledger.model.*;
import revisitledger.plan.*;
import revisitledger.report.*;
import revisitledger.resolve.*;

/** Offline command boundary. Diagnostics never interpolate archive contents or machine paths. */
public final class Cli {
 public static final String VERSION="0.1.0";
 private static final long LIST_BYTES=16L<<20;
 private Cli(){}
 public static int run(String[] args,PrintStream out,PrintStream err){
  try{
   if(args.length==1&&(args[0].equals("--help")||args[0].equals("help"))){help(out);return 0;}
   if(args.length==1&&args[0].equals("--version")){out.println("RevisitLedger "+VERSION);return 0;}
   if(args.length==0)throw invalid();
   String command=args[0];Map<String,String> options=parse(args,command);
   if(command.equals("explain")){
    Explain.render(localFile(Path.of(required(options,"report"))),locator(required(options,"record")),out);
    return 0;
   }
   Path root=Path.of(required(options,"root"));
   Path files=localFile(Path.of(required(options,"files")));
   List<String> corpus=readList(files);List<Path> inputPaths=InputFiles.resolve(root,corpus);
   var protectedPaths=new ArrayList<>(inputPaths);protectedPaths.add(files);
   List<String> seeds=corpus;
   if(command.equals("plan")){Path seedFile=localFile(Path.of(required(options,"seeds")));protectedPaths.add(seedFile);seeds=readList(seedFile);if(!new HashSet<>(corpus).containsAll(seeds))throw invalid();}
   ChoiceSet supplied=null;
   if(options.containsKey("choices")){Path choiceFile=localFile(Path.of(options.get("choices")));protectedPaths.add(choiceFile);supplied=ChoiceReader.read(choiceFile);}
   ScanLimits defaults=ScanLimits.defaults();
   ScanLimits limits=new ScanLimits(number(options,"max-expanded-bytes",defaults.maxExpandedBytes()),number(options,"max-records",defaults.maxRecords()),number(options,"max-block-bytes",defaults.maxBlockBytes()),Math.toIntExact(number(options,"max-header-bytes",defaults.maxHeaderBytes())),number(options,"max-scratch-bytes",defaults.maxScratchBytes()));
   Path scratch=options.containsKey("scratch")?directory(Path.of(options.get("scratch"))):directory(Path.of(System.getProperty("java.io.tmpdir")));
   try(OutputTarget target=new OutputTarget(Path.of(required(options,"json")),protectedPaths,options.containsKey("overwrite"))){
    int code;
    try(RecordIndex index=RecordIndex.create(scratch,limits.maxScratchBytes())){
     ScanSummary scan=new ArchiveScanner().scan(root,corpus,limits,index);index.seal(scan);
     ChoiceSet choices=supplied==null?ChoiceSet.empty(index.corpusFingerprint()):supplied;
     if(supplied!=null)validateChoices(index,choices);
     try(PlanHandle plan=new ClosurePlanner().plan(index,seeds,choices,root)){
      try(OutputStream report=target.open()){
       if(command.equals("audit"))ReportWriter.writeAudit(index,plan,choices,limits,report);
       else ReportWriter.write(index,plan,choices,limits,report);
      }
      code=!scan.complete()||!index.complete()||!plan.inputsVerified()?2:plan.complete()?0:1;
     }
    }
    target.commit();
    out.println(code==0?"Complete for the supported revisit-payload scope.":code==1?"Blocked by dependency, integrity, or unsupported findings; see JSON report.":"Incomplete result; see JSON report.");
    return code;
   }
  }catch(InterruptedIOException e){Thread.currentThread().interrupt();err.println("Incomplete: operation interrupted; no new report committed.");return 2;}
   catch(IOException|IllegalArgumentException|ArithmeticException|SecurityException e){err.println("Incomplete: invalid arguments, unsafe path, malformed input, resource limit, or I/O failure. Use --help.");return 2;}
 }
 private static void validateChoices(RecordIndex index,ChoiceSet choices)throws IOException{
  if(!choices.validFor(index))throw invalid();
  var selectors=new SelectorResolver();
  for(var binding:choices.bindings().entrySet()){
   boolean enumerated=false;
   try(var candidates=selectors.resolve(index,binding.getKey(),choices).sortedCandidates()){
    while(candidates.next())if(candidates.value().key().equals(binding.getValue())){enumerated=true;break;}
   }
   if(!enumerated)throw invalid();
  }
 }
 private static Map<String,String> parse(String[]args,String command)throws IOException{
  Set<String> allowed=switch(command){
   case "audit"->Set.of("root","files","json","choices","overwrite","scratch","max-expanded-bytes","max-records","max-block-bytes","max-header-bytes","max-scratch-bytes");
   case "plan"->Set.of("root","files","seeds","json","choices","overwrite","scratch","max-expanded-bytes","max-records","max-block-bytes","max-header-bytes","max-scratch-bytes");
   case "explain"->Set.of("report","record");default->throw invalid();};
  var result=new HashMap<String,String>();
  for(int i=1;i<args.length;i++){
   if(!args[i].startsWith("--"))throw invalid();String key=args[i].substring(2);
   if(!allowed.contains(key)||result.containsKey(key))throw invalid();
   if(key.equals("overwrite"))result.put(key,"true");
   else{if(++i>=args.length||args[i].isEmpty()||args[i].startsWith("--"))throw invalid();result.put(key,args[i]);}
  }
  return result;
 }
 private static String required(Map<String,String>options,String key)throws IOException{String value=options.get(key);if(value==null)throw invalid();return value;}
 private static long number(Map<String,String>options,String key,long fallback)throws IOException{String s=options.get(key);if(s==null)return fallback;if(!s.matches("[0-9]{1,19}"))throw invalid();long n=Long.parseLong(s);if(n<1)throw invalid();return n;}
 static RecordKey locator(String text)throws IOException{int hash=text.lastIndexOf('#');if(hash<1)throw invalid();String file=text.substring(0,hash),number=text.substring(hash+1);validateRelative(file);if(!number.matches("[1-9][0-9]{0,18}"))throw invalid();return new RecordKey(file,Long.parseLong(number));}
 private static void validateRelative(String name)throws IOException{
  if(name.isEmpty()||name.getBytes(StandardCharsets.UTF_8).length>4096||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.chars().anyMatch(c->c<32||c==127))throw invalid();
  if(Path.of(name).isAbsolute())throw invalid();for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw invalid();
 }
 private static List<String> readList(Path path)throws IOException{
  if(Files.size(path)>LIST_BYTES)throw invalid();var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
  var rows=new TreeSet<String>();long characters=0;
  try(Reader reader=new BufferedReader(new InputStreamReader(Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS),decoder))){
   StringBuilder row=new StringBuilder();int c;boolean cr=false;
   while((c=reader.read())!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(++characters>LIST_BYTES)throw invalid();
    if(c=='\n'){addRow(rows,row.toString());row.setLength(0);cr=false;}
    else if(c=='\r'){if(cr)throw invalid();cr=true;}
    else{if(cr)throw invalid();if(row.length()>=4096)throw invalid();row.append((char)c);}
   }
   if(cr)throw invalid();if(!row.isEmpty())addRow(rows,row.toString());
  }
  if(rows.isEmpty())throw invalid();return List.copyOf(rows);
 }
 private static void addRow(Set<String>rows,String row)throws IOException{if(row.isEmpty())return;validateRelative(row);if(!rows.add(row)||rows.size()>10000)throw invalid();}
 private static Path checkedLocal(Path raw)throws IOException{
  for(Path part:raw)if(part.toString().equals("..")||part.toString().equals("."))throw invalid();
  Path absolute=raw.toAbsolutePath().normalize();Path current=absolute.getRoot();
  for(Path part:absolute){current=current.resolve(part);if(Files.isSymbolicLink(current))throw invalid();}
  return absolute;
 }
 private static Path localFile(Path path)throws IOException{Path safe=checkedLocal(path);if(!Files.isRegularFile(safe,LinkOption.NOFOLLOW_LINKS))throw invalid();return safe;}
 private static Path directory(Path path)throws IOException{Path safe=checkedLocal(path);if(!Files.isDirectory(safe,LinkOption.NOFOLLOW_LINKS))throw invalid();return safe;}
 private static IOException invalid(){return new IOException("Invalid command or safe local path required");}
 private static void help(PrintStream out){out.println("RevisitLedger "+VERSION+" - offline WARC revisit dependency planning\n"+
  "audit --root CORPUS --files LIST --json REPORT [options]\n"+
  "plan --root CORPUS --files LIST --seeds LIST --json REPORT [options]\n"+
  "explain --report REPORT --record relative/file.warc#ORDINAL\n"+
  "Options: --choices JSON --overwrite --scratch DIRECTORY\n"+
  "Limits: --max-expanded-bytes N --max-records N --max-block-bytes N\n"+
  "        --max-header-bytes N --max-scratch-bytes N (positive decimal bytes/counts)\n"+
  "Lists: UTF-8 root-relative paths, one per line; no URLs, traversal or symlinks.\n"+
  "Exit 0: complete within scope; 1: read corpus with blockers; 2: incomplete/error.\n"+
  "No authenticity, atomic snapshot, full WARC validation, or replay guarantee.");}
 private static final class OutputTarget implements AutoCloseable{
  final Path output;final List<Path>protectedPaths;final boolean overwrite;Path stage;boolean opened;
  OutputTarget(Path output,List<Path>inputs,boolean overwrite)throws IOException{this.output=checkedLocal(output);this.protectedPaths=List.copyOf(inputs);this.overwrite=overwrite;check();}
  void check()throws IOException{
   if(!checkedLocal(output).equals(output)||!Files.isDirectory(output.getParent(),LinkOption.NOFOLLOW_LINKS))throw invalid();
   if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)){
    if(!Files.isRegularFile(output,LinkOption.NOFOLLOW_LINKS))throw invalid();
    for(Path input:protectedPaths)if(Files.isSameFile(output,input))throw invalid();
    if(!overwrite)throw invalid();
   }
  }
  OutputStream open()throws IOException{if(opened)throw invalid();opened=true;stage=Files.createTempFile(output.getParent(),".revisitledger-report-",".tmp");return Files.newOutputStream(stage,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS);}
  void commit()throws IOException{
   if(stage==null||Thread.currentThread().isInterrupted())throw new InterruptedIOException();check();
   if(overwrite){Files.move(stage,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);stage=null;}
   else{Files.createLink(output,stage);Files.delete(stage);stage=null;}
  }
  public void close()throws IOException{if(stage!=null)Files.deleteIfExists(stage);}
 }
}
