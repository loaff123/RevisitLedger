package revisitledger.model;
public record FileFacts(String relativePath, String sha256, long recordCount, boolean complete) {  }
