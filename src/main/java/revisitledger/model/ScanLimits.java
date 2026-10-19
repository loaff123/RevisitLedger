package revisitledger.model;
/** All byte counts are binary bytes. Ingestion uses no scratch files. */
public record ScanLimits(long maxExpandedBytes,long maxRecords,long maxBlockBytes,int maxHeaderBytes,long maxScratchBytes) {
 public ScanLimits {if(maxExpandedBytes<=0||maxRecords<=0||maxBlockBytes<=0||maxHeaderBytes<=0||maxScratchBytes<=0)throw new IllegalArgumentException("Limits must be positive");}
 public static ScanLimits defaults(){return new ScanLimits(64L<<30,1000000,4L<<30,65536,4L<<30);}
}
