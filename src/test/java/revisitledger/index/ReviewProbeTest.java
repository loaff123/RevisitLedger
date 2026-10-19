package revisitledger.index;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import revisitledger.model.*;

/** Independent review probes. Only this file is owned by the reviewer. */
class ReviewProbeTest {
 @TempDir Path temp;
 @Test void jdbcPragmaSyntaxInScratchParentMustRemainLiteralPath() throws Exception {
  Path parent=Files.createDirectory(temp.resolve("scratch?jdbc.explicit_readonly=false"));
  verifyLiteralPathAndCleanup(parent);
  assertFalse(Files.exists(temp.resolve("scratch")),"Database outside owned scratch leaked after close");
 }
 @Test void sqlPragmaSyntaxInScratchParentMustRemainLiteralPath() throws Exception {
  Path parent=Files.createDirectory(temp.resolve("scratch?read_uncommitted=false"));
  verifyLiteralPathAndCleanup(parent);
  assertFalse(Files.exists(temp.resolve("scratch")),"Database outside owned scratch leaked after close");
 }
 @Test void uriSignificantCharactersRemainLiteralPath() throws Exception {
  Path parent=Files.createDirectory(temp.resolve("space # percent%20 ü ? a&b=c"));
  verifyLiteralPathAndCleanup(parent);
  try(var paths=Files.list(temp)){assertEquals(List.of(parent),paths.toList());}
 }
 @Test void quotaFailureWithUriSignificantParentLeavesNoDatabaseAnywhere() throws Exception {
  Path parent=Files.createDirectory(temp.resolve("scratch?jdbc.explicit_readonly=false"));
  assertThrows(IOException.class,()->RecordIndex.create(parent,1));
  try(var paths=Files.walk(temp)){assertEquals(List.of(temp,parent),paths.toList());}
 }
 private void verifyLiteralPathAndCleanup(Path parent) throws Exception {
  try(var index=RecordIndex.create(parent,8L<<20)) {
   index.seal(new ScanSummary(0,0,true));
   assertTrue(index.complete());
   try(var paths=Files.walk(parent)) {
    assertTrue(paths.anyMatch(p->p.getFileName().toString().equals("index.sqlite")),"Database escaped owned scratch directory");
   }
  }
  try(var paths=Files.list(parent)){assertEquals(0,paths.count(),"Owned scratch leaked after close");}
 }
}
