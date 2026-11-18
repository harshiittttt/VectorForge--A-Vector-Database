package com.vectorforge.storageengine.wal;

public enum OperationType {
    UPSERT((byte) 0),
    DELETE((byte) 1);

    private final byte code;

    OperationType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static OperationType fromCode(byte code) {
        if (code == 0) return UPSERT;
        if (code == 1) return DELETE;
        throw new IllegalArgumentException("Unknown operation code: " + code);
    }
}
