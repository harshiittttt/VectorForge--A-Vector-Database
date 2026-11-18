package com.vectorforge.storageengine.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalTest {

    @Test
    void testWriteAndRead(@TempDir Path tempDir) throws Exception {
        Path walFile = tempDir.resolve("00001.wal");
        
        WalRecord record1 = new WalRecord(1, OperationType.UPSERT, "vec1", new float[]{1.0f, 2.5f, -0.3f}, new byte[]{0x01, 0x02});
        WalRecord record2 = new WalRecord(2, OperationType.DELETE, "vec2", null, null);

        try (WalWriter writer = new WalWriter(walFile)) {
            writer.append(record1);
            writer.append(record2);
            writer.flush();
        }

        try (WalReader reader = new WalReader(walFile)) {
            List<WalRecord> records = reader.readAll();
            assertEquals(2, records.size());
            assertEquals(record1, records.get(0));
            assertEquals(record2, records.get(1));
        }
    }
    
    @Test
    void testTornWriteDetection(@TempDir Path tempDir) throws Exception {
        Path walFile = tempDir.resolve("00002.wal");
        
        WalRecord record = new WalRecord(1, OperationType.UPSERT, "vec1", new float[]{1.0f}, null);

        try (WalWriter writer = new WalWriter(walFile)) {
            writer.append(record);
            writer.flush();
        }
        
        // Truncate the file to simulate a torn write
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(walFile, java.nio.file.StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 2); 
        }
        
        try (WalReader reader = new WalReader(walFile)) {
            List<WalRecord> records = reader.readAll();
            // Should be empty because the only record was torn and CRC failed
            assertTrue(records.isEmpty());
        }
    }
}
