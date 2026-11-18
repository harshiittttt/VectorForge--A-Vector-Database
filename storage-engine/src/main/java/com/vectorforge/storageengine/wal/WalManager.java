package com.vectorforge.storageengine.wal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages write-ahead log files, handling segment rotation and crash recovery replays.
 */
public class WalManager implements AutoCloseable {
    private final Path walDirectory;
    private final Path checkpointPath;
    private final long maxSegmentSizeBytes;
    
    private WalWriter currentWriter;
    private Path currentSegmentPath;
    private long currentSegmentSize;
    private long nextSegmentId;

    public WalManager(Path walDirectory, long maxSegmentSizeBytes) throws IOException {
        this.walDirectory = walDirectory;
        this.checkpointPath = walDirectory.resolve("checkpoint");
        this.maxSegmentSizeBytes = maxSegmentSizeBytes;
        Files.createDirectories(walDirectory);
        
        this.nextSegmentId = determineNextSegmentId();
        rotate();
    }
    
    private long determineNextSegmentId() throws IOException {
        try (Stream<Path> files = Files.list(walDirectory)) {
            return files.filter(p -> p.toString().endsWith(".wal"))
                        .mapToLong(p -> {
                            String name = p.getFileName().toString();
                            return Long.parseLong(name.substring(0, name.length() - 4));
                        })
                        .max()
                        .orElse(0L) + 1;
        }
    }

    public synchronized void append(WalRecord record) throws IOException {
        if (currentSegmentSize >= maxSegmentSizeBytes) {
            rotate();
        }
        
        currentWriter.append(record);
        
        long recordSize = 4 + 8 + 8 + 1 + 4 + record.vectorId().getBytes().length + 4 + 
                (record.vector() != null ? record.vector().length * 4 : 0) + 4 + 
                (record.metadata() != null ? record.metadata().length : 0);
        
        currentSegmentSize += recordSize;
    }

    public synchronized void flush() throws IOException {
        if (currentWriter != null) {
            currentWriter.flush();
        }
    }
    
    private void rotate() throws IOException {
        if (currentWriter != null) {
            currentWriter.close();
        }
        
        String segmentName = String.format("%010d.wal", nextSegmentId++);
        currentSegmentPath = walDirectory.resolve(segmentName);
        currentWriter = new WalWriter(currentSegmentPath);
        currentSegmentSize = 0;
    }

    public List<WalRecord> replayAll() throws IOException {
        return replayAfter(readCheckpoint());
    }

    public List<WalRecord> replayAfter(long sequenceNumber) throws IOException {
        List<WalRecord> allRecords = new ArrayList<>();
        
        try (Stream<Path> files = Files.list(walDirectory)) {
            List<Path> walFiles = files
                    .filter(p -> p.toString().endsWith(".wal"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
                    
            for (Path walFile : walFiles) {
                try (WalReader reader = new WalReader(walFile)) {
                        allRecords.addAll(reader.readAll().stream()
                            .filter(record -> record.seqNo() > sequenceNumber)
                            .toList());
                }
            }
        }
        return allRecords;
    }

    public synchronized long readCheckpoint() throws IOException {
        if (!Files.exists(checkpointPath)) return 0L;
        return Long.parseLong(Files.readString(checkpointPath, StandardCharsets.UTF_8).trim());
    }

    public synchronized void checkpoint(long sequenceNumber) throws IOException {
        flush();
        Files.writeString(checkpointPath, Long.toString(sequenceNumber), StandardCharsets.UTF_8);
        Files.move(checkpointPath, checkpointPath.resolveSibling("checkpoint.tmp"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.move(checkpointPath.resolveSibling("checkpoint.tmp"), checkpointPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        deleteCheckpointedSegments(sequenceNumber);
    }

    private void deleteCheckpointedSegments(long sequenceNumber) throws IOException {
        try (Stream<Path> files = Files.list(walDirectory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".wal")).toList()) {
                if (file.equals(currentSegmentPath)) continue;
                try (WalReader reader = new WalReader(file)) {
                    List<WalRecord> records = reader.readAll();
                    if (records.isEmpty() || records.stream().allMatch(record -> record.seqNo() <= sequenceNumber)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (currentWriter != null) {
            currentWriter.close();
        }
    }
}
