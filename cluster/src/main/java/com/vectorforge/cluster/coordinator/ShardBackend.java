package com.vectorforge.cluster.coordinator;

import com.vectorforge.vectorindex.hnsw.HnswIndex;
import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ShardBackend {
    void upsert(String id, float[] vector, byte[] metadata) throws IOException;

    void delete(String id) throws IOException;

    int clearAll() throws IOException;

    List<HnswIndex.SearchResult> query(float[] vector, int topK, int efSearch) throws IOException;

    List<BruteForceIndex.SearchResult> bruteForceQuery(float[] vector, int topK) throws IOException;

    Map<String, Object> stats() throws IOException;
}
