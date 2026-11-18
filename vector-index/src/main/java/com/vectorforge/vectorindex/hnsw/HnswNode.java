package com.vectorforge.vectorindex.hnsw;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single vector in the Hierarchical Navigable Small World (HNSW) graph.
 * It tracks its maximum layer and its connections (edges) to other nodes at each layer.
 */
public class HnswNode {
    public final String id;
    public final float[] vector;
    public final int maxLayer;
    
    // Index corresponds to the layer level. 
    // Example: neighborsByLayer.get(0) returns all neighbors at Layer 0 (the dense base layer).
    public final List<List<HnswNode>> neighborsByLayer;

    public HnswNode(String id, float[] vector, int maxLayer) {
        this.id = id;
        this.vector = vector;
        this.maxLayer = maxLayer;
        
        // Initialize an empty neighbor list for each layer from 0 up to maxLayer
        this.neighborsByLayer = new ArrayList<>(maxLayer + 1);
        for (int i = 0; i <= maxLayer; i++) {
            this.neighborsByLayer.add(new ArrayList<>());
        }
    }
}
