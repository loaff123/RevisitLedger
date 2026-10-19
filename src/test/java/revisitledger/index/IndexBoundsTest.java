package revisitledger.index;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import revisitledger.model.*;
import static org.junit.jupiter.api.Assertions.*;
class IndexBoundsTest {
 @TempDir Path temp;
 @Test void sqlNeverUsesTemporarySortingAndJournalsAreDisabled()throws Exception{
  try(var index=RecordIndex.create(temp,16L<<20)){
   index.record(RecordIndexTest.fact("a.warc",1));RecordIndexTest.seal(index,"a.warc",1);
   Path db;try(var paths=Files.walk(temp)){db=paths.filter(p->p.getFileName().toString().equals("index.sqlite")).findFirst().orElseThrow();}
   try(var con=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=con.createStatement()){
    for(String sql:List.of("SELECT data FROM records ORDER BY path,ordinal","SELECT data FROM records INDEXED BY record_id WHERE id='x' AND target='x' AND seconds=1 AND nanos=1 ORDER BY path,ordinal","SELECT data FROM records INDEXED BY record_target_date WHERE target='x' AND seconds=1 AND nanos=1 ORDER BY path,ordinal","SELECT data FROM records INDEXED BY record_date WHERE seconds=1 AND nanos=1 ORDER BY path,ordinal","SELECT data FROM records INDEXED BY payload_sha1 WHERE provider=1 AND sha1='x' AND EXISTS(SELECT 1 FROM files WHERE files.path=records.path AND complete=1) ORDER BY path,ordinal","SELECT data FROM findings INDEXED BY finding_order ORDER BY path,ordinal,data,seq","SELECT key,value FROM state WHERE namespace='graph' ORDER BY key")){
     try(var rows=statement.executeQuery("EXPLAIN QUERY PLAN "+sql)){while(rows.next())assertFalse(rows.getString(4).contains("TEMP B-TREE"),sql);}
    }
    for(String sql:List.of(SqliteRecordIndex.MARK_INCOMPLETE_SQL,SqliteRecordIndex.CLEAR_STATE_SQL)){
     try(var rows=statement.executeQuery("EXPLAIN "+sql)){while(rows.next())assertFalse(Set.of("OpenEphemeral","SorterOpen","RowSetAdd").contains(rows.getString(2)),"Unbounded write staging in "+sql);}
    }

   }
   try(var paths=Files.walk(temp)){assertFalse(paths.anyMatch(p->p.getFileName().toString().matches(".*-(journal|wal|shm)$")));}
  }
 }
 @Test void duplicateLocatorFailsClosed()throws Exception{try(var index=RecordIndex.create(temp,8L<<20)){index.record(RecordIndexTest.fact("a.warc",1));assertThrows(IOException.class,()->index.record(RecordIndexTest.fact("a.warc",1)));assertThrows(IOException.class,()->RecordIndexTest.seal(index,"a.warc",1));assertFalse(index.complete());}}
 @Test void sealedMismatchedFileCountsInvalidateIndividualFile()throws Exception{try(var index=RecordIndex.create(temp,8L<<20)){index.record(RecordIndexTest.fact("a.warc",2));index.file(new FileFacts("a.warc","c".repeat(64),1,true));index.seal(new ScanSummary(1,1,true));assertFalse(index.complete());assertFalse(index.file("a.warc").orElseThrow().complete());assertTrue(RecordIndexTest.rows(index.concretePayloads(new DigestValue("sha1",RecordIndexTest.SHA1))).isEmpty());}}
 @Test void fingerprintChangesWhenFindingsChange()throws Exception{String a,b;try(var i=RecordIndex.create(temp,8L<<20)){RecordIndexTest.seal(i,"empty.warc",0);a=i.corpusFingerprint();}try(var i=RecordIndex.create(temp,8L<<20)){i.finding(new Finding("input_changed",Optional.empty(),"Input changed",Map.of("file","empty.warc")));RecordIndexTest.seal(i,"empty.warc",0);b=i.corpusFingerprint();}assertNotEquals(a,b);}
 @Test void stateQuotaSharesTheDatabaseCapAndCannotMasqueradeAsComplete()throws Exception{try(var index=RecordIndex.create(temp,2L<<20)){index.seal(new ScanSummary(0,0,true));var state=index.state("graph");boolean failed=false;for(int n=0;n<1000;n++){try{state.put(String.format("%05d",n),new byte[65536]);}catch(IOException e){failed=true;break;}}assertTrue(failed);assertFalse(index.complete());assertThrows(IOException.class,index::corpusFingerprint);try(var paths=Files.walk(temp)){assertTrue(paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum()<=(2L<<20));}}}
 @Test void cursorLimitIsRecoverableAndExhaustionReleasesResources()throws Exception{try(var i=RecordIndex.create(temp,8L<<20)){i.record(RecordIndexTest.fact("a.warc",1));RecordIndexTest.seal(i,"a.warc",1);var cursors=new ArrayList<Cursor<RecordFacts>>();for(int n=0;n<64;n++)cursors.add(i.records());assertThrows(IOException.class,i::records);for(var c:cursors)c.close();for(int n=0;n<80;n++)try(var c=i.records()){assertTrue(c.next());assertFalse(c.next());assertFalse(c.next());assertThrows(IllegalStateException.class,c::value);}assertTrue(i.complete());}}
 @Test void tenThousandRecordsStreamWithoutAnySideFiles()throws Exception{try(var i=RecordIndex.create(temp,64L<<20)){for(int n=10000;n>0;n--)i.record(RecordIndexTest.fact("a.warc",n));RecordIndexTest.seal(i,"a.warc",10000);long ordinal=0;try(var c=i.byId("<id>")){while(c.next())assertEquals(++ordinal,c.value().key().ordinal());}assertEquals(10000,ordinal);assertTrue(i.complete());try(var paths=Files.walk(temp)){assertFalse(paths.anyMatch(p->p.getFileName().toString().matches(".*-(journal|wal|shm)$")));}}}
 @Test void stateScanCannotBeMutatedUntilClosed()throws Exception{try(var i=RecordIndex.create(temp,8L<<20)){i.seal(new ScanSummary(0,0,true));var s=i.state("graph");s.put("a",new byte[]{1});try(var c=s.scan()){assertTrue(c.next());assertThrows(IOException.class,()->s.put("b",new byte[]{2}));assertThrows(IOException.class,()->s.remove("a"));assertThrows(IOException.class,s::clear);i.state("other").put("x",new byte[]{3});}s.put("b",new byte[]{2});assertTrue(i.complete());}}
 @Test void oversizedFindingSinkFailurePoisonsIndex()throws Exception{try(var i=RecordIndex.create(temp,8L<<20)){assertThrows(IOException.class,()->i.finding(new Finding("code",Optional.empty(),"x".repeat(2<<20),Map.of())));assertThrows(IOException.class,()->i.seal(new ScanSummary(0,0,true)));assertFalse(i.complete());}}
 @Test void sealFailureCannotLeaveCompleteState()throws Exception{try(var i=RecordIndex.create(temp,16L<<20)){i.file(new FileFacts("x".repeat((1<<20)+1),"c".repeat(64),0,true));assertThrows(IOException.class,()->i.seal(new ScanSummary(1,0,true)));assertFalse(i.complete());assertThrows(IOException.class,i::corpusFingerprint);}}
}
