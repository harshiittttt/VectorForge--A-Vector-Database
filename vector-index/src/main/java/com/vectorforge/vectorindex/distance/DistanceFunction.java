package com.vectorforge.vectorindex.distance;

public interface DistanceFunction {
    /**
     * Computes the distance between two vectors.
     * Lower values indicate higher similarity.
     */
    float compute(float[] v1, float[] v2);
}
