package com.vectorforge.storageengine.sstable;

import com.vectorforge.storageengine.wal.OperationType;
import com.vectorforge.storageengine.wal.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SSTableTest {

    @Test
    void testWriteAndReadSSTable(@TempDir Path tempDir) throws Exception {
        Path sstableFile = tempDir.resolve("000001.sst");
        
        WalRecord r1 = new WalRecord(1, OperationType.UPSERT, "apple", new float[]{1f, 2f}, null);
        WalRecord r2 = new WalRecord(2, OperationType.UPSERT, "banana", new float[]{3f, 4f}, null);
        WalRecord r3 = new WalRecord(3, OperationType.UPSERT, "cherry", new float[]{5f, 6f}, null);
        
        // 1. Write the SSTable
        try (SSTableWriter writer = new SSTableWriter(sstableFile, 3)) {
            writer.write(r1);
            writer.write(r2);
            writer.write(r3);
            writer.finish();
        }
        
        // 2. Read back from the SSTable
        try (SSTable sstable = new SSTable(sstableFile)) {
            assertEquals(r2, sstable.get("banana"));
            assertEquals(r1, sstable.get("apple"));
            assertEquals(r3, sstable.get("cherry"));
            
            // Non-existent element
            assertNull(sstable.get("zebra"));
        }
    }
}
