package com.vectorforge.storageengine.memtable;

import com.vectorforge.storageengine.wal.WalRecord;

import java.util.Collection;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory sorted map that buffers recent writes.
 * Vectors are kept sorted by their ID to allow sequential writing to SSTables during a flush.
 */
public class Memtable {
    private final ConcurrentSkipListMap<String, WalRecord> skipList;
    private final AtomicLong sizeInBytes;
    private final long maxSizeBytes;

    public Memtable(long maxSizeBytes) {
        this.skipList = new ConcurrentSkipListMap<>();
        this.sizeInBytes = new AtomicLong(0);
        this.maxSizeBytes = maxSizeBytes;
    }

    /**
     * @return true if the memtable has exceeded its maximum size and should be flushed.
     */
    public boolean put(WalRecord record) {
        WalRecord old = skipList.put(record.vectorId(), record);
        
        long sizeDelta = estimateSize(record);
        if (old != null) {
            sizeDelta -= estimateSize(old);
        }
        
        long currentSize = sizeInBytes.addAndGet(sizeDelta);
        return currentSize >= maxSizeBytes;
    }

    public WalRecord get(String vectorId) {
        return skipList.get(vectorId);
    }

    public Collection<WalRecord> getSnapshot() {
        return skipList.values();
    }

    public long getSizeInBytes() {
        return sizeInBytes.get();
    }
    
    public boolean isFull() {
        return sizeInBytes.get() >= maxSizeBytes;
    }

    public void clear() {
        skipList.clear();
        sizeInBytes.set(0);
    }

    private long estimateSize(WalRecord record) {
        // String overhead roughly ~ 2 bytes per char + object overhead
        long size = record.vectorId().length() * 2L + 24;
        if (record.vector() != null) {
            size += record.vector().length * 4L + 16;
        }
        if (record.metadata() != null) {
            size += record.metadata().length + 16;
        }
        return size;
    }
}
