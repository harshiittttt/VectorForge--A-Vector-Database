package com.vectorforge.shardnode;

import com.vectorforge.storageengine.memtable.Memtable;
import com.vectorforge.storageengine.compaction.CompactionManager;
import com.vectorforge.storageengine.sstable.SSTableScanner;
import com.vectorforge.storageengine.sstable.SSTableWriter;
import com.vectorforge.storageengine.wal.OperationType;
import com.vectorforge.storageengine.wal.WalManager;
import com.vectorforge.storageengine.wal.WalRecord;
import com.vectorforge.vectorindex.distance.L2Distance;
import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;
import com.vectorforge.vectorindex.hnsw.HnswIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * ShardNode connects the Storage Engine (Phase 1) with the Vector Index (Phase 2).
 * It acts as the single entry point for a physical shard.
 */
public class ShardNode implements AutoCloseable {
    private final WalManager walManager;
    private final Memtable memtable;
    private final HnswIndex hnswIndex;
    private final BruteForceIndex bruteForceIndex;
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
    private final Path sstableDirectory;
    private final ExecutorService flushExecutor;
    private long nextSstableId;

    public ShardNode(Path dataDir) throws IOException {
        // Initialize storage components (64MB thresholds for flush)
        this.walManager = new WalManager(dataDir.resolve("wal"), 64 * 1024 * 1024);
        this.memtable = new Memtable(64 * 1024 * 1024);
        this.sstableDirectory = dataDir.resolve("sstables");
        Files.createDirectories(sstableDirectory);
        this.nextSstableId = determineNextSstableId();
        this.flushExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "shard-flush-" + dataDir.getFileName());
            thread.setDaemon(true);
            return thread;
        });
        
        // Initialize index components (M=16, efConstruction=200, efSearch=100)
        this.hnswIndex = new HnswIndex(new L2Distance(), 16, 200, 100);
        this.bruteForceIndex = new BruteForceIndex(new L2Distance());
        
        // Recover state from WAL on startup
        recover();
    }

    private void recover() throws IOException {
        System.out.println("Recovering ShardNode state from WAL...");
        int restored = loadSstables();
        List<WalRecord> logs = walManager.replayAll();
        for (WalRecord log : logs) {
            applyRecord(log, true);
        }
        System.out.println("Recovery complete. Restored " + (restored + logs.size()) + " records.");
    }

    private int loadSstables() throws IOException {
        int restored = 0;
        try (Stream<Path> files = Files.list(sstableDirectory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".sst")).sorted().toList()) {
                try (SSTableScanner scanner = new SSTableScanner(path)) {
                    while (scanner.hasNext()) {
                        applyRecord(scanner.next(), false);
                        restored++;
                    }
                }
            }
        }
        return restored;
    }

    private void applyRecord(WalRecord record, boolean addToMemtable) {
        if (record.opType() == OperationType.UPSERT) {
            if (addToMemtable) memtable.put(record);
            vectors.put(record.vectorId(), record.vector());
            hnswIndex.insert(record.vectorId(), record.vector());
            bruteForceIndex.upsert(record.vectorId(), record.vector());
        } else if (record.opType() == OperationType.DELETE) {
            if (addToMemtable) memtable.put(record);
            vectors.remove(record.vectorId());
            hnswIndex.delete(record.vectorId());
            bruteForceIndex.delete(record.vectorId());
        }
    }

    /**
     * Executes the critical write path: WAL -> Memtable -> HNSW
     */
    public synchronized void upsert(String id, float[] vector, byte[] metadata) throws IOException {
        WalRecord record = new WalRecord(System.currentTimeMillis(), OperationType.UPSERT, id, vector, metadata);
        
        // 1. Durably write to WAL and fsync (Crash Tolerance)
        walManager.append(record);
        walManager.flush();
        
        // 2. Buffer in Memtable (Read path & SSTable flush preparation)
        boolean memtableFull = memtable.put(record);
        
        // 3. Index in HNSW (Graph traversal)
        vectors.put(id, vector);
        hnswIndex.insert(id, vector);
        bruteForceIndex.upsert(id, vector);

        if (memtableFull) {
            flushMemtable();
        }
    }

    /**
     * Executes the search path entirely via the fast HNSW index.
     * Synchronized to prevent concurrent efSearch mutations racing with the search.
     */
    public synchronized List<HnswIndex.SearchResult> query(float[] vector, int topK) {
        return hnswIndex.search(vector, topK);
    }

    public synchronized List<HnswIndex.SearchResult> query(float[] vector, int topK, int efSearch) {
        hnswIndex.setEfSearch(efSearch);
        return hnswIndex.search(vector, topK);
    }

    public List<BruteForceIndex.SearchResult> bruteForceQuery(float[] vector, int topK) {
        return bruteForceIndex.search(vector, topK);
    }

    public synchronized void delete(String id) throws IOException {
        WalRecord record = new WalRecord(System.currentTimeMillis(), OperationType.DELETE, id, null, null);
        walManager.append(record);
        walManager.flush();
        memtable.put(record);
        vectors.remove(id);
        hnswIndex.delete(id);
        bruteForceIndex.delete(id);
    }

    public synchronized int clearAllVectors() throws IOException {
        try {
            flushExecutor.submit(() -> {}).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for shard flush", e);
        } catch (ExecutionException e) {
            throw new IOException("Unable to drain shard flush queue", e);
        }

        List<String> ids = new ArrayList<>(vectors.keySet());
        if (ids.isEmpty()) return 0;

        long sequenceNumber = System.currentTimeMillis();
        List<WalRecord> tombstones = ids.stream()
                .map(id -> new WalRecord(sequenceNumber, OperationType.DELETE, id, null, null))
                .toList();
        for (WalRecord tombstone : tombstones) {
            walManager.append(tombstone);
        }
        walManager.flush();

        boolean memtableFull = false;
        for (WalRecord tombstone : tombstones) {
            memtableFull |= memtable.put(tombstone);
            vectors.remove(tombstone.vectorId());
            hnswIndex.delete(tombstone.vectorId());
            bruteForceIndex.delete(tombstone.vectorId());
        }
        if (memtableFull) flushMemtable();
        return ids.size();
    }

    public synchronized long memtableSizeBytes() {
        return memtable.getSizeInBytes();
    }

    public synchronized int sstableCount() throws IOException {
        try (Stream<Path> files = Files.list(sstableDirectory)) {
            return (int) files.filter(path -> path.getFileName().toString().endsWith(".sst")).count();
        }
    }

    public Map<String, float[]> snapshotVectors() {
        return Map.copyOf(vectors);
    }

    private long determineNextSstableId() throws IOException {
        try (Stream<Path> files = Files.list(sstableDirectory)) {
            return files
                        .filter(path -> path.getFileName().toString().startsWith("sstable-")
                            && path.getFileName().toString().endsWith(".sst"))
                    .mapToLong(path -> {
                        String name = path.getFileName().toString();
                        return Long.parseLong(name.substring("sstable-".length(), name.length() - 4));
                    })
                    .max()
                    .orElse(0L) + 1;
        }
    }

    private synchronized void flushMemtable() throws IOException {
        List<WalRecord> records = List.copyOf(memtable.getSnapshot());
        if (records.isEmpty()) return;

        Path output = sstableDirectory.resolve(String.format("sstable-%010d.sst", nextSstableId++));
        memtable.clear();
        flushExecutor.submit(() -> writeSstableAndMaybeCompact(records, output));
    }

    private void writeSstableAndMaybeCompact(List<WalRecord> records, Path output) {
        try {
            writeSstable(records, output);
            compactIfNeeded();
            walManager.checkpoint(records.stream().mapToLong(WalRecord::seqNo).max().orElse(0L));
            System.out.println("Flushed " + records.size() + " records to " + output);
        } catch (IOException e) {
            System.err.println("Shard flush failed for " + output + ": " + e.getMessage());
        }
    }

    private void writeSstable(List<WalRecord> records, Path output) throws IOException {
        try (SSTableWriter writer = new SSTableWriter(output, records.size())) {
            for (WalRecord record : records) {
                writer.write(record);
            }
            writer.finish();
        }
    }

    private void compactIfNeeded() throws IOException {
        List<Path> inputs;
        try (Stream<Path> files = Files.list(sstableDirectory)) {
            inputs = files
                    .filter(path -> path.getFileName().toString().startsWith("sstable-")
                            && path.getFileName().toString().endsWith(".sst"))
                    .sorted()
                    .toList();
        }
        if (inputs.size() < 4) return;

        Path output = sstableDirectory.resolve(String.format("compacted-%010d.sst", nextSstableId++));
        CompactionManager.compact(inputs, output, 100_000);
        for (Path input : inputs) {
            Files.deleteIfExists(input);
        }
        System.out.println("Compacted " + inputs.size() + " SSTables into " + output);
    }

    @Override
    public void close() throws Exception {
        flushMemtable();
        flushExecutor.shutdown();
        flushExecutor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        walManager.close();
    }
}
