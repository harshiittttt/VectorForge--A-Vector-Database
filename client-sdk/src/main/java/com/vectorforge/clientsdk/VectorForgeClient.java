package com.vectorforge.clientsdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

/**
 * A simple Java Client SDK for VectorForge.
 * Allows users to programmatically interact with the database instead of using cURL.
 */
public class VectorForgeClient {
    private final HttpClient httpClient;
    private final String baseUrl;

    public VectorForgeClient(String host, int port) {
        this.httpClient = HttpClient.newHttpClient();
        this.baseUrl = "http://" + host + ":" + port;
    }

    public boolean upsert(String id, float[] vector) throws Exception {
        String json = String.format("{\"id\":\"%s\", \"vector\":%s}", id, Arrays.toString(vector));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/upsert"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    public String query(float[] vector) throws Exception {
        String json = String.format("{\"vector\":%s}", Arrays.toString(vector));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/query"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
    
    // Quick Demo
    public static void main(String[] args) throws Exception {
        VectorForgeClient client = new VectorForgeClient("localhost", 8080);
        
        System.out.println("Upserting vectors...");
        client.upsert("user_123", new float[]{0.1f, 0.2f, 0.3f});
        client.upsert("user_456", new float[]{0.9f, 0.8f, 0.7f});
        
        System.out.println("Querying for closest to [0.1, 0.2, 0.3]...");
        String results = client.query(new float[]{0.1f, 0.2f, 0.3f});
        
        System.out.println("Database Response: " + results);
    }
}
