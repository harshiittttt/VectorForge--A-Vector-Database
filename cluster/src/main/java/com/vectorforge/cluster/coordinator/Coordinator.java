package com.vectorforge.cluster.coordinator;

import com.vectorforge.cluster.hashring.HashRing;
import com.vectorforge.shardnode.ShardNode;
import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;
import com.vectorforge.vectorindex.distance.L2Distance;
import com.vectorforge.vectorindex.hnsw.HnswIndex;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * The Coordinator manages the cluster state. It hashes incoming writes to the correct primary and replicas,
 * and executes parallel Scatter-Gather queries across all shards.
 */
public class Coordinator {
    private final HashRing hashRing;
    private final Map<String, ShardBackend> clusterNodes = new ConcurrentHashMap<>();
    private final Map<String, String> nodeErrors = new ConcurrentHashMap<>();
    private final int replicationFactor;
    private final BruteForceIndex bruteForceIndex = new BruteForceIndex(new L2Distance());
    
    // Thread pool used to fire queries to all shards in parallel
    private final ForkJoinPool queryPool = new ForkJoinPool(); 

    public Coordinator(int replicationFactor) {
        this.hashRing = new HashRing(150); // 150 vnodes per physical node is an industry standard
        this.replicationFactor = replicationFactor;
    }

    public void registerNode(String nodeId, ShardNode node) {
        registerBackend(nodeId, new LocalShardBackend(node));
        for (Map.Entry<String, float[]> vector : node.snapshotVectors().entrySet()) {
            bruteForceIndex.upsert(vector.getKey(), vector.getValue());
        }
    }

    public void registerBackend(String nodeId, ShardBackend backend) {
        clusterNodes.put(nodeId, backend);
        hashRing.addNode(nodeId);
    }

    public void removeNode(String nodeId) {
        clusterNodes.remove(nodeId);
        hashRing.removeNode(nodeId);
    }

    /**
     * Upsert: Hashes the vector ID, finds the owning nodes, and replicates the write.
     */
    public void upsert(String vectorId, float[] vector, byte[] metadata) throws IOException {
        List<String> targetNodes = hashRing.getReplicas(vectorId, replicationFactor);
        
        if (targetNodes.isEmpty()) {
            throw new IllegalStateException("No nodes available in the cluster.");
        }

        int successfulWrites = 0;
        for (String nodeId : targetNodes) {
            ShardBackend node = clusterNodes.get(nodeId);
            if (node != null) {
                try {
                    node.upsert(vectorId, vector, metadata);
                    successfulWrites++;
                    nodeErrors.remove(nodeId);
                } catch (IOException | RuntimeException error) {
                    nodeErrors.put(nodeId, error.getMessage());
                }
            }
        }
        int quorum = (targetNodes.size() / 2) + 1;
        if (successfulWrites < quorum) {
            throw new IOException("Write quorum failed: " + successfulWrites + "/" + quorum + " replicas acknowledged");
        }
        bruteForceIndex.upsert(vectorId, vector);
    }

    public void delete(String vectorId) throws IOException {
        List<String> targetNodes = hashRing.getReplicas(vectorId, replicationFactor);
        int successfulDeletes = 0;
        for (String nodeId : targetNodes) {
            ShardBackend node = clusterNodes.get(nodeId);
            if (node != null) {
                try {
                    node.delete(vectorId);
                    successfulDeletes++;
                    nodeErrors.remove(nodeId);
                } catch (IOException | RuntimeException error) {
                    nodeErrors.put(nodeId, error.getMessage());
                }
            }
        }
        int quorum = (targetNodes.size() / 2) + 1;
        if (successfulDeletes < quorum) {
            throw new IOException("Delete quorum failed: " + successfulDeletes + "/" + quorum + " replicas acknowledged");
        }
        bruteForceIndex.delete(vectorId);
    }

