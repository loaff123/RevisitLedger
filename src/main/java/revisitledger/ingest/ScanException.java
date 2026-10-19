package revisitledger.ingest;
import java.io.IOException;
/** Stable safe diagnostic: never embeds an absolute path or archive-controlled text. */
public final class ScanException extends IOException {
 private final String code;
 public ScanException(String code,String message){super(message);this.code=code;}
 public String code(){return code;}
}
