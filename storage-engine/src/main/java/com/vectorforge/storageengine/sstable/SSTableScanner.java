package com.vectorforge.storageengine.sstable;

import com.vectorforge.storageengine.wal.OperationType;
import com.vectorforge.storageengine.wal.WalRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Iterates over the records of an SSTable sequentially without loading the entire file into memory.
 * Crucial for K-way merge compaction.
 */
public class SSTableScanner implements AutoCloseable, Iterator<WalRecord> {
    private final FileChannel channel;
    private final long indexOffset;
    private WalRecord nextRecord;

    public SSTableScanner(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        
        long fileSize = channel.size();
        ByteBuffer footer = ByteBuffer.allocate(20);
        channel.read(footer, fileSize - 20);
        footer.flip();
        
        this.indexOffset = footer.getLong();
        channel.position(0); // Start reading from the very beginning (Data block)
        advance();
    }

    private void advance() {
        try {
            if (channel.position() >= indexOffset) {
                nextRecord = null;
                return;
            }
            
            ByteBuffer header = ByteBuffer.allocate(13); // seq(8)+op(1)+idLen(4)
            int read = channel.read(header);
            if (read < 13) {
                nextRecord = null;
                return;
            }
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
            
            nextRecord = new WalRecord(seqNo, opType, id, vector, metadata);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan SSTable sequentially", e);
        }
    }

    @Override
    public boolean hasNext() {
        return nextRecord != null;
    }

    @Override
    public WalRecord next() {
        WalRecord rec = nextRecord;
        advance();
        return rec;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
