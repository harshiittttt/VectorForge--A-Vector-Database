package com.vectorforge.storageengine.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class WalReader implements AutoCloseable {
    private final FileChannel channel;
    private final CRC32 crc32 = new CRC32();

    public WalReader(Path filePath) throws IOException {
        this.channel = FileChannel.open(filePath, StandardOpenOption.READ);
    }

    public List<WalRecord> readAll() throws IOException {
        List<WalRecord> records = new ArrayList<>();
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);

        while (true) {
            lengthBuffer.clear();
            int bytesRead = channel.read(lengthBuffer);
            if (bytesRead == -1) break; // EOF
            if (bytesRead < 4) {
                break; // Torn write at length field
            }

            lengthBuffer.flip();
            int payloadSize = lengthBuffer.getInt();
            
            // Limit payload size to avoid OOM on corrupted length
            if (payloadSize <= 0 || payloadSize > 1024 * 1024 * 100) { 
                break; 
            }

            ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadSize);
            bytesRead = 0;
            while (bytesRead < payloadSize) {
                int read = channel.read(payloadBuffer);
                if (read == -1) break;
                bytesRead += read;
            }
            
            if (bytesRead < payloadSize) {
                break; // Torn write mid-payload
            }

            payloadBuffer.flip();
            long expectedCrc = payloadBuffer.getLong();
            
            crc32.reset();
            crc32.update(payloadBuffer.array(), 8, payloadSize - 8); 
            if (crc32.getValue() != expectedCrc) {
                break; // CRC mismatch - corruption or torn write detected
            }

            long seqNo = payloadBuffer.getLong();
            OperationType opType = OperationType.fromCode(payloadBuffer.get());
            
            int idLen = payloadBuffer.getInt();
            byte[] idBytes = new byte[idLen];
            payloadBuffer.get(idBytes);
            String vectorId = new String(idBytes, StandardCharsets.UTF_8);

            int vecLen = payloadBuffer.getInt();
            float[] vector = null;
            if (vecLen > 0) {
                vector = new float[vecLen];
                for (int i = 0; i < vecLen; i++) {
                    vector[i] = payloadBuffer.getFloat();
                }
            }

            int metaLen = payloadBuffer.getInt();
            byte[] metadata = null;
            if (metaLen > 0) {
                metadata = new byte[metaLen];
                payloadBuffer.get(metadata);
            }

            records.add(new WalRecord(seqNo, opType, vectorId, vector, metadata));
        }
        return records;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
