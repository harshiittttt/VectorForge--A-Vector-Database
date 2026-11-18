package com.vectorforge.api.http;

import com.sun.net.httpserver.HttpServer;
import com.vectorforge.cluster.coordinator.Coordinator;
import com.vectorforge.shardnode.ShardNode;
import com.vectorforge.vectorindex.bruteforce.BruteForceIndex;
import com.vectorforge.vectorindex.hnsw.HnswIndex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lightweight HTTP gateway for VectorForge. 
 * Allows immediate testing via curl without needing to compile gRPC clients.
 */
public class HttpGateway {
    private final HttpServer server;
    private final Coordinator coordinator;
    private final List<ShardNode> shardNodes;

    public HttpGateway(int port, Path dataDir) throws IOException {
        this.coordinator = new Coordinator(2);
        this.shardNodes = new ArrayList<>();
        String remoteShards = System.getProperty("vectorforge.remoteShards", "").trim();
        if (remoteShards.isEmpty()) {
            Path shardRoot = dataDir.resolve("shards");
            for (int i = 1; i <= 3; i++) {
                ShardNode shard = new ShardNode(shardRoot.resolve("node-" + i));
                shardNodes.add(shard);
                coordinator.registerNode("node-" + i, shard);
            }
        } else {
            String[] endpoints = remoteShards.split(",");
            for (int i = 0; i < endpoints.length; i++) {
                coordinator.registerBackend("node-" + (i + 1),
                        new com.vectorforge.cluster.coordinator.HttpShardBackend(URI.create(endpoints[i].trim())));
            }
        }
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Extremely simple manual JSON parsing for demo purposes to avoid external dependencies
        this.server.createContext("/upsert", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    
                    String id = extractString(body, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                    float[] vector = extractVector(body, "\"vector\"\\s*:\\s*\\[([^\\]]+)\\]");
                    
                    if (id != null && vector != null) {
                        coordinator.upsert(id, vector, null);
                        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                    } else {
                        sendResponse(exchange, 400, "{\"error\":\"Invalid format. Expected {'id':'...', 'vector':[...]}\"}");
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                sendResponse(exchange, 405, "Method not allowed");
            }
        });

        this.server.createContext("/query", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    float[] vector = extractVector(body, "\"vector\"\\s*:\\s*\\[([^\\]]+)\\]");
                    int topK = extractInt(body, "\"top_k\"\\s*:\\s*(\\d+)", 10);
                    int efSearch = extractInt(body, "\"ef_search\"\\s*:\\s*(\\d+)", 100);
                    
                    if (vector != null) {
                        List<HnswIndex.SearchResult> results = coordinator.query(vector, topK, efSearch);
                        
                        StringBuilder json = new StringBuilder("[");
                        for (int i = 0; i < results.size(); i++) {
                            json.append(String.format("{\"id\":\"%s\", \"distance\":%f}", 
                                results.get(i).id(), results.get(i).distance()));
                            if (i < results.size() - 1) json.append(",");
                        }
                        json.append("]");
                        
                        sendResponse(exchange, 200, json.toString());
                    } else {
                        sendResponse(exchange, 400, "{\"error\":\"Invalid format\"}");
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            }
        });

        this.server.createContext("/delete", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String id = extractString(body, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                    if (id == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Invalid format\"}");
                    } else {
                        coordinator.delete(id);
                        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                sendResponse(exchange, 405, "Method not allowed");
            }
        });

        this.server.createContext("/bruteforce/query", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    float[] vector = extractVector(body, "\"vector\"\\s*:\\s*\\[([^\\]]+)\\]");
                    int topK = extractInt(body, "\"top_k\"\\s*:\\s*(\\d+)", 10);
                    if (vector == null) {
                        sendResponse(exchange, 400, "{\"error\":\"Invalid format\"}");
                    } else {
                        List<BruteForceIndex.SearchResult> results = coordinator.bruteForceQuery(vector, topK);
                        sendResponse(exchange, 200, bruteForceResultsJson(results));
                    }
                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else {
                sendResponse(exchange, 405, "Method not allowed");
            }
        });

        this.server.createContext("/v1/cluster/nodes", exchange -> {
            sendResponse(exchange, 200, clusterStatsJson());
        });


        this.server.createContext("/v1/cluster/clear", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                var deletedByNode = coordinator.clearAll();
                int totalDeleted = deletedByNode.values().stream().mapToInt(Integer::intValue).sum();
                StringBuilder response = new StringBuilder("{\"deleted_vectors\":")
                        .append(totalDeleted).append(",\"per_shard\":{");
                boolean first = true;
                for (var entry : deletedByNode.entrySet()) {
                    if (!first) response.append(',');
                    first = false;
                    response.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
                }
                sendResponse(exchange, 200, response.append("}}").toString());
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    public void start() {
        server.start();
        System.out.println("VectorForge HTTP Gateway started on port " + server.getAddress().getPort());
    }

    public void stop() throws Exception {
        server.stop(0);
        for (ShardNode shard : shardNodes) {
            shard.close();
        }
    }

    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private String extractString(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : null;
    }
    
    private float[] extractVector(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        if (m.find()) {
            String[] parts = m.group(1).split(",");
            float[] vec = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vec[i] = Float.parseFloat(parts[i].trim());
            }
            return vec;
        }
        return null;
    }

    private int extractInt(String json, String regex, int defaultValue) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    private String bruteForceResultsJson(List<BruteForceIndex.SearchResult> results) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < results.size(); i++) {
            BruteForceIndex.SearchResult result = results.get(i);
            json.append(String.format("{\"id\":\"%s\", \"distance\":%f}", result.id(), result.distance()));
            if (i + 1 < results.size()) json.append(",");
        }
        return json.append("]").toString();
    }

    private String clusterStatsJson() {
        StringBuilder json = new StringBuilder("{\"node_count\":").append(coordinator.nodeCount()).append(",\"nodes\":[");
        boolean first = true;
        for (var entry : coordinator.nodeStats().entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"id\":\"").append(entry.getKey()).append("\"");
            for (var stat : entry.getValue().entrySet()) {
                json.append(",\"").append(stat.getKey()).append("\":");
                if (stat.getValue() instanceof Number) json.append(stat.getValue());
                else json.append("\"").append(stat.getValue()).append("\"");
            }
            json.append("}");
        }
        return json.append("]}").toString();
    }
    
    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(System.getProperty("user.dir"), "data");
        HttpGateway gateway = new HttpGateway(8080, dataDir);
        gateway.start();
    }
}
