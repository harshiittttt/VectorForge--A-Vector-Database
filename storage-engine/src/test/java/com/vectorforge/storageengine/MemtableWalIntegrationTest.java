package com.vectorforge.storageengine;

import com.vectorforge.storageengine.memtable.Memtable;
import com.vectorforge.storageengine.wal.OperationType;
import com.vectorforge.storageengine.wal.WalManager;
import com.vectorforge.storageengine.wal.WalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemtableWalIntegrationTest {

    @Test
    void testWritePathAndRecovery(@TempDir Path tempDir) throws Exception {
        Path walDir = tempDir.resolve("wals");
        
        // 1. Setup initial system
        WalManager walManager = new WalManager(walDir, 1024); // 1KB segments for quick rotation
        Memtable memtable = new Memtable(1024 * 1024); // 1MB memtable max size
        
        // 2. Write vectors
        WalRecord r1 = new WalRecord(1, OperationType.UPSERT, "vec1", new float[]{1.1f, 2.2f}, null);
        WalRecord r2 = new WalRecord(2, OperationType.UPSERT, "vec2", new float[]{3.3f, 4.4f}, null);
        
        // Write path: append to WAL, then put in Memtable
        walManager.append(r1);
        memtable.put(r1);
        
        walManager.append(r2);
        memtable.put(r2);
        
        walManager.flush(); // Ensure fsync
        
        // 3. Verify memtable has the data
        assertEquals(r1, memtable.get("vec1"));
        assertEquals(r2, memtable.get("vec2"));
        
        // 4. Simulate crash
        walManager.close(); // Hard close
        memtable.clear(); // Lose in-memory data
        
        // 5. Recover on restart
        WalManager recoveredWal = new WalManager(walDir, 1024);
        Memtable recoveredMemtable = new Memtable(1024 * 1024);
        
        List<WalRecord> logs = recoveredWal.replayAll();
        for (WalRecord log : logs) {
            recoveredMemtable.put(log);
        }
        
        // 6. Verify data is fully recovered
        assertEquals(2, recoveredMemtable.getSnapshot().size());
        assertEquals(r1, recoveredMemtable.get("vec1"));
        assertEquals(r2, recoveredMemtable.get("vec2"));
    }
}
