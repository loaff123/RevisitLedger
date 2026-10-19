package revisitledger.ingest;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.netpreserve.jwarc.*;
import revisitledger.model.*;

/** Bounded, read-only ingestion. A sink must honor the final file/scan completeness. */
public final class ArchiveScanner {
 private static final Set<String> SINGLETONS=Set.of("content-length","content-type","warc-type","warc-record-id","warc-date","warc-target-uri","warc-profile","warc-refers-to","warc-refers-to-target-uri","warc-refers-to-date","warc-block-digest","warc-payload-digest","warc-truncated","warc-segment-number","warc-segment-origin-id","warc-segment-total-length","warc-warcinfo-id","warc-identified-payload-type","warc-ip-address","warc-filename");
 private static final Set<String> PROFILES=Set.of("http://netpreserve.org/warc/1.0/revisit/identical-payload-digest","http://netpreserve.org/warc/1.1/revisit/identical-payload-digest");
 public ScanSummary scan(Path root,List<String> relativeFiles,ScanLimits limits,RecordSink sink)throws IOException {
  try{return scanInternal(root,relativeFiles,limits,sink);}catch(SinkFailure e){throw e.cause;}
 }
 private ScanSummary scanInternal(Path root,List<String> relativeFiles,ScanLimits limits,RecordSink sink)throws IOException {
  Objects.requireNonNull(limits);Objects.requireNonNull(sink);
  Path base=InputFiles.checkedRoot(root);List<Path> paths=InputFiles.resolve(base,relativeFiles);
  StrictArchiveInput.Budget budget=new StrictArchiveInput.Budget(limits.maxExpandedBytes());
  long files=0,records=0;boolean complete=true;
  for(Path path:paths){
   String relative=base.relativize(path).toString().replace(File.separatorChar,'/');long ordinal=0;boolean fileComplete=true;Snapshot before=null;
   try{
    before=snapshot(base,path);MessageDigest scanned=PayloadHasher.digest("SHA-256");
    try(InputStream raw=new DigestInputStream(openRead(base,path),scanned);InputStream in=StrictArchiveInput.open(raw,limits,budget)){
     while(true){
      byte[] header=Framing.header(in,limits.maxHeaderBytes(),true);if(header==null)break;
      if(records>=limits.maxRecords())throw new ScanException("resource_limit","Record-count limit reached");
      WarcParser parsed=Framing.warc(header);String version=parsed.version().toString();
      if(!version.equals("WARC/1.0")&&!version.equals("WARC/1.1"))throw new ScanException("input_incomplete","Only WARC 1.0 and 1.1 are supported");
      MessageHeaders headers=parsed.headers();validateHeaders(headers);
      long length=Framing.length(headers.sole("Content-Length").orElseThrow(),"input_incomplete");
      if(length>limits.maxBlockBytes())throw new ScanException("resource_limit","Individual block-byte limit reached");
      RecordKey key=new RecordKey(relative,ordinal+1);String type=headers.sole("WARC-Type").orElseThrow();var findings=new ArrayList<Finding>();
      inspectMetadata(headers,type,key,findings);
      Framing.Block block=new Framing.Block(in,length);MessageDigest sha1=PayloadHasher.digest("SHA-1"),sha256=PayloadHasher.digest("SHA-256");
      InputStream body=new DigestInputStream(new DigestInputStream(block,sha1),sha256);Optional<PayloadEvidence> payload=Optional.empty();
      try{
       if(type.equals("resource")||type.equals("response")){
        if(isUnsupportedProvider(headers,type))add(findings,key,"unsupported_provider","Truncated, segmented, unknown-scheme or unsupported response providers cannot certify payloads");
        else payload=Optional.of(type.equals("resource")?PayloadHasher.resource(body,length):PayloadHasher.http(body,length,limits.maxHeaderBytes()));
       }else if(type.equals("revisit")){
        if(length>0&&!isHttp(headers))add(findings,key,"unsupported_provider","Non-HTTP revisit blocks are unsupported");
        else PayloadHasher.revisit(body,length,limits.maxHeaderBytes());
       }else if(!Set.of("metadata","warcinfo").contains(type))add(findings,key,"unsupported_provider","This record kind is not a supported concrete payload provider");
      }catch(ScanException e){
       if(!e.code().equals("http_framing")&&!e.code().equals("unsupported_provider"))throw e;
       add(findings,key,e.code(),e.getMessage());if(e.code().equals("http_framing"))fileComplete=false;
      }
      Framing.drain(body);Framing.trailer(in);
      checkDigest(headers,"WARC-Block-Digest",Map.of("sha1",HexFormat.of().formatHex(sha1.digest()),"sha256",HexFormat.of().formatHex(sha256.digest())),key,findings);
      if(payload.isPresent())checkDigest(headers,"WARC-Payload-Digest",payload.get().hexDigests(),key,findings);
      RecordFacts fact=new RecordFacts(key,type,version,headers.map(),payload,findings);ordinal++;records++;
      emitRecord(sink,fact);for(Finding f:findings)emitFinding(sink,f);
     }
    }
    if(!HexFormat.of().formatHex(scanned.digest()).equals(before.sha256))throw new ScanException("input_changed","Input bytes changed during scanning");
   }catch(SinkFailure e){throw e.cause;}
    catch(IOException|IllegalArgumentException e){
     fileComplete=false;String code=e instanceof ScanException s?s.code():"input_incomplete";
     emitFinding(sink,new Finding(code,Optional.empty(),safeMessage(e),Map.of("file",relative)));
   }
   if(before!=null){
    try{if(!before.equals(snapshot(base,path))){fileComplete=false;emitFinding(sink,new Finding("input_changed",Optional.empty(),"Input identity or bytes changed during scanning",Map.of("file",relative)));}}
    catch(IOException e){fileComplete=false;emitFinding(sink,new Finding("input_changed",Optional.empty(),"Input could not be reverified after scanning",Map.of("file",relative)));}
   }
   try{sink.file(new FileFacts(relative,before==null?"":before.sha256,ordinal,fileComplete));}catch(IOException e){throw e;}
   files++;complete&=fileComplete;
  }
  return new ScanSummary(files,records,complete);
 }
 private static void validateHeaders(MessageHeaders h)throws IOException {
  for(var e:h.map().entrySet())if(SINGLETONS.contains(e.getKey().toLowerCase(Locale.ROOT))&&e.getValue().size()!=1)throw new ScanException("duplicate_singleton","Duplicate singleton WARC header");
  for(String key:List.of("WARC-Type","WARC-Record-ID","WARC-Date","Content-Length"))if(h.all(key).size()!=1||h.sole(key).orElse("").isEmpty())throw new ScanException("input_incomplete","Missing required WARC header");
 }
 private static boolean isHttp(MessageHeaders h){return h.sole("Content-Type").map(s->s.split(";",2)[0].strip().equalsIgnoreCase("application/http")).orElse(false);}
 private static boolean isUnsupportedProvider(MessageHeaders h,String type){
  if(h.map().keySet().stream().anyMatch(k->k.equalsIgnoreCase("WARC-Truncated")||k.toLowerCase(Locale.ROOT).startsWith("warc-segment-")))return true;
  try{String scheme=URI.create(h.sole("WARC-Target-URI").orElse("")).getScheme();if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme)&&!(type.equals("resource")&&"ftp".equalsIgnoreCase(scheme)))return true;}catch(IllegalArgumentException e){return true;}
  return type.equals("response")&&!isHttp(h);
 }
 private static void inspectMetadata(MessageHeaders h,String type,RecordKey key,List<Finding> out){
  for(String field:List.of("WARC-Date","WARC-Refers-To-Date"))h.sole(field).ifPresent(date->{
   boolean supported=date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,9})?Z");
   if(supported)try{Instant.parse(date);}catch(RuntimeException e){supported=false;}
   if(!supported)out.add(new Finding("unsupported_precision",Optional.of(key),"Timestamp cannot be matched with the supported exact UTC precision",Map.of("field",field)));
  });
  if(type.equals("revisit")&&!PROFILES.contains(h.sole("WARC-Profile").orElse("")))add(out,key,"unsupported_profile","Revisit profile is outside the identical-payload support contract");
  for(String field:List.of("WARC-Payload-Digest","WARC-Block-Digest"))h.sole(field).ifPresent(raw->{try{DigestValue.parse(raw);}catch(IllegalArgumentException e){out.add(new Finding("unsupported_digest",Optional.of(key),"Digest algorithm or encoding is unsupported",Map.of("field",field)));}});
 }
 private static void checkDigest(MessageHeaders h,String field,Map<String,String> actual,RecordKey key,List<Finding> out){
  h.sole(field).ifPresent(raw->{try{DigestValue value=DigestValue.parse(raw);if(!value.lowercaseHex().equals(actual.get(value.algorithm())))out.add(new Finding("digest_mismatch",Optional.of(key),"Declared digest does not match the complete bytes",Map.of("field",field)));}catch(IllegalArgumentException ignored){/* Already reported in metadata inspection. */}});
 }
 private static void add(List<Finding> out,RecordKey key,String code,String text){out.add(new Finding(code,Optional.of(key),text,Map.of()));}
 private record Snapshot(Object identity,long size,java.nio.file.attribute.FileTime modified,String sha256) {}
 private static Snapshot snapshot(Path base,Path path)throws IOException {
  InputFiles.recheck(base,path);
  BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!before.isRegularFile())throw new ScanException("input_changed","Input is no longer a regular file");
  MessageDigest digest=PayloadHasher.digest("SHA-256");try(InputStream stream=new DigestInputStream(openRead(base,path),digest)){Framing.drain(stream);}
  BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  if(!Objects.equals(before.fileKey(),after.fileKey())||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new ScanException("input_changed","Input changed during fingerprinting");
  return new Snapshot(after.fileKey(),after.size(),after.lastModifiedTime(),HexFormat.of().formatHex(digest.digest()));
 }
 private static InputStream openRead(Path base,Path p)throws IOException{InputFiles.recheck(base,p);return Files.newInputStream(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);}
 private static String safeMessage(Exception e){return e instanceof ScanException?e.getMessage():"Archive scan did not complete; input, compression, or I/O failed";}
 private static final class SinkFailure extends RuntimeException {final IOException cause;SinkFailure(IOException cause){this.cause=cause;}}
 private static void emitRecord(RecordSink sink,RecordFacts fact){try{sink.record(fact);}catch(IOException e){throw new SinkFailure(e);}}
 private static void emitFinding(RecordSink sink,Finding finding){try{sink.finding(finding);}catch(IOException e){throw new SinkFailure(e);}}
}
