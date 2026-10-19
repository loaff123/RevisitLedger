package revisitledger.ingest;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InputFilesTest {
 @TempDir Path dir;
 @Test void inputAliasAndTraversalRejected()throws Exception {
  Files.writeString(dir.resolve("a.warc"),"data");Files.createLink(dir.resolve("b.warc"),dir.resolve("a.warc"));
  for(String name:List.of("../a.warc","/tmp/a.warc","https://example/a.warc","./a.warc","a/../a.warc","a\\b.warc"))assertThrows(java.io.IOException.class,()->InputFiles.resolve(dir,List.of(name)));
  assertThrows(java.io.IOException.class,()->InputFiles.resolve(dir,List.of("a.warc","b.warc")));
  assertThrows(java.io.IOException.class,()->InputFiles.checkOutput(dir,"b.warc",List.of(dir.resolve("a.warc")),true));
 }
 @Test void nonRegularAndSymlinkPathsRejected()throws Exception {
  Files.createDirectory(dir.resolve("folder.warc"));assertThrows(java.io.IOException.class,()->InputFiles.resolve(dir,List.of("folder.warc")));
  Files.createSymbolicLink(dir.resolve("link.warc"),Path.of("/etc/passwd"));assertThrows(java.io.IOException.class,()->InputFiles.resolve(dir,List.of("link.warc")));
 }
 @Test void safePathsSortAndNewOutputsDoNotClobber()throws Exception {
  Files.writeString(dir.resolve("a.warc"),"a");Files.writeString(dir.resolve("b.warc.gz"),"b");
  assertEquals(List.of(dir.resolve("a.warc"),dir.resolve("b.warc.gz")),InputFiles.resolve(dir,List.of("b.warc.gz","a.warc")));
  assertEquals(dir.resolve("report.json"),InputFiles.checkOutput(dir,"report.json",List.of(dir.resolve("a.warc")),false));
  Files.writeString(dir.resolve("report.json"),"old");assertThrows(java.io.IOException.class,()->InputFiles.checkOutput(dir,"report.json",List.of(),false));
 }
}
