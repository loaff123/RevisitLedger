package revisitledger.index;
public record StateEntry(String key,byte[] value) {public StateEntry {value=value.clone();} @Override public byte[] value(){return value.clone();}}
