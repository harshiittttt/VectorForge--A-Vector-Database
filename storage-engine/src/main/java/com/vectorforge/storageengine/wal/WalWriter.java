package com.vectorforge.storageengine.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Handles appending WalRecords to a file channel.
 * Format: [length:4][crc32:8][seq_no:8][op_type:1][id_len:4][id_bytes][vec_len:4][vec_bytes][meta_len:4][meta_bytes]
 * Note: length covers everything after the length field itself.
 */
public class WalWriter implements AutoCloseable {
    private final FileChannel channel;
    private final CRC32 crc32 = new CRC32();
    
    public WalWriter(Path filePath) throws IOException {
        this.channel = FileChannel.open(filePath, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.WRITE, 
            StandardOpenOption.APPEND);
    }

    public void append(WalRecord record) throws IOException {
        byte[] idBytes = record.vectorId().getBytes(StandardCharsets.UTF_8);
        int vecBytesLen = record.vector() != null ? record.vector().length * 4 : 0;
        int metaBytesLen = record.metadata() != null ? record.metadata().length : 0;

        // payloadSize = crc32(8) + seq_no(8) + op_type(1) + id_len(4) + idBytes + vec_len(4) + vecBytes + meta_len(4) + metaBytes
        int payloadSize = 8 + 8 + 1 + 4 + idBytes.length + 4 + vecBytesLen + 4 + metaBytesLen;
        int totalSize = 4 + payloadSize;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(payloadSize);
        
        int crcPos = buffer.position();
        buffer.putLong(0L); // Placeholder for CRC32
        
        buffer.putLong(record.seqNo());
        buffer.put(record.opType().getCode());
        
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        
        buffer.putInt(record.vector() != null ? record.vector().length : 0);
        if (record.vector() != null) {
            for (float v : record.vector()) {
                buffer.putFloat(v);
            }
        }
        
        buffer.putInt(metaBytesLen);
        if (record.metadata() != null) {
            buffer.put(record.metadata());
        }

        // Compute CRC
        crc32.reset();
        crc32.update(buffer.array(), crcPos + 8, payloadSize - 8);
        buffer.putLong(crcPos, crc32.getValue());

        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public void flush() throws IOException {
        channel.force(false); // fsync to disk
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
