package com.vectorforge.vectorindex.distance;

public class CosineDistance implements DistanceFunction {
    @Override
    public float compute(float[] v1, float[] v2) {
        if (v1.length != v2.length) throw new IllegalArgumentException("Vector dimensions must match");
        float dotProduct = 0;
        float norm1 = 0;
        float norm2 = 0;
        
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        
        if (norm1 == 0 || norm2 == 0) return 1.0f; // Handle zero-vectors safely
        
        float similarity = dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
        
        // Convert cosine similarity [-1, 1] to cosine distance [0, 2]
        // where 0 means perfectly identical orientation.
        return 1.0f - similarity; 
    }
}
