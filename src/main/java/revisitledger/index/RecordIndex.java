package revisitledger.index;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import revisitledger.model.*;
/** Ephemeral disk-backed index. Seal after the scanner returns; close removes owned scratch. */
public interface RecordIndex extends RecordSink, AutoCloseable {
 static RecordIndex create(Path scratchParent,long maxScratchBytes)throws IOException {return SqliteRecordIndex.create(scratchParent,maxScratchBytes);}
 void seal(ScanSummary summary)throws IOException;
 boolean complete();
 String corpusFingerprint()throws IOException;
 Optional<RecordFacts> record(RecordKey key)throws IOException;
 Optional<FileFacts> file(String path)throws IOException;
 Cursor<RecordFacts> records()throws IOException;
 Cursor<RecordFacts> byId(String id)throws IOException;
 Cursor<RecordFacts> byTargetDate(String target,String date)throws IOException;
 Cursor<RecordFacts> candidates(Optional<String> id,Optional<String> target,Optional<String> date)throws IOException;
 Cursor<RecordFacts> concretePayloads(DigestValue digest)throws IOException;
 Cursor<RecordFacts> recordsInFile(String path)throws IOException;
 Cursor<FileFacts> files()throws IOException;
 Cursor<Finding> findings()throws IOException;
 StateStore state(String namespace)throws IOException;
 @Override void close()throws IOException;
}
