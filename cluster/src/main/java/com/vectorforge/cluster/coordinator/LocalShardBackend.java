package com.vectorforge.cluster.coordinator;

import com.vectorforge.shardnode.ShardNode;
import com.vectorforge.vectorindex.hnsw.HnswIndex;
import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalShardBackend implements ShardBackend {
    private final ShardNode shardNode;

    public LocalShardBackend(ShardNode shardNode) {
        this.shardNode = shardNode;
    }

    @Override
    public void upsert(String id, float[] vector, byte[] metadata) throws IOException {
        shardNode.upsert(id, vector, metadata);
    }

    @Override
    public void delete(String id) throws IOException {
        shardNode.delete(id);
    }

    @Override
    public int clearAll() throws IOException {
        return shardNode.clearAllVectors();
    }

    @Override
    public List<HnswIndex.SearchResult> query(float[] vector, int topK, int efSearch) {
        return shardNode.query(vector, topK, efSearch);
    }

    @Override
    public List<BruteForceIndex.SearchResult> bruteForceQuery(float[] vector, int topK) {
        return shardNode.bruteForceQuery(vector, topK);
    }

    @Override
    public Map<String, Object> stats() throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("memtable_bytes", shardNode.memtableSizeBytes());
        stats.put("sstable_count", shardNode.sstableCount());
        stats.put("vector_count", shardNode.snapshotVectors().size());
        return stats;
    }
}
