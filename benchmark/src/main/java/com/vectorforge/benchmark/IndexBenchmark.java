package com.vectorforge.benchmark;

import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;
import com.vectorforge.vectorindex.distance.L2Distance;
import com.vectorforge.vectorindex.hnsw.HnswIndex;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class IndexBenchmark {

    public static void main(String[] args) throws Exception {
        List<String> idsList = new ArrayList<>();
        List<float[]> datasetList = new ArrayList<>();
        
        File customFile = new File("custom_vectors.csv");
        if (customFile.exists()) {
            System.out.println("1. Loading YOUR custom data from custom_vectors.csv...");
            try (BufferedReader br = new BufferedReader(new FileReader(customFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    String id = parts[0];
                    float[] vec = new float[parts.length - 1];
                    for (int i = 0; i < vec.length; i++) {
                        vec[i] = Float.parseFloat(parts[i+1]);
                    }
                    idsList.add(id);
                    datasetList.add(vec);
                }
            }
        } else {
            System.out.println("1. No custom data found. Generating 10,000 random vectors...");
            Random rand = new Random(42);
            for (int i = 0; i < 10000; i++) {
                float[] vec = new float[128];
                for (int d = 0; d < 128; d++) vec[d] = rand.nextFloat();
                idsList.add("vec_" + i);
                datasetList.add(vec);
            }
        }

        int numVectors = datasetList.size();
        int dimensions = datasetList.get(0).length;
        System.out.println("Loaded " + numVectors + " vectors of dimension " + dimensions);

        if (numVectors < 10) {
            System.err.println("Error: Please provide at least 10 sentences in your dataset for a meaningful benchmark.");
            return;
        }

        // Use up to 200 of the vectors as our search queries
        int numQueries = Math.min(200, Math.max(1, numVectors / 5));
        float[][] queries = new float[numQueries][dimensions];
        for (int i = 0; i < numQueries; i++) {
            queries[i] = datasetList.get(i);
        }

        int topK = Math.min(10, numVectors / 2); // Dynamic topK based on dataset size

        L2Distance distance = new L2Distance();
        
        System.out.println("2. Loading Brute Force Oracle...");
        BruteForceIndex bruteForce = new BruteForceIndex(distance);
        for (int i = 0; i < numVectors; i++) bruteForce.upsert(idsList.get(i), datasetList.get(i));

        System.out.println("3. Building HNSW Graph (M=16, efConstruction=200)...");
        HnswIndex hnsw = new HnswIndex(distance, 16, 200, 50);
        for (int i = 0; i < numVectors; i++) hnsw.insert(idsList.get(i), datasetList.get(i));

        System.out.println("4. Running Benchmark Queries...");
        try (FileWriter csv = new FileWriter("benchmark_results.csv")) {
            csv.write("Algorithm,ef_search,Recall,Latency_ms\n");

            // --- BRUTE FORCE BENCHMARK ---
            long startBf = System.nanoTime();
            List<List<BruteForceIndex.SearchResult>> trueResults = new ArrayList<>();
            for (float[] q : queries) {
                trueResults.add(bruteForce.search(q, topK));
            }
            long endBf = System.nanoTime();
            double bfLatency = (endBf - startBf) / 1_000_000.0 / numQueries;
            
            System.out.printf("Brute Force | Latency: %.4f ms | Recall: 1.0\n", bfLatency);
            csv.write(String.format("BruteForce,0,1.0,%f\n", bfLatency));

            // --- HNSW BENCHMARK ---
            int[] efSearchValues = {10, 20, 40, 80, 150, 300};
            Field efField = HnswIndex.class.getDeclaredField("efSearch");
            efField.setAccessible(true);

            for (int ef : efSearchValues) {
                if (ef < topK) continue; // Skip invalid configs for small datasets
                
                efField.set(hnsw, ef);
                
                long startHnsw = System.nanoTime();
                List<List<HnswIndex.SearchResult>> approxResults = new ArrayList<>();
                for (float[] q : queries) {
                    approxResults.add(hnsw.search(q, topK));
                }
                long endHnsw = System.nanoTime();
                double hnswLatency = (endHnsw - startHnsw) / 1_000_000.0 / numQueries;

                // Calculate Recall
                int totalMatches = 0;
                for (int i = 0; i < numQueries; i++) {
                    List<BruteForceIndex.SearchResult> exact = trueResults.get(i);
                    List<HnswIndex.SearchResult> approx = approxResults.get(i);
                    
                    for (HnswIndex.SearchResult a : approx) {
                        for (BruteForceIndex.SearchResult e : exact) {
                            if (a.id().equals(e.id())) {
                                totalMatches++;
                                break;
                            }
                        }
                    }
                }
                double recall = (double) totalMatches / (numQueries * topK);
                
                System.out.printf("HNSW (ef=%-3d) | Latency: %6.4f ms | Recall: %.3f\n", ef, hnswLatency, recall);
                csv.write(String.format("HNSW,%d,%f,%f\n", ef, recall, hnswLatency));
            }
        }
        System.out.println("Results saved to benchmark_results.csv! You can now run 'python plot_results.py'.");
    }
}
