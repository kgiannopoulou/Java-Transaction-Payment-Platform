package com.kgiannopoulou.ledgerforge.events;

import com.kgiannopoulou.ledgerforge.risk.application.RiskAssessmentService;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEvent;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventCodec;
import com.kgiannopoulou.ledgerforge.shared.event.PaymentCapturedEvent;
import com.kgiannopoulou.ledgerforge.support.KafkaIntegrationTest;
import com.kgiannopoulou.ledgerforge.support.RecordingWebhookSender;
import com.kgiannopoulou.ledgerforge.support.TopicProbe;
import com.kgiannopoulou.ledgerforge.webhook.application.WebhookNotificationService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka is at-least-once: consumers must survive duplicates, transient failures and poison
 * records. These tests publish straight to the topic (bypassing the outbox) to stage exactly
 * those situations.
 */
class ConsumerFailureHandlingKafkaTest extends KafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    IntegrationEventCodec codec;

    @Autowired
    RecordingWebhookSender webhookSender;

    @Autowired
    JdbcClient jdbc;

    @Test
    void duplicateDeliveriesHaveTheirEffectExactlyOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentCapturedEvent captured = largeCapture(paymentId);
        PaymentCapturedEvent marker = largeCapture(paymentId); // same key, so it is consumed after both copies

        send(captured);
        send(captured); // the duplicate: e.g. relay crashed after Kafka's ack, before marking the row
        send(marker);

        awaitProcessedByBothConsumers(marker.eventId());

        assertThat(webhookSender.attempts(captured.eventId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE event_id = ?", captured.eventId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk_alerts WHERE event_id = ?", captured.eventId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", captured.eventId())).isEqualTo(2);
    }

    @Test
    void transientFailureIsRetriedWithBackoffAndSucceedsOnce() throws Exception {
        PaymentCapturedEvent captured = largeCapture(UUID.randomUUID());
        webhookSender.failTimes(captured.eventId(), 2); // merchant endpoint down for two attempts

        send(captured);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE event_id = ?", captured.eventId()))
                        .isEqualTo(1));
        assertThat(webhookSender.attempts(captured.eventId())).isEqualTo(3);
        assertThat(webhookSender.successes(captured.eventId())).isEqualTo(1);
    }

    @Test
    void permanentFailureLandsOnTheDeadLetterTopicWithoutSideEffects() throws Exception {
        PaymentCapturedEvent captured = largeCapture(UUID.randomUUID());
        webhookSender.failTimes(captured.eventId(), RecordingWebhookSender.FOREVER);

        send(captured);

        var dead = TopicProbe.await(KAFKA.getBootstrapServers(), EventTopics.deadLetterTopic(EventTopics.PAYMENTS),
                r -> TopicProbe.header(r, EventTopics.HEADER_EVENT_ID).filter(captured.eventId().toString()::equals)
                        .isPresent(), TIMEOUT);

        assertThat(dead.value()).isEqualTo(codec.encode(captured)); // original payload, ready for replay
        assertThat(TopicProbe.header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).contains(EventTopics.PAYMENTS);
        assertThat(webhookSender.attempts(captured.eventId())).isEqualTo(4); // 1 + 3 retries
        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE event_id = ?", captured.eventId())).isZero();
        assertThat(count("SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                WebhookNotificationService.CONSUMER, captured.eventId())).isZero();

        // Consumer groups are independent: the broken webhook endpoint did not stop risk scoring.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM risk_alerts WHERE event_id = ?", captured.eventId())).isEqualTo(1));
    }

    @Test
    void malformedRecordsSkipRetriesAndGoStraightToTheDeadLetterTopic() throws Exception {
        String key = UUID.randomUUID().toString();
        var poison = new ProducerRecord<String, String>(EventTopics.PAYMENTS, key, "{not json");
        poison.headers().add(EventTopics.HEADER_EVENT_TYPE, "PaymentCaptured".getBytes(StandardCharsets.UTF_8));
        kafka.send(poison).get();

        var dead = TopicProbe.await(KAFKA.getBootstrapServers(), EventTopics.deadLetterTopic(EventTopics.PAYMENTS),
                r -> key.equals(r.key()), TIMEOUT);

        String exception = TopicProbe.header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN).orElse("")
                + TopicProbe.header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN).orElse("");
        assertThat(exception).contains("MalformedEventException");
        assertThat(dead.value()).isEqualTo("{not json");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static PaymentCapturedEvent largeCapture(UUID paymentId) {
        return new PaymentCapturedEvent(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("25000.00"), Currency.getInstance("EUR"), Instant.now());
    }

    private void send(IntegrationEvent event) throws Exception {
        var record = new ProducerRecord<String, String>(EventTopics.topicFor(event), event.aggregateId().toString(),
                codec.encode(event));
        record.headers()
                .add(EventTopics.HEADER_EVENT_ID, event.eventId().toString().getBytes(StandardCharsets.UTF_8))
                .add(EventTopics.HEADER_EVENT_TYPE, event.type().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    private void awaitProcessedByBothConsumers(UUID eventId) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.sql(
                        "SELECT consumer FROM processed_events WHERE event_id = ?").param(eventId)
                .query(String.class).list())
                .containsExactlyInAnyOrder(RiskAssessmentService.CONSUMER, WebhookNotificationService.CONSUMER));
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
