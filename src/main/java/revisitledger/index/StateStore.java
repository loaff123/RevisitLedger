package revisitledger.index;
import java.io.IOException;
import java.util.Optional;
/** Private namespace of bounded values; keys are ordered by exact SQLite binary text order. */
public interface StateStore {Optional<byte[]> get(String key)throws IOException;void put(String key,byte[] value)throws IOException;void remove(String key)throws IOException;void clear()throws IOException;Cursor<StateEntry> scan()throws IOException;}
