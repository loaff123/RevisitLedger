package revisitledger.index;

import revisitledger.model.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.sqlite.util.OSInfo;

/** One-use SQLite scratch database. Not thread safe; archive data is immutable after sealing. */
final class SqliteRecordIndex implements RecordIndex {
 private static final int PAGE=4096, MAX_OPEN_CURSORS=64, MAX_STATE_VALUE=65536, MAX_STATE_KEY=8192;
 static final String MARK_INCOMPLETE_SQL="UPDATE files SET complete=0 WHERE path=?";
 static final String CLEAR_STATE_SQL="DELETE FROM state WHERE namespace=?";
 private static boolean nativeReady;
 private final Path directory;
 private final long budget;
 private Connection db;
 private boolean sealed,complete,failed,closed;
 private String fingerprint;
 private final Set<DbCursor<?>> cursors=Collections.newSetFromMap(new IdentityHashMap<>());
 private SqliteRecordIndex(Path dir,long budget){directory=dir;this.budget=budget;}
 static RecordIndex create(Path parent,long budget)throws IOException {
  if(budget<=0)throw new IllegalArgumentException("Scratch limit must be positive");
  Path directory=Files.createTempDirectory(parent,"revisit-index-");var index=new SqliteRecordIndex(directory,budget);
  try{index.open();return index;}catch(Exception e){try{index.close();}catch(IOException cleanup){e.addSuppressed(cleanup);}if(e instanceof IOException io)throw io;throw new IOException("Cannot initialize scratch index",e);}
 }
 private void open()throws Exception {
  String libraryName=System.mapLibraryName("sqlitejdbc");if(OSInfo.getOSName().equals("Mac"))libraryName="libsqlitejdbc.dylib";
  String resource="/org/sqlite/native/"+OSInfo.getNativeLibFolderPathForCurrentOS()+"/"+libraryName;
  long nativeBytes=0;try(var in=SqliteRecordIndex.class.getResourceAsStream(resource)){if(in==null)throw new IOException("Unsupported SQLite native platform");byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)nativeBytes+=n;}
  // Reserve the bundled library even when this JVM already loaded it. No driver spill escapes quota.
  long pages=(budget-nativeBytes)/PAGE;if(pages<32)throw new IOException("Scratch limit cannot hold SQLite metadata and native driver");
  synchronized(SqliteRecordIndex.class){
   String oldPath=System.getProperty("org.sqlite.lib.path"),oldName=System.getProperty("org.sqlite.lib.name"),oldTmp=System.getProperty("org.sqlite.tmpdir");
   try{
    if(!nativeReady){try(var in=SqliteRecordIndex.class.getResourceAsStream(resource)){Files.copy(in,directory.resolve(libraryName));}}
    System.setProperty("org.sqlite.lib.path",directory.toAbsolutePath().toString());System.setProperty("org.sqlite.lib.name",libraryName);System.setProperty("org.sqlite.tmpdir",directory.toAbsolutePath().toString());
    db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite").toAbsolutePath().toUri().toASCIIString());nativeReady=true;
   }finally{restore("org.sqlite.lib.path",oldPath);restore("org.sqlite.lib.name",oldName);restore("org.sqlite.tmpdir",oldTmp);}
  }
  execute("PRAGMA page_size=4096");execute("PRAGMA max_page_count="+Math.min(pages,4294967294L));
  // This is disposable scratch. OFF prevents rollback/WAL files; any write error poisons the index.
  execute("PRAGMA journal_mode=OFF");execute("PRAGMA synchronous=OFF");execute("PRAGMA mmap_size=0");execute("PRAGMA cache_size=-1024");execute("PRAGMA temp_store=MEMORY");execute("PRAGMA automatic_index=OFF");execute("PRAGMA trusted_schema=OFF");
  execute("PRAGMA user_version=1");
  execute("CREATE TABLE records(path TEXT NOT NULL, ordinal INTEGER NOT NULL, id TEXT, target TEXT, seconds INTEGER, nanos INTEGER, provider INTEGER NOT NULL, sha1 TEXT, sha256 TEXT, data BLOB NOT NULL, PRIMARY KEY(path,ordinal)) WITHOUT ROWID");
  execute("CREATE INDEX record_id ON records(id,path,ordinal)");execute("CREATE INDEX record_target ON records(target,path,ordinal)");execute("CREATE INDEX record_date ON records(seconds,nanos,path,ordinal)");execute("CREATE INDEX record_target_date ON records(target,seconds,nanos,path,ordinal)");
  execute("CREATE INDEX payload_sha1 ON records(sha1,path,ordinal) WHERE provider=1");execute("CREATE INDEX payload_sha256 ON records(sha256,path,ordinal) WHERE provider=1");
  execute("CREATE TABLE files(path TEXT PRIMARY KEY,sha256 TEXT NOT NULL,count INTEGER NOT NULL,complete INTEGER NOT NULL) WITHOUT ROWID");
  execute("CREATE TABLE findings(seq INTEGER PRIMARY KEY,path TEXT NOT NULL,ordinal INTEGER NOT NULL,data BLOB NOT NULL)");execute("CREATE INDEX finding_order ON findings(path,ordinal,data,seq)");
  execute("CREATE TABLE state(namespace TEXT NOT NULL,key TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(namespace,key)) WITHOUT ROWID");
  checkDisk();
 }
 private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
 private void execute(String sql)throws SQLException{try(var s=db.createStatement()){s.execute(sql);}}
 private void checkOpen()throws IOException{if(closed)throw new IOException("Index is closed");if(failed)throw new IOException("Index is invalid after an earlier failure");}
 private void checkRead()throws IOException{checkOpen();if(!sealed)throw new IOException("Seal the completed scan before querying");}
 private void checkWrite()throws IOException{checkOpen();if(sealed)throw new IOException("Archive index is sealed");}
 private IOException fail(Exception cause){failed=true;complete=false;return new IOException("Scratch index operation failed; discard this index",cause);}
 private void checkDisk()throws IOException{long size=0;try(var stream=Files.list(directory)){for(var it=stream.iterator();it.hasNext();){var p=it.next();if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected scratch entry");size=Math.addExact(size,Files.size(p));}}if(size>budget)throw fail(new IOException("Scratch disk limit exceeded"));}
 private interface Binder{void bind(PreparedStatement s)throws SQLException;}
 private void mutate(String sql,Binder bind)throws IOException {
  checkOpen();try{db.setAutoCommit(false);try(var s=db.prepareStatement(sql)){bind.bind(s);s.executeUpdate();}db.commit();db.setAutoCommit(true);checkDisk();}catch(SQLException|IOException e){try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw fail(e);}
 }
 private static Instant date(String value){if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,9})?Z"))return null;try{return Instant.parse(value);}catch(RuntimeException e){return null;}}
 @Override public void record(RecordFacts r)throws IOException {
  checkWrite();byte[] bytes;try{bytes=FactCodec.record(r);}catch(IOException e){throw fail(e);}Instant date=date(r.header("WARC-Date").orElse(null));
  boolean provider=(r.type().equals("resource")||r.type().equals("response"))&&r.payload().map(PayloadEvidence::complete).orElse(false)&&r.findings().isEmpty();
  String sha1=r.payload().map(p->p.hexDigests().get("sha1")).orElse(null),sha256=r.payload().map(p->p.hexDigests().get("sha256")).orElse(null);
  mutate("INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?)",s->{s.setString(1,r.key().relativePath());s.setLong(2,r.key().ordinal());s.setString(3,r.header("WARC-Record-ID").orElse(null));s.setString(4,r.header("WARC-Target-URI").orElse(null));if(date==null){s.setNull(5,Types.BIGINT);s.setNull(6,Types.INTEGER);}else{s.setLong(5,date.getEpochSecond());s.setInt(6,date.getNano());}s.setBoolean(7,provider);s.setString(8,sha1);s.setString(9,sha256);s.setBytes(10,bytes);});
 }
 @Override public void file(FileFacts f)throws IOException {checkWrite();if(f.recordCount()<0||f.relativePath().isEmpty())throw fail(new IOException("Invalid file metadata"));mutate("INSERT INTO files VALUES(?,?,?,?)",s->{s.setString(1,f.relativePath());s.setString(2,f.sha256());s.setLong(3,f.recordCount());s.setBoolean(4,f.complete()&&f.sha256().matches("[0-9a-f]{64}"));});}
 @Override public void finding(Finding f)throws IOException {checkWrite();byte[] bytes;try{bytes=FactCodec.finding(f);}catch(IOException e){throw fail(e);}mutate("INSERT INTO findings(path,ordinal,data) VALUES(?,?,?)",s->{s.setString(1,f.record().map(RecordKey::relativePath).orElse(f.evidence().getOrDefault("file","")));s.setLong(2,f.record().map(RecordKey::ordinal).orElse(0L));s.setBytes(3,bytes);});}
 private long number(String sql,Object... args)throws IOException {try(var s=prepare(sql,args);var r=s.executeQuery()){return r.next()?r.getLong(1):0;}catch(SQLException e){throw fail(e);}}
 @Override public void seal(ScanSummary summary)throws IOException {
  checkWrite();try{sealInternal(summary);}catch(IOException e){throw fail(e);}
 }
 private void sealInternal(ScanSummary summary)throws IOException {
  boolean ok=summary.complete();long fileCount=number("SELECT count(*) FROM files"),recordCount=number("SELECT count(*) FROM records");
  ok&=summary.fileCount()==fileCount&&summary.recordCount()==recordCount&&summary.fileCount()>=0&&summary.recordCount()>=0;
  ok&=number("SELECT EXISTS(SELECT 1 FROM records r WHERE NOT EXISTS(SELECT 1 FROM files f WHERE f.path=r.path))")==0;
  String previous="";
  while(true){
   String path;long count;boolean fileComplete;
   try(var statement=prepare("SELECT path,count,complete FROM files WHERE path>? ORDER BY path LIMIT 1",previous);var result=statement.executeQuery()){
    if(!result.next())break;path=result.getString(1);count=result.getLong(2);fileComplete=result.getBoolean(3);
   }catch(SQLException e){throw fail(e);}
   boolean valid=number("SELECT count(*) FROM records WHERE path=?",path)==count;
   if(count>0)valid&=number("SELECT min(ordinal) FROM records WHERE path=?",path)==1&&number("SELECT max(ordinal) FROM records WHERE path=?",path)==count;
   if(!valid)mutate(MARK_INCOMPLETE_SQL,statement->statement.setString(1,path));
   ok&=valid&&fileComplete;previous=path;
  }
  ok&=number("SELECT EXISTS(SELECT 1 FROM files WHERE complete=0)")==0;
  complete=ok;sealed=true;fingerprint=calculateFingerprint();checkDisk();
 }
 private String calculateFingerprint()throws IOException {
  try{var md=MessageDigest.getInstance("SHA-256");var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),md));FactCodec.string(out,"RevisitLedger index/corpus v1");out.writeBoolean(complete);out.writeLong(number("SELECT count(*) FROM files"));out.writeLong(number("SELECT count(*) FROM records"));out.writeLong(number("SELECT count(*) FROM findings"));
   try(var c=files()){while(c.next()){var f=c.value();FactCodec.string(out,f.relativePath());FactCodec.string(out,f.sha256());out.writeLong(f.recordCount());out.writeBoolean(f.complete());}}
   try(var c=query("SELECT data FROM records ORDER BY path,ordinal",rs->rs.getBytes(1))){while(c.next()){byte[] row=c.value();out.writeInt(row.length);out.write(row);}}
   try(var c=query("SELECT data FROM findings INDEXED BY finding_order ORDER BY path,ordinal,data,seq",rs->rs.getBytes(1))){while(c.next()){byte[] row=c.value();out.writeInt(row.length);out.write(row);}}
   out.flush();return HexFormat.of().formatHex(md.digest());
  }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
 }
 @Override public boolean complete(){return sealed&&complete&&!failed&&!closed;}
 @Override public String corpusFingerprint()throws IOException{checkRead();return fingerprint;}
 private PreparedStatement prepare(String sql,Object... args)throws SQLException {var s=db.prepareStatement(sql);for(int n=0;n<args.length;n++)s.setObject(n+1,args[n]);return s;}
 private interface Mapper<T>{T map(ResultSet r)throws SQLException,IOException;}
 private <T> Cursor<T> query(String sql,Mapper<T> mapper,Object... args)throws IOException {
  checkRead();if(cursors.size()>=MAX_OPEN_CURSORS)throw new IOException("Close cursors before opening more");PreparedStatement statement=null;
  try{statement=prepare(sql,args);var cursor=new DbCursor<T>(statement,statement.executeQuery(),mapper);cursors.add(cursor);return cursor;}catch(SQLException e){if(statement!=null)try{statement.close();}catch(SQLException ignored){}throw fail(e);}
 }
 private final class DbCursor<T> implements Cursor<T>{final PreparedStatement statement;final ResultSet results;final Mapper<T> mapper;boolean done,shut;String stateNamespace;T value;DbCursor(PreparedStatement s,ResultSet r,Mapper<T> mapper){statement=s;results=r;this.mapper=mapper;}public boolean next()throws IOException{checkRead();if(shut){if(done)return false;throw new IOException("Cursor is closed");}try{if(results.next()){value=mapper.map(results);return true;}done=true;close();return false;}catch(SQLException|IOException e){throw fail(e);}}public T value(){if(shut||value==null)throw new IllegalStateException("Cursor has no current row");return value;}public void close()throws IOException{if(shut)return;shut=true;value=null;cursors.remove(this);try{results.close();statement.close();}catch(SQLException e){throw fail(e);}}}
 private Cursor<RecordFacts> recordQuery(String sql,Object... args)throws IOException{return query(sql,rs->FactCodec.record(rs.getBytes(1)),args);}
 @Override public Optional<RecordFacts> record(RecordKey key)throws IOException{try(var c=recordQuery("SELECT data FROM records WHERE path=? AND ordinal=?",key.relativePath(),key.ordinal())){return c.next()?Optional.of(c.value()):Optional.empty();}}
 @Override public Optional<FileFacts> file(String path)throws IOException{try(var c=query("SELECT path,sha256,count,complete FROM files WHERE path=?",SqliteRecordIndex::fileFact,path)){return c.next()?Optional.of(c.value()):Optional.empty();}}
 private static FileFacts fileFact(ResultSet r)throws SQLException{return new FileFacts(r.getString(1),r.getString(2),r.getLong(3),r.getBoolean(4));}
 @Override public Cursor<RecordFacts> records()throws IOException{return recordQuery("SELECT data FROM records ORDER BY path,ordinal");}
 @Override public Cursor<RecordFacts> byId(String id)throws IOException{return candidates(Optional.of(id),Optional.empty(),Optional.empty());}
 @Override public Cursor<RecordFacts> byTargetDate(String target,String date)throws IOException{return candidates(Optional.empty(),Optional.of(target),Optional.of(date));}
 @Override public Cursor<RecordFacts> candidates(Optional<String> id,Optional<String> target,Optional<String> rawDate)throws IOException {
  Instant instant=rawDate.map(SqliteRecordIndex::date).orElse(null);if(rawDate.isPresent()&&instant==null)return recordQuery("SELECT data FROM records WHERE 0 ORDER BY path,ordinal");
  String index=id.isPresent()?"record_id":target.isPresent()?(instant==null?"record_target":"record_target_date"):instant!=null?"record_date":null;
  var sql=new StringBuilder("SELECT data FROM records");if(index!=null)sql.append(" INDEXED BY ").append(index);sql.append(" WHERE 1");var args=new ArrayList<Object>();
  if(id.isPresent()){sql.append(" AND id=?");args.add(id.get());}if(target.isPresent()){sql.append(" AND target=?");args.add(target.get());}if(instant!=null){sql.append(" AND seconds=? AND nanos=?");args.add(instant.getEpochSecond());args.add(instant.getNano());}sql.append(" ORDER BY path,ordinal");return recordQuery(sql.toString(),args.toArray());
 }
 @Override public Cursor<RecordFacts> concretePayloads(DigestValue d)throws IOException{String column=d.algorithm().equals("sha1")?"sha1":"sha256";return recordQuery("SELECT data FROM records INDEXED BY payload_"+column+" WHERE provider=1 AND "+column+"=? AND EXISTS(SELECT 1 FROM files WHERE files.path=records.path AND complete=1) ORDER BY path,ordinal",d.lowercaseHex());}
 @Override public Cursor<RecordFacts> recordsInFile(String path)throws IOException{return recordQuery("SELECT data FROM records WHERE path=? ORDER BY path,ordinal",path);}
 @Override public Cursor<FileFacts> files()throws IOException{return query("SELECT path,sha256,count,complete FROM files ORDER BY path",SqliteRecordIndex::fileFact);}
 @Override public Cursor<Finding> findings()throws IOException{return query("SELECT data FROM findings INDEXED BY finding_order ORDER BY path,ordinal,data,seq",rs->FactCodec.finding(rs.getBytes(1)));}
 @Override public StateStore state(String namespace)throws IOException{checkRead();if(!namespace.matches("[A-Za-z0-9_.-]{1,64}"))throw new IOException("Invalid state namespace");return new StateStore(){
  private void key(String key)throws IOException{checkRead();if(key.length()>MAX_STATE_KEY||key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_STATE_KEY)throw new IOException("State key exceeds limit");}
  public Optional<byte[]> get(String key)throws IOException{key(key);try(var c=query("SELECT value FROM state WHERE namespace=? AND key=?",r->r.getBytes(1),namespace,key)){return c.next()?Optional.of(c.value()):Optional.empty();}}
  private void writable()throws IOException{checkRead();for(var cursor:cursors)if(namespace.equals(cursor.stateNamespace))throw new IOException("Close the state cursor before changing its namespace");}
  public void put(String key,byte[] value)throws IOException{key(key);writable();if(value.length>MAX_STATE_VALUE)throw new IOException("State value exceeds limit");mutate("INSERT INTO state VALUES(?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET value=excluded.value",s->{s.setString(1,namespace);s.setString(2,key);s.setBytes(3,value);});}
  public void remove(String key)throws IOException{key(key);writable();mutate("DELETE FROM state WHERE namespace=? AND key=?",s->{s.setString(1,namespace);s.setString(2,key);});}
  public void clear()throws IOException{writable();mutate(CLEAR_STATE_SQL,s->s.setString(1,namespace));}
  public Cursor<StateEntry> scan()throws IOException{var cursor=(DbCursor<StateEntry>)query("SELECT key,value FROM state WHERE namespace=? ORDER BY key",r->new StateEntry(r.getString(1),r.getBytes(2)),namespace);cursor.stateNamespace=namespace;return cursor;}
 };}
 @Override public void close()throws IOException {
  if(closed)return;IOException error=null;for(var c:List.copyOf(cursors))try{c.close();}catch(IOException e){error=e;}closed=true;complete=false;if(db!=null)try{db.close();}catch(SQLException e){error=new IOException("Cannot close scratch index",e);}
  try(var stream=Files.list(directory)){for(var it=stream.iterator();it.hasNext();)Files.delete(it.next());}catch(IOException e){error=e;}try{Files.deleteIfExists(directory);}catch(IOException e){error=e;}if(error!=null)throw error;
 }
}
