package revisitledger.ingest;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local-only path validation. Symlinks are deliberately unsupported, even inside the root. */
public final class InputFiles {
 private InputFiles() {}
 /** Check every lexical root component before canonicalization can erase symlink evidence. */
 public static Path checkedRoot(Path root)throws IOException {
  Path absolute=root.toAbsolutePath();Path current=absolute.getRoot();
  if(current==null||!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw invalid();
  for(Path part:absolute){current=current.resolve(part);if(Files.isSymbolicLink(current)||!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw invalid();}
  return absolute.toRealPath();
 }
 public static List<Path> resolve(Path root,List<String> files)throws IOException {
  Path base=checkedRoot(root);
  var out=new ArrayList<Path>();
  for(String file:files){
   Path p=checkedPath(base,file);if(!(file.endsWith(".warc")||file.endsWith(".warc.gz"))||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw invalid();
   for(Path previous:out)if(Files.isSameFile(p,previous))throw new ScanException("input_alias","Input paths identify the same file");
   out.add(p);
  }
  out.sort(Comparator.comparing(p->base.relativize(p).toString()));return List.copyOf(out);
 }
 public static Path checkOutput(Path root,String file,List<Path> inputs,boolean overwrite)throws IOException {
  Path p=checkedPath(checkedRoot(root),file);
  if(!Files.isDirectory(p.getParent(),LinkOption.NOFOLLOW_LINKS))throw invalid();
  if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){
   if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw invalid();
   for(Path input:inputs)if(Files.isSameFile(p,input))throw new ScanException("input_alias","Output aliases an input file");
   if(!overwrite)throw new ScanException("output_exists","Output requires a new path or explicit overwrite");
  }
  return p;
 }
 static void recheck(Path base,Path path)throws IOException {
  if(!checkedRoot(base).equals(base)||!path.startsWith(base))throw invalid();
  checkedPath(base,base.relativize(path).toString());
  if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw invalid();
 }
 private static Path checkedPath(Path base,String name)throws IOException {
  if(name==null||name.isEmpty()||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.chars().anyMatch(c->c<32||c==127))throw invalid();
  Path rel;try{rel=Path.of(name);}catch(InvalidPathException e){throw invalid();}
  if(rel.isAbsolute())throw invalid();
  for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw invalid();
  Path current=base;for(Path part:rel){current=current.resolve(part);if(Files.isSymbolicLink(current))throw invalid();}
  if(!current.startsWith(base))throw invalid();return current;
 }
 private static ScanException invalid(){return new ScanException("invalid_path","Use distinct local regular files and safe root-relative paths; symlinks are unsupported");}
}
