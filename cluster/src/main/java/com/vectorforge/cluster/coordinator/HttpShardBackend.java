package com.vectorforge.cluster.coordinator;

import com.vectorforge.vectorindex.hnsw.HnswIndex;
import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpShardBackend implements ShardBackend {
    private static final Pattern RESULT = Pattern.compile("\\{\\\"id\\\":\\\"([^\\\"]+)\\\",\\s*\\\"distance\\\":([0-9.Ee+-]+)\\}");
    private final HttpClient client = HttpClient.newHttpClient();
    private final URI baseUri;

    public HttpShardBackend(URI baseUri) {
        this.baseUri = baseUri;
    }

    @Override
    public void upsert(String id, float[] vector, byte[] metadata) throws IOException {
        String body = "{\"id\":\"" + id + "\",\"vector\":" + vectorJson(vector) + "}";
        send("/upsert", body);
    }

    @Override
    public void delete(String id) throws IOException {
        send("/delete", "{\"id\":\"" + id + "\"}");
    }

    @Override
    public int clearAll() throws IOException {
        String response = send("/clear", "{}");
        return Math.toIntExact(extractLong(response, "deleted"));
    }

    @Override
    public List<HnswIndex.SearchResult> query(float[] vector, int topK, int efSearch) throws IOException {
        String body = "{\"vector\":" + vectorJson(vector) + ",\"top_k\":" + topK + ",\"ef_search\":" + efSearch + "}";
        String response = send("/query", body);
        List<HnswIndex.SearchResult> results = new ArrayList<>();
        Matcher matcher = RESULT.matcher(response);
        while (matcher.find()) {
            results.add(new HnswIndex.SearchResult(matcher.group(1), Float.parseFloat(matcher.group(2))));
        }
        return results;
    }

    @Override
    public List<BruteForceIndex.SearchResult> bruteForceQuery(float[] vector, int topK) throws IOException {
        String body = "{\"vector\":" + vectorJson(vector) + ",\"top_k\":" + topK + "}";
        String response = send("/bruteforce/query", body);
        List<BruteForceIndex.SearchResult> results = new ArrayList<>();
        Matcher matcher = RESULT.matcher(response);
        while (matcher.find()) {
            results.add(new BruteForceIndex.SearchResult(matcher.group(1), Float.parseFloat(matcher.group(2))));
        }
        return results;
    }

    @Override
    public Map<String, Object> stats() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/stats")).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Shard health returned " + response.statusCode());
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("status", "healthy");
            stats.put("memtable_bytes", extractLong(response.body(), "memtable_bytes"));
            stats.put("sstable_count", extractLong(response.body(), "sstable_count"));
            stats.put("vector_count", extractLong(response.body(), "vector_count"));
            return stats;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Shard health request interrupted", e);
        }
    }

    private long extractLong(String body, String field) throws IOException {
        Matcher matcher = Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) throw new IOException("Shard stats missing " + field);
        return Long.parseLong(matcher.group(1));
    }

    private String send(String path, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Shard request failed: " + response.body());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Shard request interrupted", e);
        }
    }

    private String vectorJson(float[] vector) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) json.append(',');
            json.append(vector[i]);
        }
        return json.append(']').toString();
    }
}
