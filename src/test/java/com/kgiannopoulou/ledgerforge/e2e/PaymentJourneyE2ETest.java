package com.kgiannopoulou.ledgerforge.e2e;

import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.KafkaIntegrationTest;
import com.kgiannopoulou.ledgerforge.support.WebhookReceiverStub;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The top of the pyramid: whole customer journeys through the public API, with every moving part
 * real. HTTP and JWTs in; PostgreSQL, Redis, Kafka, the outbox relay, the consumers and the webhook
 * dispatcher in between; a signed HTTP request to a merchant out. Few tests, each covering a lot.
 */
class PaymentJourneyE2ETest extends KafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebhookReceiverStub merchantServer = new WebhookReceiverStub();

    @AfterEach
    void stopMerchantServer() {
        merchantServer.close();
    }

    @Test
    void aCustomerPaysAMerchantWhoIsNotifiedAndGivesPartOfItBack() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID shop = newWallet("EUR");
        String secret = registerWebhook(shop, "payment.captured", "payment.refunded", "transfer.completed");

        UUID payment = newPayment(alice, shop, "30.00", "EUR");
        assertThat(asOwnerOf(alice).post("/api/v1/payments/" + payment + "/authorize", null).body())
                .containsEntry("status", "AUTHORIZED");
        assertThat(balanceOf(alice)).as("funds are held").isEqualByComparingTo("70.00");
        assertThat(asOwnerOf(shop).post("/api/v1/payments/" + payment + "/capture", null).body())
                .containsEntry("status", "CAPTURED");
        assertThat(asOwnerOf(shop).post("/api/v1/payments/" + payment + "/refund",
                Map.of("amount", "10.00", "currency", "EUR")).body()).containsEntry("status", "PARTIALLY_REFUNDED");
        assertThat(transfer(alice, shop, "5.00", "EUR").status()).isEqualTo(201);

        // Money, by three independent routes: balances, the ledger, the reconciliation report.
        assertThat(balanceOf(alice)).isEqualByComparingTo("75.00");
        assertThat(balanceOf(shop)).isEqualByComparingTo("25.00");
        assertLedgerConsistentFor(List.of(alice, shop));
        assertThat(as("ava", Role.AUDITOR).get("/api/v1/admin/reconciliation").body()).containsEntry("consistent", true);

        // The merchant's server, reached through outbox, Kafka, fan-out and the dispatcher.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(merchantServer.received()).hasSize(3));
        assertThat(merchantServer.received()).extracting(r -> r.json().get("type"))
                .containsExactlyInAnyOrder("payment.captured", "payment.refunded", "transfer.completed");
        assertThat(merchantServer.received()).allSatisfy(request -> assertThat(WebhookSignature.verify(
                secret, request.signature(), request.body(), Instant.now(), Duration.ofMinutes(5))).isTrue());

        // The trail an auditor would follow.
        assertThat(auditActions(payment)).containsExactly("PAYMENT_REFUNDED", "PAYMENT_CAPTURED", "PAYMENT_AUTHORIZED",
                "PAYMENT_CREATED");
    }

    @Test
    void aLargePaymentFromANewAccountIsHeldApprovedAndThenFlagged() {
        UUID whale = newFundedWallet("EUR", "20000.00");
        UUID shop = newWallet("EUR");

        var created = asOwnerOf(whale).post("/api/v1/payments", Map.of(
                "payerAccountId", whale, "merchantAccountId", shop, "amount", "12000.00", "currency", "EUR"));
        assertThat(created.body()).containsEntry("status", "UNDER_REVIEW");
        UUID payment = UUID.fromString(created.string("id"));

        assertThat(asOwnerOf(whale).post("/api/v1/payments/" + payment + "/authorize", null).status())
                .as("held: no money can move").isEqualTo(409);
        assertThat(balanceOf(whale)).isEqualByComparingTo("20000.00");

        ApiClient support = as("sam", Role.SUPPORT);
        assertThat(support.post("/api/v1/payments/" + payment + "/review", Map.of("decision", "APPROVE")).status())
                .isEqualTo(200);
        asOwnerOf(whale).post("/api/v1/payments/" + payment + "/authorize", null);
        asOwnerOf(shop).post("/api/v1/payments/" + payment + "/capture", null);
        assertThat(balanceOf(shop)).isEqualByComparingTo("12000.00");

        // Approved is not forgotten: after-the-fact monitoring still raises an alert via Kafka.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(support.get("/api/v1/risk/alerts?accountId=" + whale)
                .body().toString()).contains("LARGE_PAYMENT", payment.toString()));
    }

    @Test
    void aFrozenCustomerIsRefusedAndTheirEndpointHearsAboutTheFreeze() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID shop = newWallet("EUR");
        registerWebhook(alice, "account.frozen");

        assertThat(api.post("/api/v1/accounts/" + alice + "/freeze", null).status()).isEqualTo(200);

        var refused = asOwnerOf(alice).post("/api/v1/payments", Map.of(
                "payerAccountId", alice, "merchantAccountId", shop, "amount", "5.00", "currency", "EUR"));
        assertThat(refused.code()).isEqualTo("PAYMENT_BLOCKED");
        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(merchantServer.received()).singleElement()
                .satisfies(r -> assertThat(r.json()).containsEntry("type", "account.frozen")));
    }

    private String registerWebhook(UUID account, String... eventTypes) {
        var response = asOwnerOf(account).post("/api/v1/webhook-endpoints", Map.of(
                "accountId", account, "url", merchantServer.url(), "eventTypes", List.of(eventTypes)));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return response.string("secret");
    }

    @SuppressWarnings("unchecked")
    private List<Object> auditActions(UUID payment) {
        var body = as("ava", Role.AUDITOR).get("/api/v1/audit-events?entityId=" + payment).body();
        return ((List<Map<String, Object>>) body.get("items")).stream().map(e -> e.get("action")).toList();
    }
}
