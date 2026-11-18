package com.vectorforge.vectorindex.hnsw;

import com.vectorforge.vectorindex.distance.DistanceFunction;

import java.util.*;

/**
 * Implements the Hierarchical Navigable Small World (HNSW) approximate nearest neighbor index.
 */
public class HnswIndex {
    private final int M; // Max edges per node
    private final int M_max0; // Max edges for layer 0 (dense layer)
    private final int efConstruction; // Beam width during graph construction
    private int efSearch; // Beam width during query
    private final double levelMult;
    
    private HnswNode entryPoint;
    private int maxLayer;
    
    private final DistanceFunction distanceFunction;
    private final Map<String, HnswNode> nodes = new HashMap<>(); 
    private final Set<String> deletedIds = new HashSet<>();
    private final Random random = new Random();

    public HnswIndex(DistanceFunction distanceFunction, int M, int efConstruction, int efSearch) {
        this.distanceFunction = distanceFunction;
        this.M = M;
        this.M_max0 = M * 2;
        this.efConstruction = efConstruction;
        this.efSearch = efSearch;
        this.levelMult = 1.0 / Math.log(M);
        this.maxLayer = -1;
    }

    public record SearchResult(String id, float distance) {}

    public void setEfSearch(int efSearch) {
        if (efSearch < 1) throw new IllegalArgumentException("efSearch must be positive");
        this.efSearch = efSearch;
    }

    // Computes the insertion layer based on an exponential decay probability
    private int assignLevel() {
        double r = random.nextDouble();
        if (r < 1e-9) r = 1e-9;
        return (int) (-Math.log(r) * levelMult);
    }

