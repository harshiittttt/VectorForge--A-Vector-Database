package com.vectorforge.storageengine;

import com.vectorforge.storageengine.compaction.CompactionManager;
import com.vectorforge.storageengine.sstable.SSTableScanner;
import com.vectorforge.storageengine.sstable.SSTableWriter;
import com.vectorforge.storageengine.wal.OperationType;
import com.vectorforge.storageengine.wal.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactionTest {

    @Test
    void testKWayMergeCompaction(@TempDir Path tempDir) throws Exception {
        Path sst1 = tempDir.resolve("L0_1.sst");
        Path sst2 = tempDir.resolve("L0_2.sst");
        Path sstOut = tempDir.resolve("L1_1.sst");

        // First SSTable (older data)
        try (SSTableWriter w = new SSTableWriter(sst1, 10)) {
            w.write(new WalRecord(1, OperationType.UPSERT, "Apple", new float[]{1f}, null));
            w.write(new WalRecord(2, OperationType.UPSERT, "Banana", new float[]{2f}, null));
            w.finish();
        }

        // Second SSTable (newer data, updates Banana, adds Cherry)
        try (SSTableWriter w = new SSTableWriter(sst2, 10)) {
            w.write(new WalRecord(3, OperationType.UPSERT, "Banana", new float[]{99f}, null)); // UPDATE
            w.write(new WalRecord(4, OperationType.UPSERT, "Cherry", new float[]{3f}, null));
            w.finish();
        }

        // Run Compaction
        List<Path> inputs = List.of(sst1, sst2);
        CompactionManager.compact(inputs, sstOut, 10);

        // Verify Output SSTable
        List<WalRecord> results = new ArrayList<>();
        try (SSTableScanner scanner = new SSTableScanner(sstOut)) {
            while (scanner.hasNext()) {
                results.add(scanner.next());
            }
        }

        assertEquals(3, results.size(), "Should drop the old duplicate Banana");
        
        assertEquals("Apple", results.get(0).vectorId());
        assertEquals(1, results.get(0).seqNo());
        
        assertEquals("Banana", results.get(1).vectorId());
        assertEquals(3, results.get(1).seqNo()); // Must be newest seqNo
        assertEquals(99f, results.get(1).vector()[0]); // Must be updated vector
        
        assertEquals("Cherry", results.get(2).vectorId());
        assertEquals(4, results.get(2).seqNo());
    }
}
