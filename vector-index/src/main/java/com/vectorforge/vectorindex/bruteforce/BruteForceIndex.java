package com.vectorforge.vectorindex.bruteforce;

import com.vectorforge.vectorindex.distance.DistanceFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A flat index that performs exact K-Nearest Neighbor search by scanning all vectors.
 * Used as the baseline / correctness oracle for benchmarking HNSW.
 */
public class BruteForceIndex {
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
    private final DistanceFunction distanceFunction;

    public BruteForceIndex(DistanceFunction distanceFunction) {
        this.distanceFunction = distanceFunction;
    }

    public void upsert(String id, float[] vector) {
        vectors.put(id, vector);
    }
    
    public void delete(String id) {
        vectors.remove(id);
    }

    public void clear() {
        vectors.clear();
    }

    public record SearchResult(String id, float distance) {}

    /**
     * Finds the Top-K nearest neighbors to the query vector.
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        // Max-Heap to keep track of the top K closest vectors.
        // The root of the heap is the furthest vector among our current top K.
        PriorityQueue<SearchResult> maxHeap = new PriorityQueue<>(
                Comparator.<SearchResult>comparingDouble(SearchResult::distance).reversed()
        );

        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            float dist = distanceFunction.compute(queryVector, entry.getValue());
            
            if (maxHeap.size() < topK) {
                maxHeap.add(new SearchResult(entry.getKey(), dist));
            } else if (dist < maxHeap.peek().distance()) {
                maxHeap.poll(); // Evict the furthest one
                maxHeap.add(new SearchResult(entry.getKey(), dist)); // Insert the closer one
            }
        }

        // Output results sorted from closest (lowest distance) to furthest
        List<SearchResult> results = new ArrayList<>(maxHeap.size());
        while (!maxHeap.isEmpty()) {
            results.add(0, maxHeap.poll()); 
        }
        return results;
    }
    
    public int size() {
        return vectors.size();
    }
}
