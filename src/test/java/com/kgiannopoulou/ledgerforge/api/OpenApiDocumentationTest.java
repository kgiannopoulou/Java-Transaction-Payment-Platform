package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The published contract, read back as a client would. Docs that are not checked drift. */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "ledgerforge.security.dev-tokens.enabled=true"
})
class OpenApiDocumentationTest extends IntegrationTest {

    @LocalServerPort
    int port;

    private Map<String, Object> document;

    @BeforeEach
    void readTheDocument() {
        var response = anonymous.get("/v3/api-docs");
        assertThat(response.status()).as("the docs need no token").isEqualTo(200);
        document = response.body();
    }

    @Test
    void everyPublicEndpointIsDocumentedWithItsModule() {
        assertThat(paths()).containsKeys("/api/v1/payments", "/api/v1/payments/{id}/capture", "/api/v1/transfers",
                "/api/v1/webhook-endpoints", "/api/v1/risk/decisions", "/api/v1/auth/token");
        assertThat(operation("post", "/api/v1/payments").get("tags")).isEqualTo(List.of("Payments"));
    }

    @Test
    void authenticationIsDocumented() {
        assertThat(map(map(document.get("components")).get("securitySchemes"))).containsKey("bearerAuth");
        assertThat(operation("get", "/api/v1/transfers/{id}").get("security")).asString().contains("bearerAuth");
        assertThat(operation("post", "/api/v1/auth/token")).as("the way in needs no token").doesNotContainKey("security");
    }

    @Test
    void idempotencyHeadersAreDocumented() {
        assertThat(operation("post", "/api/v1/transfers").get("parameters")).asString()
                .contains("Idempotency-Key").contains("retries safe");
    }

    @Test
    void errorResponsesUseTheProblemSchema() {
        var responses = map(operation("post", "/api/v1/payments").get("responses"));

        assertThat(responses).containsKeys("201", "400", "401", "403", "409", "422");
        assertThat(responses.get("422")).asString().contains("application/problem+json", "#/components/schemas/Problem");
        assertThat(map(map(document.get("components")).get("schemas"))).containsKey("Problem");
    }

    @Test
    void rateLimitsAreDocumented() {
        assertThat(map(operation("post", "/api/v1/payments").get("responses")).get("429")).asString()
                .contains("Retry-After");
    }

    @Test
    void paginationIsDocumented() {
        assertThat(operation("get", "/api/v1/accounts/{accountId}/transactions").get("parameters")).asString()
                .contains("page", "size", "starting from 0");
        assertThat(map(document.get("info")).get("description")).asString().contains("## Pagination");
    }

    @Test
    void swaggerUiLoadsWithoutLoggingIn() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var page = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui.html")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("swagger-ui");
        }
    }

    private Map<String, Object> paths() {
        return map(document.get("paths"));
    }

    private Map<String, Object> operation(String method, String path) {
        return map(map(paths().get(path)).get(method));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