    public Map<String, Integer> clearAll() throws IOException {
        Map<String, Integer> deletedByNode = new TreeMap<>();
        List<String> failedNodes = new ArrayList<>();
        for (Map.Entry<String, ShardBackend> entry : clusterNodes.entrySet()) {
            try {
                deletedByNode.put(entry.getKey(), entry.getValue().clearAll());
                nodeErrors.remove(entry.getKey());
            } catch (IOException | RuntimeException error) {
                nodeErrors.put(entry.getKey(), error.getMessage());
                failedNodes.add(entry.getKey());
            }
        }
        if (!failedNodes.isEmpty()) {
            throw new IOException("Could not clear every shard; failed nodes: " + String.join(", ", failedNodes));
        }
        bruteForceIndex.clear();
        return deletedByNode;
    }

    /**
     * Query: Performs a Scatter-Gather search. The query goes to EVERY physical shard,
     * and their individual Top-K results are merged into a global Top-K.
     */
    public List<HnswIndex.SearchResult> query(float[] queryVector, int topK) {
        return query(queryVector, topK, 100);
    }

    public List<HnswIndex.SearchResult> query(float[] queryVector, int topK, int efSearch) {
        Set<String> allPhysicalNodes = new HashSet<>(clusterNodes.keySet());
        
        // 1. SCATTER: Fire the query to every shard in parallel
        List<CompletableFuture<List<HnswIndex.SearchResult>>> futures = new ArrayList<>();
        
        for (String nodeId : allPhysicalNodes) {
            ShardBackend node = clusterNodes.get(nodeId);
            CompletableFuture<List<HnswIndex.SearchResult>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    List<HnswIndex.SearchResult> results = node.query(queryVector, topK, efSearch);
                    nodeErrors.remove(nodeId);
                    return results;
                } catch (Exception e) {
                    nodeErrors.put(nodeId, e.getMessage());
                    // Partial failure handling: return empty for this shard if it crashes, 
                    // ensuring cluster availability over perfect completeness.
                    return Collections.emptyList();
                }
            }, queryPool);
            futures.add(future);
        }

        // Wait for all shard responses
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 2. GATHER: Merge results using a Bounded Min-Heap
        Map<String, HnswIndex.SearchResult> bestById = new HashMap<>();
        for (CompletableFuture<List<HnswIndex.SearchResult>> future : futures) {
            List<HnswIndex.SearchResult> shardResults = future.join();
            for (HnswIndex.SearchResult res : shardResults) {
                HnswIndex.SearchResult existing = bestById.get(res.id());
                if (existing == null || res.distance() < existing.distance()) {
                    bestById.put(res.id(), res);
                }
            }
        }

        return bestById.values().stream()
                .sorted(Comparator.comparingDouble(HnswIndex.SearchResult::distance))
                .limit(topK)
                .toList();
    }

    public List<BruteForceIndex.SearchResult> bruteForceQuery(float[] queryVector, int topK) {
        Map<String, BruteForceIndex.SearchResult> bestById = new HashMap<>();
        for (Map.Entry<String, ShardBackend> entry : clusterNodes.entrySet()) {
            try {
                for (BruteForceIndex.SearchResult result : entry.getValue().bruteForceQuery(queryVector, topK)) {
                    BruteForceIndex.SearchResult existing = bestById.get(result.id());
                    if (existing == null || result.distance() < existing.distance()) {
                        bestById.put(result.id(), result);
                    }
                }
            } catch (IOException error) {
                nodeErrors.put(entry.getKey(), error.getMessage());
            }
        }
        return bestById.values().stream()
                .sorted(Comparator.comparingDouble(BruteForceIndex.SearchResult::distance))
                .limit(topK)
                .toList();
    }

    public int nodeCount() {
        return clusterNodes.size();
    }

    public Map<String, Map<String, Object>> nodeStats() {
        Map<String, Map<String, Object>> stats = new TreeMap<>();
        for (Map.Entry<String, ShardBackend> entry : clusterNodes.entrySet()) {
            try {
            Map<String, Object> node = new LinkedHashMap<>(entry.getValue().stats());
                node.put("status", nodeErrors.containsKey(entry.getKey()) ? "degraded" : "healthy");
                if (nodeErrors.containsKey(entry.getKey())) node.put("error", nodeErrors.get(entry.getKey()));
                stats.put(entry.getKey(), node);
            } catch (IOException e) {
                stats.put(entry.getKey(), Map.of("error", e.getMessage()));
            }
        }
        return stats;
    }
}