    public void insert(String id, float[] vector) {
        deletedIds.remove(id);
        int level = assignLevel();
        HnswNode newNode = new HnswNode(id, vector, level);
        nodes.put(id, newNode);

        if (entryPoint == null) {
            entryPoint = newNode;
            maxLayer = level;
            return;
        }

        HnswNode currObj = entryPoint;
        float currDist = distanceFunction.compute(vector, currObj.vector);

        // 1. Phase 1: Greedy search from top layer down to the insertion layer
        for (int lc = maxLayer; lc > level; lc--) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (HnswNode neighbor : currObj.neighborsByLayer.get(lc)) {
                    float dist = distanceFunction.compute(vector, neighbor.vector);
                    if (dist < currDist) {
                        currDist = dist;
                        currObj = neighbor;
                        changed = true;
                    }
                }
            }
        }

        // 2. Phase 2: Beam search and establish connections from insertion layer down to 0
        for (int lc = Math.min(maxLayer, level); lc >= 0; lc--) {
            PriorityQueue<NodeDist> candidates = searchLayer(vector, currObj, efConstruction, lc);
            
            int mMax = (lc == 0) ? M_max0 : M;
            List<HnswNode> neighbors = selectNeighbors(candidates, mMax);
            
            // Wire up bidirectional edges
            for (HnswNode neighbor : neighbors) {
                newNode.neighborsByLayer.get(lc).add(neighbor);
                List<HnswNode> neighborConnections = neighbor.neighborsByLayer.get(lc);
                neighborConnections.add(newNode);
                
                // Prune neighbor's connections if they exceed capacity
                if (neighborConnections.size() > mMax) {
                    pruneConnections(neighbor, lc, mMax);
                }
            }
            
            // Set up entry point for the next layer down
            if (!candidates.isEmpty()) {
                currObj = candidates.peek().node;
            }
        }

        // If this new node reaches higher than the current entry point, it becomes the new entry point
        if (level > maxLayer) {
            maxLayer = level;
            entryPoint = newNode;
        }
    }

    public void delete(String id) {
        deletedIds.add(id);
    }

    /**
     * Finds the Top-K nearest neighbors using HNSW approximate search.
     */
    public List<SearchResult> search(float[] query, int k) {
        if (entryPoint == null) return Collections.emptyList();

        HnswNode currObj = entryPoint;
        float currDist = distanceFunction.compute(query, currObj.vector);

        // 1. Fast greedy descent to Layer 1
        for (int lc = maxLayer; lc > 0; lc--) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (HnswNode neighbor : currObj.neighborsByLayer.get(lc)) {
                    float dist = distanceFunction.compute(query, neighbor.vector);
                    if (dist < currDist) {
                        currDist = dist;
                        currObj = neighbor;
                        changed = true;
                    }
                }
            }
        }

        // 2. Beam search at Layer 0
        PriorityQueue<NodeDist> topCandidates = searchLayer(query, currObj, Math.max(efSearch, k), 0);
        
        List<SearchResult> results = new ArrayList<>();
        while (!topCandidates.isEmpty() && results.size() < k) {
            NodeDist nd = topCandidates.poll(); // Pops closest first
            if (!deletedIds.contains(nd.node.id)) {
                results.add(new SearchResult(nd.node.id, nd.distance));
            }
        }
        return results;
    }

    private record NodeDist(HnswNode node, float distance) implements Comparable<NodeDist> {
        @Override
        public int compareTo(NodeDist o) {
            return Float.compare(this.distance, o.distance); // Min-heap (smallest distance first)
        }
    }

    // Explores the neighborhood in a specific layer and returns a Min-Heap of closest neighbors
    private PriorityQueue<NodeDist> searchLayer(float[] query, HnswNode entry, int ef, int layer) {
        Set<String> visited = new HashSet<>();
        PriorityQueue<NodeDist> candidates = new PriorityQueue<>(); // Min-heap for exploration
        PriorityQueue<NodeDist> topResults = new PriorityQueue<>(Comparator.reverseOrder()); // Max-heap for tracking the top EF
        
        float dist = distanceFunction.compute(query, entry.vector);
        NodeDist ep = new NodeDist(entry, dist);
        
        candidates.add(ep);
        topResults.add(ep);
        visited.add(entry.id);

        while (!candidates.isEmpty()) {
            NodeDist curr = candidates.poll();
            
            // Stop early if our closest un-explored candidate is worse than the worst in our top-EF results
            if (curr.distance > topResults.peek().distance) {
                break;
            }

            for (HnswNode neighbor : curr.node.neighborsByLayer.get(layer)) {
                if (visited.add(neighbor.id)) {
                    float d = distanceFunction.compute(query, neighbor.vector);
                    if (topResults.size() < ef || d < topResults.peek().distance) {
                        NodeDist nd = new NodeDist(neighbor, d);
                        candidates.add(nd);
                        topResults.add(nd);
                        if (topResults.size() > ef) {
                            topResults.poll(); // Kick out the furthest
                        }
                    }
                }
            }
        }
        
        PriorityQueue<NodeDist> result = new PriorityQueue<>();
        result.addAll(topResults);
        return result;
    }

    // Simplistic neighbor selection: Pick the closest ones
    private List<HnswNode> selectNeighbors(PriorityQueue<NodeDist> candidates, int mMax) {
        List<HnswNode> selected = new ArrayList<>();
        while (!candidates.isEmpty() && selected.size() < mMax) {
            selected.add(candidates.poll().node);
        }
        return selected;
    }

    // Drops the furthest connections if a node has too many
    private void pruneConnections(HnswNode node, int layer, int mMax) {
        List<HnswNode> connections = node.neighborsByLayer.get(layer);
        PriorityQueue<NodeDist> pq = new PriorityQueue<>();
        for (HnswNode neighbor : connections) {
            float dist = distanceFunction.compute(node.vector, neighbor.vector);
            pq.add(new NodeDist(neighbor, dist));
        }
        connections.clear();
        while (!pq.isEmpty() && connections.size() < mMax) {
            connections.add(pq.poll().node); // Keep closest
        }
    }
}
