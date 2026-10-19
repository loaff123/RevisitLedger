package revisitledger.model;
import java.util.Objects;
/** Stable logical locator; never a compressed-byte offset. */
public record RecordKey(String relativePath,long ordinal) {
 public RecordKey { Objects.requireNonNull(relativePath); if(relativePath.isEmpty()||ordinal<1)throw new IllegalArgumentException("Invalid record locator"); }
}
