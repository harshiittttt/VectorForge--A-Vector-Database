package com.vectorforge.storageengine.sstable;

import com.vectorforge.storageengine.wal.OperationType;
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
 * Loads an SSTable, keeps the Index and Bloom Filter in memory, 
 * and serves point lookups by reading from disk using the sparse index.
 */
public class SSTable implements AutoCloseable {
    private final FileChannel channel;
    private final BloomFilter bloomFilter;
    private final List<IndexEntry> sparseIndex = new ArrayList<>();
    private final long indexOffset;
    
    private record IndexEntry(String key, long offset) {}

    public SSTable(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        
        long fileSize = channel.size();
        if (fileSize < 20) throw new IOException("File too small to be an SSTable");
        
        // 1. Read Footer
        ByteBuffer footer = ByteBuffer.allocate(20);
        channel.read(footer, fileSize - 20);
        footer.flip();
        
        this.indexOffset = footer.getLong();
        long bloomOffset = footer.getLong();
        int magic = footer.getInt();
        
        if (magic != SSTableWriter.MAGIC) {
            throw new IOException("Invalid SSTable magic bytes. File corrupted.");
        }
        
        // 2. Read Sparse Index into memory
        ByteBuffer intBuf = ByteBuffer.allocate(4);
        channel.read(intBuf, indexOffset);
        intBuf.flip();
        int indexSize = intBuf.getInt();
        
        long currentPos = indexOffset + 4;
        for (int i = 0; i < indexSize; i++) {
            intBuf.clear();
            channel.read(intBuf, currentPos);
            intBuf.flip();
            int idLen = intBuf.getInt();
            currentPos += 4;
            
            ByteBuffer idBuf = ByteBuffer.allocate(idLen);
            channel.read(idBuf, currentPos);
            idBuf.flip();
            String key = new String(idBuf.array(), StandardCharsets.UTF_8);
            currentPos += idLen;
            
            ByteBuffer offsetBuf = ByteBuffer.allocate(8);
            channel.read(offsetBuf, currentPos);
            offsetBuf.flip();
            long offset = offsetBuf.getLong();
            currentPos += 8;
            
            sparseIndex.add(new IndexEntry(key, offset));
        }
        
        // 3. Read Bloom Filter into memory
        ByteBuffer bloomHeader = ByteBuffer.allocate(12);
        channel.read(bloomHeader, bloomOffset);
        bloomHeader.flip();
        int bitSize = bloomHeader.getInt();
        int numHash = bloomHeader.getInt();
        int byteLength = bloomHeader.getInt();
        
        ByteBuffer bloomData = ByteBuffer.allocate(byteLength);
        channel.read(bloomData, bloomOffset + 12);
        bloomData.flip();
        this.bloomFilter = new BloomFilter(bloomData.array(), bitSize, numHash);
    }
    
    public WalRecord get(String vectorId) throws IOException {
        // Fast path: Negative check using Bloom Filter
        if (!bloomFilter.mightContain(vectorId)) {
            return null;
        }
        
        // Binary search the sparse index
        long searchOffset = 0;
        long nextOffset = indexOffset; // defaults to end of data block
        
        int left = 0;
        int right = sparseIndex.size() - 1;
        while (left <= right) {
            int mid = left + (right - left) / 2;
            IndexEntry midEntry = sparseIndex.get(mid);
            
            int cmp = midEntry.key().compareTo(vectorId);
            if (cmp == 0) {
                searchOffset = midEntry.offset();
                if (mid < sparseIndex.size() - 1) nextOffset = sparseIndex.get(mid + 1).offset();
                break;
            } else if (cmp < 0) {
                searchOffset = midEntry.offset();
                left = mid + 1;
            } else {
                nextOffset = midEntry.offset();
                right = mid - 1;
            }
        }
        
        // Linear scan inside the block to find the exact record
        channel.position(searchOffset);
        while (channel.position() < nextOffset) {
            WalRecord record = readNextRecord();
            if (record == null) break;
            
            int cmp = record.vectorId().compareTo(vectorId);
            if (cmp == 0) {
                return record; // Exact match found!
            } else if (cmp > 0) {
                break; // Since data is sorted, passing it means it doesn't exist
            }
        }
        
        return null;
    }

    private WalRecord readNextRecord() throws IOException {
        if (channel.position() >= indexOffset) return null;
        
        ByteBuffer header = ByteBuffer.allocate(13); // seq(8)+op(1)+idLen(4)
        int read = channel.read(header);
        if (read < 13) return null;
        header.flip();
        
        long seqNo = header.getLong();
        OperationType opType = OperationType.fromCode(header.get());
        int idLen = header.getInt();
        
        ByteBuffer idBuf = ByteBuffer.allocate(idLen);
        channel.read(idBuf);
        idBuf.flip();
        String id = new String(idBuf.array(), StandardCharsets.UTF_8);
        
        ByteBuffer vecLenBuf = ByteBuffer.allocate(4);
        channel.read(vecLenBuf);
        vecLenBuf.flip();
        int vecLen = vecLenBuf.getInt();
        
        float[] vector = null;
        if (vecLen > 0) {
            vector = new float[vecLen];
            ByteBuffer vecBuf = ByteBuffer.allocate(vecLen * 4);
            channel.read(vecBuf);
            vecBuf.flip();
            for (int i = 0; i < vecLen; i++) vector[i] = vecBuf.getFloat();
        }
        
        ByteBuffer metaLenBuf = ByteBuffer.allocate(4);
        channel.read(metaLenBuf);
        metaLenBuf.flip();
        int metaLen = metaLenBuf.getInt();
        
        byte[] metadata = null;
        if (metaLen > 0) {
            metadata = new byte[metaLen];
            ByteBuffer metaBuf = ByteBuffer.allocate(metaLen);
            channel.read(metaBuf);
            metaBuf.flip();
            metaBuf.get(metadata);
        }
        
        return new WalRecord(seqNo, opType, id, vector, metadata);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
