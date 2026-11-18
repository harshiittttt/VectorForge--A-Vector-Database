package com.vectorforge.vectorindex.distance;

public class L2Distance implements DistanceFunction {
    @Override
    public float compute(float[] v1, float[] v2) {
        if (v1.length != v2.length) throw new IllegalArgumentException("Vector dimensions must match");
        float sum = 0;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sum += diff * diff;
        }
        // Returning squared L2 distance saves a sqrt computation 
        // and preserves the exact same ordering for KNN searches.
        return sum; 
    }
}
