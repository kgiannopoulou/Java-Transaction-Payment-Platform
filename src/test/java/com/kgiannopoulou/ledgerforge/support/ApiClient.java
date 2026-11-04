package com.kgiannopoulou.ledgerforge.support;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal thread-safe HTTP client for black-box API tests. Uses the JDK client so concurrency
 * tests exercise the real servlet container, thread pool and connection pool - not MockMvc.
 */
public final class ApiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String baseUrl;
    private final String bearerToken;

    /** Anonymous client: no Authorization header. */
    public ApiClient(int port) {
        this("http://localhost:" + port, null);
    }

    private ApiClient(String baseUrl, String bearerToken) {
        this.baseUrl = baseUrl;
        this.bearerToken = bearerToken;
    }

    /** Same server, authenticated with the given JWT. Clients are immutable and thread-safe. */
    public ApiClient withToken(String token) {
        return new ApiClient(baseUrl, token);
    }

    public ApiClient anonymous() {
        return new ApiClient(baseUrl, null);
    }

    public record Response(int status, Map<String, Object> body, Map<String, java.util.List<String>> headers) {

        public String string(String field) {
            Object value = body.get(field);
            return value == null ? null : value.toString();
        }

        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst();
        }

        public String code() {
            return string("type");
        }
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public Response get(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        headers.forEach(builder::header);
        return send(builder);
    }

    public Response delete(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE());
    }

    public Response post(String path, Object body) {
        return post(path, body, null);
    }

    public Response post(String path, Object body, String idempotencyKey) {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder);
    }

    @SuppressWarnings("unchecked")
    private Response send(HttpRequest.Builder builder) {
        try {
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = HTTP.send(builder.timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body() == null ? "" : response.body().strip();
            Map<String, Object> body = raw.isEmpty() ? Map.of()
                    : raw.startsWith("[") ? Map.of("items", json.readValue(raw, java.util.List.class))
                    : json.readValue(raw, Map.class);
            return new Response(response.statusCode(), body, response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
