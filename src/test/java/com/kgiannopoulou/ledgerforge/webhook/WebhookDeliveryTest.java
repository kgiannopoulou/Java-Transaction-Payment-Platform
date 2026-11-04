package com.kgiannopoulou.ledgerforge.webhook;

import com.kgiannopoulou.ledgerforge.shared.event.AccountFrozenEvent;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentCapturedEvent;
import com.kgiannopoulou.ledgerforge.support.ApiClient;
import com.kgiannopoulou.ledgerforge.support.ApiTestSupport;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.PostgresContainers;
import com.kgiannopoulou.ledgerforge.support.RedisContainers;
import com.kgiannopoulou.ledgerforge.support.WebhookReceiverStub;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookDispatcher;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookEvent;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookFanOutService;
import com.kgiannopoulou.ledgerforge.webhook.domain.WebhookSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Delivery mechanics against a real HTTP receiver and real PostgreSQL. Instead of waiting out the
 * real backoff, a test checks the stored {@code next_attempt_at} and then moves it to the past.
 *
 * <p>Own database, because the Kafka test context keeps a live dispatcher running on the shared
 * one and would claim these deliveries.
 */
class WebhookDeliveryTest extends ApiTestSupport {

    private static final PostgreSQLContainer DATABASE = PostgresContainers.start();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, DATABASE);
        RedisContainers.register(registry, RedisContainers.SHARED);
    }

    @Autowired
    WebhookFanOutService fanOut;

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    JdbcClient jdbc;

    private final WebhookReceiverStub receiver = new WebhookReceiverStub();

    /** The dispatcher works on the whole table, so leftovers from other tests must be out of the way. */
    @BeforeEach
    void clearTheQueue() {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'DEAD' WHERE status = 'PENDING'").update();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    // ---- the retry schedule ------------------------------------------------------------------

    @Test
    void failuresBackOffTenSecondsThirtySecondsTwoMinutesThenSucceed() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.respondWith(500, 503, 502); // attempt 4 gets the default 200
        UUID delivery = publishCapture(endpoint);

        attemptNow(delivery, 1);
        assertNextAttemptAfter(delivery, 1, Duration.ofSeconds(10));
        attemptNow(delivery, 2);
        assertNextAttemptAfter(delivery, 2, Duration.ofSeconds(30));
        attemptNow(delivery, 3);
        assertNextAttemptAfter(delivery, 3, Duration.ofMinutes(2));
        attemptNow(delivery, 4);

        var details = endpoint.client().get("/api/v1/webhook-deliveries/" + delivery);
        assertThat(details.body()).containsEntry("status", "DELIVERED").containsEntry("attemptCount", 4);
        assertThat(details.string("deliveredAt")).isNotNull();
        assertThat(attempts(details)).extracting(a -> a.get("responseStatus")).containsExactly(500, 503, 502, 200);
        assertThat(attempts(details)).extracting(a -> a.get("outcome"))
                .containsExactly("FAILURE", "FAILURE", "FAILURE", "SUCCESS");
        assertThat(attempts(details)).extracting(a -> a.get("attemptNumber")).containsExactly(1, 2, 3, 4);
    }

    @Test
    void afterTheLastScheduledAttemptTheDeliveryIsDeadAndCanBeReplayed() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.alwaysRespondWith(500);
        UUID delivery = publishCapture(endpoint);

        for (int attempt = 1; attempt <= 6; attempt++) { // 1 + the 5 scheduled retries
            attemptNow(delivery, attempt);
        }

        var dead = endpoint.client().get("/api/v1/webhook-deliveries/" + delivery);
        assertThat(dead.body()).containsEntry("status", "DEAD").containsEntry("lastError", "HTTP 500");
        assertThat(attempts(dead)).hasSize(6);
        assertThat(dispatcher.dispatchDue()).as("dead deliveries are never picked up again").isZero();
        assertThat(endpoint.client().get("/api/v1/webhook-endpoints/" + endpoint.id() + "/deliveries?status=DEAD")
                .body().get("items")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);

        // The receiver is fixed; the customer replays the dead delivery.
        receiver.alwaysRespondWith(200);
        var retry = endpoint.client().post("/api/v1/webhook-deliveries/" + delivery + "/retry", null);
        assertThat(retry.status()).isEqualTo(202);
        assertThat(retry.body()).containsEntry("status", "PENDING");
        dispatcher.dispatchDue();
        awaitAttempts(delivery, 7);

        var delivered = endpoint.client().get("/api/v1/webhook-deliveries/" + delivery);
        assertThat(delivered.body()).containsEntry("status", "DELIVERED");
        assertThat(attempts(delivered)).hasSize(7);
    }

    // ---- what goes over the wire -------------------------------------------------------------

    @Test
    void requestIsSignedAndUsesThePublicEnvelope() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID delivery = publish(endpoint, eventId, paymentId);

        attemptNow(delivery, 1);

        var request = receiver.received().getFirst();
        String publicId = "evt_" + eventId.toString().replace("-", "");
        assertThat(request.json()).containsEntry("id", publicId).containsEntry("type", "payment.captured")
                .containsKeys("createdAt", "data");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) request.json().get("data");
        assertThat(data).containsEntry("paymentId", paymentId.toString())
                .containsEntry("amount", "25.0000").containsEntry("currency", "EUR");
        assertThat(request.eventId()).isEqualTo(publicId);
        assertThat(request.deliveryId()).isEqualTo(delivery.toString());
        assertThat(request.attempt()).isEqualTo(1);
        assertThat(WebhookSignature.verify(endpoint.secret(), request.signature(), request.body(), Instant.now(),
                Duration.ofMinutes(5))).isTrue();
        assertThat(WebhookSignature.verify("whsec_wrong", request.signature(), request.body(), Instant.now(),
                Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void retriesCarryTheSameEventIdAndPayload() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.respondWith(500);
        UUID delivery = publishCapture(endpoint);

        attemptNow(delivery, 1);
        attemptNow(delivery, 2);

        assertThat(receiver.received()).hasSize(2);
        assertThat(receiver.received()).extracting(WebhookReceiverStub.Received::eventId).containsOnly(
                receiver.received().getFirst().eventId());
        assertThat(receiver.received()).extracting(WebhookReceiverStub.Received::body).containsOnly(
                receiver.received().getFirst().body());
        assertThat(receiver.received()).extracting(WebhookReceiverStub.Received::attempt).containsExactly(1, 2);
    }

    // ---- failure modes -----------------------------------------------------------------------

    @Test
    void aSlowEndpointTimesOutAndCountsAsAFailedAttempt() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.delayResponses(Duration.ofSeconds(3)); // request-timeout is 1s in tests
        UUID delivery = publishCapture(endpoint);

        attemptNow(delivery, 1);

        var attempt = attempts(endpoint.client().get("/api/v1/webhook-deliveries/" + delivery)).getFirst();
        assertThat(attempt).containsEntry("outcome", "FAILURE");
        assertThat(attempt.get("error").toString()).contains("Timed out");
        assertThat(attempt).doesNotContainKey("responseStatus");
        assertThat(((Number) attempt.get("durationMs")).longValue()).isBetween(900L, 2500L);
    }

    @Test
    void theDeadlineAlsoCoversAnEndpointThatNeverFinishesItsResponseBody() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.stallResponseBody(Duration.ofSeconds(4)); // request-timeout is 1s in tests
        UUID delivery = publishCapture(endpoint);

        attemptNow(delivery, 1);

        var attempt = attempts(endpoint.client().get("/api/v1/webhook-deliveries/" + delivery)).getFirst();
        assertThat(attempt).containsEntry("outcome", "FAILURE");
        assertThat(attempt.get("error").toString()).contains("Timed out");
        assertThat(((Number) attempt.get("durationMs")).longValue()).isBetween(900L, 2500L);
    }

    @Test
    void anUnreachableEndpointCountsAsAFailedAttempt() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.close(); // nothing is listening any more
        UUID delivery = publishCapture(endpoint);

        attemptNow(delivery, 1);

        var attempt = attempts(endpoint.client().get("/api/v1/webhook-deliveries/" + delivery)).getFirst();
        assertThat(attempt).containsEntry("outcome", "FAILURE").containsKey("error");
    }

    @Test
    void redirectsAreNotFollowed() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        receiver.respondWith(302);
        UUID delivery = publishCapture(endpoint);

        attemptNow(delivery, 1);

        assertThat(attempts(endpoint.client().get("/api/v1/webhook-deliveries/" + delivery)).getFirst())
                .containsEntry("responseStatus", 302).containsEntry("outcome", "FAILURE");
    }

    @Test
    void disablingAnEndpointKillsItsPendingDeliveriesWithoutCallingIt() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        UUID delivery = publishCapture(endpoint);
        assertThat(deleteEndpoint(endpoint)).isEqualTo(204);

        attemptNow(delivery, 1);

        assertThat(receiver.received()).isEmpty();
        var details = endpoint.client().get("/api/v1/webhook-deliveries/" + delivery);
        assertThat(details.body()).containsEntry("status", "DEAD").containsEntry("lastError", "Endpoint is disabled");
        assertThat(endpoint.client().post("/api/v1/webhook-deliveries/" + delivery + "/retry", null).code())
                .isEqualTo("WEBHOOK_ENDPOINT_DISABLED");
    }

    @Test
    void onlyDeadDeliveriesCanBeReplayed() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        UUID delivery = publishCapture(endpoint);
        attemptNow(delivery, 1);

        var retry = endpoint.client().post("/api/v1/webhook-deliveries/" + delivery + "/retry", null);

        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.code()).isEqualTo("WEBHOOK_DELIVERY_NOT_DEAD");
    }

    @Test
    void aCrashedDispatcherLeavesTheDeliveryToBePickedUpAfterItsLease() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        UUID delivery = publishCapture(endpoint);
        // As left behind by a dispatcher that claimed the row and died: attempt counted, no result.
        jdbc.sql("UPDATE webhook_deliveries SET attempts = 1, next_attempt_at = now() + interval '1 minute' WHERE id = ?")
                .param(delivery).update();

        assertThat(dispatcher.dispatchDue()).as("the lease protects it for now").isZero();

        makeDue(delivery);
        dispatcher.dispatchDue();
        awaitAttempts(delivery, 1); // one row: the crashed attempt 1 never recorded a result

        assertThat(receiver.received()).singleElement().satisfies(r -> assertThat(r.attempt()).isEqualTo(2));
        assertThat(attempts(endpoint.client().get("/api/v1/webhook-deliveries/" + delivery)))
                .extracting(a -> a.get("attemptNumber")).as("the gap shows that attempt 1 died").containsExactly(2);
        assertThat(endpoint.client().get("/api/v1/webhook-deliveries/" + delivery).body())
                .containsEntry("status", "DELIVERED");
    }

    // ---- fan-out -----------------------------------------------------------------------------

    @Test
    void anEventGoesOnlyToEndpointsSubscribedToItsType() {
        UUID merchant = newWallet("EUR");
        Endpoint captures = registerEndpoint(merchant, "payment.captured");
        Endpoint alsoCaptures = registerEndpoint(merchant, "payment.captured", "payment.refunded");
        Endpoint refundsOnly = registerEndpoint(merchant, "payment.refunded");

        fanOut.handle(capture(UUID.randomUUID(), UUID.randomUUID(), merchant));

        assertThat(deliveriesOf(captures)).hasSize(1);
        assertThat(deliveriesOf(alsoCaptures)).hasSize(1);
        assertThat(deliveriesOf(refundsOnly)).isEmpty();
    }

    @Test
    void bothPartiesToAPaymentAreNotified() {
        UUID payer = newWallet("EUR");
        UUID merchant = newWallet("EUR");
        Endpoint payerEndpoint = registerEndpoint(payer, "payment.captured");
        Endpoint merchantEndpoint = registerEndpoint(merchant, "payment.captured");

        fanOut.handle(new PaymentCapturedEvent(UUID.randomUUID(), UUID.randomUUID(), payer, merchant,
                new BigDecimal("25.0000"), Currency.getInstance("EUR"), Instant.now()));

        assertThat(deliveriesOf(payerEndpoint)).hasSize(1);
        assertThat(deliveriesOf(merchantEndpoint)).hasSize(1);
    }

    @Test
    void redeliveredEventsDoNotFanOutTwice() {
        Endpoint endpoint = registerEndpoint("account.frozen");
        var event = new AccountFrozenEvent(UUID.randomUUID(), endpoint.accountId(), ownerOf(endpoint.accountId()),
                Instant.now());

        fanOut.handle(event);
        fanOut.handle(event);

        assertThat(deliveriesOf(endpoint)).hasSize(1);
    }

    // ---- concurrency -------------------------------------------------------------------------

    @Test
    void concurrentDispatchersNeverSendTheSameDeliveryTwice() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        int events = 200;
        for (int i = 0; i < events; i++) {
            fanOut.handle(capture(UUID.randomUUID(), UUID.randomUUID(), endpoint.accountId()));
        }

        // Eight "instances" polling at once, as many times as it takes.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Concurrently.run(8, i -> dispatcher.dispatchDue());
            assertThat(receiver.received()).hasSizeGreaterThanOrEqualTo(events);
        });
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.sql("""
                        SELECT count(*) FROM webhook_deliveries d WHERE d.endpoint_id = ? AND d.status = 'DELIVERED'""")
                .param(endpoint.id()).query(Long.class).single()).isEqualTo(events));

        assertThat(receiver.received()).hasSize(events);
        assertThat(receiver.received()).extracting(WebhookReceiverStub.Received::deliveryId).doesNotHaveDuplicates();
    }

    // ---- storage -----------------------------------------------------------------------------

    @Test
    void attemptHistoryCannotBeRewritten() {
        Endpoint endpoint = registerEndpoint("payment.captured");
        UUID delivery = publishCapture(endpoint);
        attemptNow(delivery, 1);

        assertThatThrownBy(() -> jdbc.sql("UPDATE webhook_delivery_attempts SET outcome = 'FAILURE' WHERE delivery_id = ?")
                .param(delivery).update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM webhook_delivery_attempts WHERE delivery_id = ?")
                .param(delivery).update()).rootCause().hasMessageContaining("append-only");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private record Endpoint(UUID id, UUID accountId, String secret, ApiClient client) {
    }

    private Endpoint registerEndpoint(String... eventTypes) {
        return registerEndpoint(newWallet("EUR"), eventTypes);
    }

    private Endpoint registerEndpoint(UUID account, String... eventTypes) {
        ApiClient owner = asOwnerOf(account);
        var response = owner.post("/api/v1/webhook-endpoints", Map.of(
                "accountId", account, "url", receiver.url(), "eventTypes", List.of(eventTypes)));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return new Endpoint(UUID.fromString(response.string("id")), account, response.string("secret"), owner);
    }

    private int deleteEndpoint(Endpoint endpoint) {
        return endpoint.client().delete("/api/v1/webhook-endpoints/" + endpoint.id()).status();
    }

    private UUID publishCapture(Endpoint endpoint) {
        return publish(endpoint, UUID.randomUUID(), UUID.randomUUID());
    }

    private UUID publish(Endpoint endpoint, UUID eventId, UUID paymentId) {
        fanOut.handle(capture(eventId, paymentId, endpoint.accountId()));
        return jdbc.sql("SELECT id FROM webhook_deliveries WHERE endpoint_id = ? AND event_id = ?")
                .params(endpoint.id(), eventId).query(UUID.class).single();
    }

    private static PaymentCapturedEvent capture(UUID eventId, UUID paymentId, UUID merchant) {
        return new PaymentCapturedEvent(eventId, paymentId, UUID.randomUUID(), merchant, new BigDecimal("25.0000"),
                Currency.getInstance("EUR"), Instant.now());
    }

    private void attemptNow(UUID delivery, int expectedAttempt) {
        makeDue(delivery);
        dispatcher.dispatchDue();
        awaitAttempts(delivery, expectedAttempt);
    }

    private void makeDue(UUID delivery) {
        jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ? AND status = 'PENDING'")
                .param(delivery).update();
    }

    private void awaitAttempts(UUID delivery, int count) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.sql(
                        "SELECT count(*) FROM webhook_delivery_attempts WHERE delivery_id = ?")
                .param(delivery).query(Long.class).single()).isEqualTo(count));
    }

    /** The wait is measured from the end of the attempt to the stored next_attempt_at. */
    private void assertNextAttemptAfter(UUID delivery, int attempt, Duration expected) {
        var row = jdbc.sql("""
                        SELECT d.next_attempt_at, a.started_at, a.duration_ms
                        FROM webhook_deliveries d JOIN webhook_delivery_attempts a ON a.delivery_id = d.id
                        WHERE d.id = ? AND a.attempt_number = ?""")
                .params(delivery, attempt).query().singleRow();
        Instant finished = ((java.sql.Timestamp) row.get("started_at")).toInstant()
                .plusMillis(((Number) row.get("duration_ms")).longValue());
        Instant next = ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant();
        assertThat(Duration.between(finished, next)).isBetween(expected.minusSeconds(1), expected.plusSeconds(1));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attempts(ApiClient.Response delivery) {
        return (List<Map<String, Object>>) delivery.body().get("attempts");
    }

    private List<OffsetDateTime> deliveriesOf(Endpoint endpoint) {
        return jdbc.sql("SELECT created_at FROM webhook_deliveries WHERE endpoint_id = ?").param(endpoint.id())
                .query(OffsetDateTime.class).list();
    }
}
