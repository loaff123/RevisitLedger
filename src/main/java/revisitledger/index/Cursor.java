package revisitledger.index;
import java.io.IOException;
/** Closeable forward cursor. Only the current row is retained; value requires successful next. */
public interface Cursor<T> extends AutoCloseable { boolean next() throws IOException; T value(); @Override void close() throws IOException; }
