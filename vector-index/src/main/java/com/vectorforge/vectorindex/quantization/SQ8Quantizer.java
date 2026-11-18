package com.vectorforge.vectorindex.quantization;

/**
 * Scalar Quantization (SQ8): Compresses 32-bit floats into 8-bit bytes.
 * This slashes the memory footprint of the HNSW graph by exactly 75%, 
 * allowing 4x as many vectors to fit in RAM with a negligible loss in accuracy.
 */
public class SQ8Quantizer {
    
    public record QuantizedVector(byte[] bytes, float min, float max) {}

    /**
     * Converts a Float32 array into an Int8 (byte) array based on the min/max bounds.
     */
    public static QuantizedVector quantize(float[] vector) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        
        // 1. Find min and max bounds to establish the compression range
        for (float v : vector) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        
        float range = max - min;
        if (range == 0) range = 1.0f; // Prevent division by zero if all values are identical
        
        byte[] quantized = new byte[vector.length];
        for (int i = 0; i < vector.length; i++) {
            // 2. Normalize to a [0, 1] scale
            float normalized = (vector[i] - min) / range;
            
            // 3. Map to [0, 255] and shift to Java's signed byte range [-128, 127]
            int b = Math.round(normalized * 255.0f) - 128;
            quantized[i] = (byte) b;
        }
        
        return new QuantizedVector(quantized, min, max);
    }

    /**
     * Reconstructs the Float32 array from the byte array.
     * (Note: This is an approximation. Small precision loss occurs here!)
     */
    public static float[] dequantize(QuantizedVector qv) {
        float[] vector = new float[qv.bytes().length];
        float range = qv.max() - qv.min();
        
        for (int i = 0; i < qv.bytes().length; i++) {
            // 1. Shift signed byte back to [0, 255] integer space
            int unsignedByte = qv.bytes()[i] + 128;
            
            // 2. Reverse the mathematical mapping
            vector[i] = (unsignedByte / 255.0f) * range + qv.min();
        }
        return vector;
    }
}
