package com.kgiannopoulou.ledgerforge.webhook;

import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEndpointApiTest extends IntegrationTest {

    private static final String URL = "https://merchant.example.com/webhooks";
    private static final String ENDPOINTS = "/api/v1/webhook-endpoints";

    @Autowired
    JdbcClient jdbc;

    @Test
    void registeringReturnsTheSecretOnceAndStoresItEncrypted() {
        UUID wallet = newWallet("EUR");

        var created = register(wallet, URL, "payment.captured", "payment.refunded");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).containsEntry("url", URL).containsEntry("status", "ACTIVE")
                .containsEntry("eventTypes", List.of("payment.captured", "payment.refunded"));
        String secret = created.string("secret");
        assertThat(secret).startsWith("whsec_").hasSizeGreaterThan(40);

        String id = created.string("id");
        assertThat(asOwnerOf(wallet).get(ENDPOINTS + "/" + id).body()).doesNotContainKey("secret");
        String stored = jdbc.sql("SELECT secret_ciphertext FROM webhook_endpoints WHERE id = ?")
                .param(UUID.fromString(id)).query(String.class).single();
        assertThat(stored).isNotEqualTo(secret).doesNotContain(secret.substring(6));
    }

    @Test
    void secretsAreUniquePerEndpoint() {
        UUID wallet = newWallet("EUR");

        assertThat(register(wallet, URL, "payment.captured").string("secret"))
                .isNotEqualTo(register(wallet, URL, "payment.captured").string("secret"));
    }

    @Test
    void onlyTheOwnerCanRegisterAnEndpoint() {
        UUID alice = newWallet("EUR");
        UUID bob = newWallet("EUR");
        var request = Map.of("accountId", alice, "url", URL, "eventTypes", List.of("payment.captured"));

        assertThat(asOwnerOf(bob).post(ENDPOINTS, request).status()).isEqualTo(403);
        assertThat(anonymous.post(ENDPOINTS, request).status()).isEqualTo(401);
        assertThat(api.post(ENDPOINTS, request).status()).as("admins do not act for customers").isEqualTo(403);
    }

    @Test
    void endpointsAndDeliveriesAreOwnerOrStaffOnly() {
        UUID alice = newWallet("EUR");
        UUID bob = newWallet("EUR");
        String id = register(alice, URL, "payment.captured").string("id");

        ApiClient mallory = asOwnerOf(bob);
        assertThat(mallory.get(ENDPOINTS + "/" + id).status()).isEqualTo(403);
        assertThat(mallory.get(ENDPOINTS + "/" + id + "/deliveries").status()).isEqualTo(403);
        assertThat(mallory.get(ENDPOINTS + "?accountId=" + alice).status()).isEqualTo(403);
        assertThat(mallory.delete(ENDPOINTS + "/" + id).status()).isEqualTo(403);

        assertThat(as("sam", Role.SUPPORT).get(ENDPOINTS + "/" + id).status()).isEqualTo(200);
        assertThat(as("ava", Role.AUDITOR).get(ENDPOINTS + "/" + id + "/deliveries").status()).isEqualTo(200);
        assertThat(as("sam", Role.SUPPORT).delete(ENDPOINTS + "/" + id).status()).isEqualTo(403);
    }

    @Test
    void invalidRegistrationsAreRejected() {
        UUID wallet = newWallet("EUR");

        assertThat(register(wallet, "ftp://example.com/x", "payment.captured").code()).isEqualTo("INVALID_WEBHOOK_URL");
        assertThat(register(wallet, "not a url", "payment.captured").code()).isEqualTo("INVALID_WEBHOOK_URL");
        assertThat(register(wallet, "https://user:pass@example.com/", "payment.captured").code())
                .isEqualTo("INVALID_WEBHOOK_URL");
        assertThat(register(wallet, URL, "payment.exploded").code()).isEqualTo("UNKNOWN_WEBHOOK_EVENT_TYPE");
        assertThat(register(wallet, URL).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void anAccountHasALimitedNumberOfActiveEndpoints() {
        UUID wallet = newWallet("EUR");
        for (int i = 0; i < 10; i++) {
            assertThat(register(wallet, URL, "payment.captured").status()).isEqualTo(201);
        }

        var eleventh = register(wallet, URL, "payment.captured");

        assertThat(eleventh.status()).isEqualTo(422);
        assertThat(eleventh.code()).isEqualTo("WEBHOOK_ENDPOINT_LIMIT_REACHED");
    }

    @Test
    void disablingIsIdempotentAndAuditedOnce() {
        UUID wallet = newWallet("EUR");
        String id = register(wallet, URL, "payment.captured").string("id");

        assertThat(asOwnerOf(wallet).delete(ENDPOINTS + "/" + id).status()).isEqualTo(204);
        assertThat(asOwnerOf(wallet).delete(ENDPOINTS + "/" + id).status()).isEqualTo(204);

        assertThat(asOwnerOf(wallet).get(ENDPOINTS + "/" + id).body()).containsEntry("status", "DISABLED");
        assertThat(jdbc.sql("SELECT action FROM audit_events WHERE entity_id = ? ORDER BY occurred_at")
                .param(UUID.fromString(id)).query(String.class).list())
                .containsExactly("WEBHOOK_ENDPOINT_REGISTERED", "WEBHOOK_ENDPOINT_DISABLED");
    }

    @Test
    void listShowsTheAccountsEndpointsNewestFirst() {
        UUID wallet = newWallet("EUR");
        String first = register(wallet, URL, "payment.captured").string("id");
        String second = register(wallet, URL, "account.frozen").string("id");

        var list = asOwnerOf(wallet).get(ENDPOINTS + "?accountId=" + wallet);

        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) list.body().get("items");
        assertThat(items).extracting(e -> e.get("id")).containsExactly(second, first);
        assertThat(items).allSatisfy(e -> assertThat(e).doesNotContainKey("secret"));
    }

    private ApiClient.Response register(UUID wallet, String url, String... eventTypes) {
        return asOwnerOf(wallet).post(ENDPOINTS, Map.of(
                "accountId", wallet, "url", url, "eventTypes", List.of(eventTypes)));
    }
}
