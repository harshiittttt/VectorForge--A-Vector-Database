package com.vectorforge.storageengine.compaction;

import com.vectorforge.storageengine.sstable.SSTableScanner;
import com.vectorforge.storageengine.sstable.SSTableWriter;
import com.vectorforge.storageengine.wal.WalRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Orchestrates background merging of SSTables.
 */
public class CompactionManager {

    private static class MergeNode {
        final SSTableScanner scanner;
        WalRecord currentRecord;

        MergeNode(SSTableScanner scanner, WalRecord currentRecord) {
            this.scanner = scanner;
            this.currentRecord = currentRecord;
        }
    }

    /**
     * Performs a K-Way merge on a list of overlapping SSTables and writes the output to a new SSTable.
     * Drops older versions of the same vector by keeping the highest sequence number.
     */
    public static void compact(List<Path> inputSSTables, Path outputSSTable, int expectedRecords) throws IOException {
        // Min-heap ordered by vectorId (ascending), then by seqNo (descending)
        PriorityQueue<MergeNode> minHeap = new PriorityQueue<>(
                Comparator.<MergeNode, String>comparing(node -> node.currentRecord.vectorId())
                          .thenComparing(node -> node.currentRecord.seqNo(), Comparator.reverseOrder())
        );

        List<SSTableScanner> scanners = new ArrayList<>();

        try {
            // 1. Initialize scanners and min-heap
            for (Path path : inputSSTables) {
                SSTableScanner scanner = new SSTableScanner(path);
                scanners.add(scanner);
                if (scanner.hasNext()) {
                    minHeap.add(new MergeNode(scanner, scanner.next()));
                }
            }

            // 2. K-way merge using the PriorityQueue
            try (SSTableWriter writer = new SSTableWriter(outputSSTable, expectedRecords)) {
                String lastSeenId = null;

                while (!minHeap.isEmpty()) {
                    MergeNode node = minHeap.poll();
                    WalRecord record = node.currentRecord;

                    // Because of our comparator, the first time we see an ID, it has the highest seqNo
                    if (lastSeenId == null || !lastSeenId.equals(record.vectorId())) {
                        writer.write(record); // Accept the newest mutation
                        lastSeenId = record.vectorId();
                    }
                    // Else: drop the record (it's an older overwritten version)

                    // Pull the next record from the scanner that just provided the top element
                    if (node.scanner.hasNext()) {
                        node.currentRecord = node.scanner.next();
                        minHeap.add(node);
                    }
                }
                writer.finish();
            }
        } finally {
            for (SSTableScanner scanner : scanners) {
                scanner.close(); // Prevent file descriptor leaks
            }
        }
    }
}
