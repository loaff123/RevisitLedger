package revisitledger.model;
import java.io.IOException;
public interface RecordSink {void file(FileFacts f)throws IOException;void record(RecordFacts r)throws IOException;void finding(Finding f)throws IOException;}
