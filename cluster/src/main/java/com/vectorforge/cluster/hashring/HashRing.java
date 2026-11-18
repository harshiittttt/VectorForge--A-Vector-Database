package com.vectorforge.cluster.hashring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Implements Consistent Hashing with Virtual Nodes.
 * This distributes vector data evenly across all physical shards in the cluster
 * and minimizes data movement when nodes join or leave.
 */
public class HashRing {
    // Maps a 64-bit Hash -> Physical Node ID
    private final SortedMap<Long, String> ring = new TreeMap<>();
    private final int vnodesPerNode;
    private final MessageDigest md5;

    public HashRing(int vnodesPerNode) {
        this.vnodesPerNode = vnodesPerNode;
        try {
            this.md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void addNode(String nodeId) {
        // Create multiple virtual nodes for a single physical node
        for (int i = 0; i < vnodesPerNode; i++) {
            long hash = hash(nodeId + "-vnode-" + i);
            ring.put(hash, nodeId);
        }
    }

    public synchronized void removeNode(String nodeId) {
        ring.entrySet().removeIf(entry -> entry.getValue().equals(nodeId));
    }

    public String getPrimaryNode(String key) {
        if (ring.isEmpty()) return null;
        long hash = hash(key);
        
        // Find the first virtual node that is >= the key's hash
        SortedMap<Long, String> tailMap = ring.tailMap(hash);
        
        // Wrap around to the start of the ring if we passed the end
        Long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        return ring.get(nodeHash);
    }
    
    /**
     * Finds the Primary node + N-1 distinct Replicas by walking clockwise around the ring.
     */
    public List<String> getReplicas(String key, int count) {
        if (ring.isEmpty()) return Collections.emptyList();
        
        Set<String> uniqueNodes = new LinkedHashSet<>();
        long hash = hash(key);
        
        SortedMap<Long, String> tailMap = ring.tailMap(hash);
        Iterator<String> it = tailMap.values().iterator();
        
        while (uniqueNodes.size() < count && it.hasNext()) {
            uniqueNodes.add(it.next());
        }
        
        // Wrap around if we hit the end of the ring before finding enough distinct physical nodes
        if (uniqueNodes.size() < count) {
            it = ring.values().iterator();
            while (uniqueNodes.size() < count && it.hasNext()) {
                uniqueNodes.add(it.next());
            }
        }
        
        return new ArrayList<>(uniqueNodes);
    }

    // Hashes a string into a 64-bit integer
    private long hash(String key) {
        md5.reset();
        md5.update(key.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md5.digest();
        return ((long) (digest[7] & 0xFF) << 56) |
               ((long) (digest[6] & 0xFF) << 48) |
               ((long) (digest[5] & 0xFF) << 40) |
               ((long) (digest[4] & 0xFF) << 32) |
               ((long) (digest[3] & 0xFF) << 24) |
               ((long) (digest[2] & 0xFF) << 16) |
               ((long) (digest[1] & 0xFF) << 8)  |
               ((long) (digest[0] & 0xFF));
    }
}
