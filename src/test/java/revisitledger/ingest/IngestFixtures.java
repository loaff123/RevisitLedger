package revisitledger.ingest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import revisitledger.model.*;
final class IngestFixtures {
 static byte[] ascii(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
 static byte[] record(String type, byte[] body, String extra) throws IOException {
  var out=new ByteArrayOutputStream();
  out.write(ascii("WARC/1.1\r\nWARC-Type: "+type+"\r\nWARC-Record-ID: <urn:uuid:test>\r\nWARC-Date: 2020-01-01T00:00:00Z\r\nWARC-Target-URI: https://EXAMPLE.com/a%2fb/\r\nContent-Length: "+body.length+"\r\n"+extra+"\r\n"));
  out.write(body);out.write(ascii("\r\n\r\n"));return out.toByteArray();
 }
 static byte[] http(String h,String body) {return ascii("HTTP/1.1 200 OK\r\n"+h+"\r\n"+body);}
 static byte[] gzip(byte[] b) throws IOException {var out=new ByteArrayOutputStream();try(var g=new GZIPOutputStream(out)){g.write(b);}return out.toByteArray();}
 static byte[] cat(byte[]...parts)throws IOException {var out=new ByteArrayOutputStream();for(var p:parts)out.write(p);return out.toByteArray();}
 static final class Sink implements RecordSink {
  final List<RecordFacts> records=new ArrayList<>();final List<FileFacts> files=new ArrayList<>();final List<Finding> findings=new ArrayList<>();
  public void record(RecordFacts r){records.add(r);}public void file(FileFacts f){files.add(f);}public void finding(Finding f){findings.add(f);}
  boolean has(String code){return findings.stream().anyMatch(f->f.code().equals(code));}
 }
}
