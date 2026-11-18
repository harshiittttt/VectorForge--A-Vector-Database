package com.vectorforge.vectorindex.distance;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorOperators;

/**
 * Hardware-accelerated L2 distance computation using SIMD (Single Instruction, Multiple Data).
 * This allows the CPU to calculate multiple dimensions simultaneously (e.g., AVX-512 processes 16 floats per clock cycle).
 * 
 * Note: Requires running the JVM with `--add-modules jdk.incubator.vector`
 */
public class SimdL2Distance implements DistanceFunction {
    // Automatically selects the best SIMD lane width for the host CPU architecture
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    @Override
    public float compute(float[] v1, float[] v2) {
        if (v1.length != v2.length) throw new IllegalArgumentException("Vector dimensions must match");
        
        int i = 0;
        int upperBound = SPECIES.loopBound(v1.length);
        FloatVector sumVector = FloatVector.zero(SPECIES);

        // 1. SIMD Unrolled Loop: Process N dimensions at a time
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector vec1 = FloatVector.fromArray(SPECIES, v1, i);
            FloatVector vec2 = FloatVector.fromArray(SPECIES, v2, i);
            
            FloatVector diff = vec1.sub(vec2);
            FloatVector squared = diff.mul(diff);
            
            sumVector = sumVector.add(squared);
        }

        // 2. Reduce the parallel lanes down to a single float scalar
        float sum = sumVector.reduceLanes(VectorOperators.ADD);

        // 3. Scalar tail processing for any remaining dimensions that didn't divide cleanly into the SIMD lane
        for (; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sum += diff * diff;
        }

        return sum;
    }
}
