package com.vectorforge.storageengine.sstable;

import java.util.BitSet;

/**
 * A simple Bloom Filter to quickly determine if a vectorId might exist in an SSTable,
 * avoiding expensive disk reads for keys that are definitely not present.
 */
public class BloomFilter {
    private final BitSet bitSet;
    private final int size;
    private final int numHashFunctions;

    public BloomFilter(int expectedElements, double falsePositiveRate) {
        // Calculate optimal bit array size
        this.size = (int) Math.ceil(-(expectedElements * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
        // Calculate optimal number of hash functions
        this.numHashFunctions = (int) Math.ceil((size / (double) expectedElements) * Math.log(2));
        this.bitSet = new BitSet(size);
    }
    
    // Reconstruct from bytes loaded from disk
    public BloomFilter(byte[] bytes, int size, int numHashFunctions) {
        this.bitSet = BitSet.valueOf(bytes);
        this.size = size;
        this.numHashFunctions = numHashFunctions;
    }

    public void add(String key) {
        byte[] bytes = key.getBytes();
        for (int i = 0; i < numHashFunctions; i++) {
            int hash = hash(bytes, i);
            bitSet.set(Math.abs(hash % size));
        }
    }

    public boolean mightContain(String key) {
        byte[] bytes = key.getBytes();
        for (int i = 0; i < numHashFunctions; i++) {
            int hash = hash(bytes, i);
            if (!bitSet.get(Math.abs(hash % size))) {
                return false;
            }
        }
        return true;
    }
    
    public byte[] toByteArray() {
        return bitSet.toByteArray();
    }
    
    public int getSize() { return size; }
    
    public int getNumHashFunctions() { return numHashFunctions; }

    // Simple custom hash leveraging a seed to simulate multiple hash functions
    private int hash(byte[] data, int seed) {
        int hash = seed;
        for (byte b : data) {
            hash = (hash * 31) ^ b;
        }
        return hash;
    }
}
