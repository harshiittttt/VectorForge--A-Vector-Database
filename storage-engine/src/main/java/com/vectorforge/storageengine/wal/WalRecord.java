package com.vectorforge.storageengine.wal;

import java.util.Arrays;
import java.util.Objects;

public record WalRecord(
    long seqNo,
    OperationType opType,
    String vectorId,
    float[] vector,
    byte[] metadata
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WalRecord walRecord = (WalRecord) o;
        return seqNo == walRecord.seqNo &&
                opType == walRecord.opType &&
                Objects.equals(vectorId, walRecord.vectorId) &&
                Arrays.equals(vector, walRecord.vector) &&
                Arrays.equals(metadata, walRecord.metadata);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(seqNo, opType, vectorId);
        result = 31 * result + Arrays.hashCode(vector);
        result = 31 * result + Arrays.hashCode(metadata);
        return result;
    }
}
