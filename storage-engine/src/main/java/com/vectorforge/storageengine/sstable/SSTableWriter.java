package com.vectorforge.storageengine.sstable;

import com.vectorforge.storageengine.wal.WalRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes Memtable contents into an immutable SSTable file on disk.
 * Format: [Data Block] [Sparse Index Block] [Bloom Filter Block] [Footer]
 */
public class SSTableWriter implements AutoCloseable {
    public static final int MAGIC = 0x55AA55AA;
    
    private final FileChannel channel;
    private final BloomFilter bloomFilter;
    private final List<IndexEntry> sparseIndex = new ArrayList<>();
    
    private long currentOffset = 0;
    private int recordsWritten = 0;
    private final int indexInterval = 64; // Record an index entry every 64 records

    private record IndexEntry(String key, long offset) {}

    public SSTableWriter(Path path, int expectedRecords) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        this.bloomFilter = new BloomFilter(Math.max(100, expectedRecords), 0.01);
    }

    public void write(WalRecord record) throws IOException {
        // Record index periodically to create a sparse index
        if (recordsWritten % indexInterval == 0) {
            sparseIndex.add(new IndexEntry(record.vectorId(), currentOffset));
        }
        
        bloomFilter.add(record.vectorId());
        
        byte[] idBytes = record.vectorId().getBytes(StandardCharsets.UTF_8);
        int vecLen = record.vector() != null ? record.vector().length * 4 : 0;
        int metaLen = record.metadata() != null ? record.metadata().length : 0;
        
        // seq(8) + op(1) + idLen(4) + id + vecLen(4) + vec + metaLen(4) + meta
        int recordSize = 8 + 1 + 4 + idBytes.length + 4 + vecLen + 4 + metaLen;
        ByteBuffer buffer = ByteBuffer.allocate(recordSize);
        
        buffer.putLong(record.seqNo());
        buffer.put(record.opType().getCode());
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        
        buffer.putInt(record.vector() != null ? record.vector().length : 0);
        if (record.vector() != null) {
            for (float v : record.vector()) buffer.putFloat(v);
        }
        
        buffer.putInt(metaLen);
        if (record.metadata() != null) buffer.put(record.metadata());
        
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        
        currentOffset += recordSize;
        recordsWritten++;
    }

    public void finish() throws IOException {
        long indexOffset = currentOffset;
        
        // 1. Write Sparse Index
        ByteBuffer indexBuffer = ByteBuffer.allocate(4);
        indexBuffer.putInt(sparseIndex.size());
        indexBuffer.flip();
        channel.write(indexBuffer);
        
        for (IndexEntry entry : sparseIndex) {
            byte[] idBytes = entry.key().getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(4 + idBytes.length + 8);
            b.putInt(idBytes.length);
            b.put(idBytes);
            b.putLong(entry.offset());
            b.flip();
            channel.write(b);
        }
        
        long bloomOffset = channel.position();
        
        // 2. Write Bloom Filter
        byte[] bloomBytes = bloomFilter.toByteArray();
        ByteBuffer bloomBuffer = ByteBuffer.allocate(4 + 4 + 4 + bloomBytes.length);
        bloomBuffer.putInt(bloomFilter.getSize());
        bloomBuffer.putInt(bloomFilter.getNumHashFunctions());
        bloomBuffer.putInt(bloomBytes.length);
        bloomBuffer.put(bloomBytes);
        bloomBuffer.flip();
        channel.write(bloomBuffer);
        
        // 3. Write Footer (20 bytes total)
        ByteBuffer footer = ByteBuffer.allocate(20);
        footer.putLong(indexOffset);
        footer.putLong(bloomOffset);
        footer.putInt(MAGIC);
        footer.flip();
        channel.write(footer);
        
        channel.force(true); // Fsync to disk
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
