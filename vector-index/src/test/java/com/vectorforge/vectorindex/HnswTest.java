package com.vectorforge.vectorindex;

import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;
import com.vectorforge.vectorindex.distance.L2Distance;
import com.vectorforge.vectorindex.hnsw.HnswIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HnswTest {

    @Test
    void testHnswRecallVersusBruteForce() {
        L2Distance distance = new L2Distance();
        BruteForceIndex bruteForce = new BruteForceIndex(distance);
        
        // M=16, efConstruction=100, efSearch=50
        HnswIndex hnsw = new HnswIndex(distance, 16, 100, 50); 
        
        Random rand = new Random(42);
        
        // Generate 1000 random 16-dimensional vectors
        for (int i = 0; i < 1000; i++) {
            float[] vec = new float[16];
            for (int d = 0; d < 16; d++) vec[d] = rand.nextFloat();
            String id = "vec_" + i;
            
            bruteForce.upsert(id, vec);
            hnsw.insert(id, vec);
        }
        
        // Query with a new random vector
        float[] query = new float[16];
        for (int d = 0; d < 16; d++) query[d] = rand.nextFloat();
        
        int topK = 10;
        List<BruteForceIndex.SearchResult> trueNearest = bruteForce.search(query, topK);
        List<HnswIndex.SearchResult> approxNearest = hnsw.search(query, topK);
        
        // Calculate Recall@10
        int matches = 0;
        for (HnswIndex.SearchResult approxResult : approxNearest) {
            for (BruteForceIndex.SearchResult trueResult : trueNearest) {
                if (approxResult.id().equals(trueResult.id())) {
                    matches++;
                    break;
                }
            }
        }
        
        double recall = (double) matches / topK;
        System.out.println("Recall@10: " + (recall * 100) + "%");
        
        // HNSW should have very high recall (>80%) on small, easy datasets
        assertTrue(recall >= 0.80, "Recall should be at least 80%");
    }
}
