package com.kgiannopoulou.ledgerforge.events;

import com.kgiannopoulou.ledgerforge.risk.application.RiskAssessmentService;
import com.kgiannopoulou.ledgerforge.security.domain.Role;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventCodec;
import com.kgiannopoulou.ledgerforge.shared.event.TransferCompletedEvent;
import com.kgiannopoulou.ledgerforge.support.KafkaIntegrationTest;
import com.kgiannopoulou.ledgerforge.support.TopicProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end: REST call → business transaction (+ outbox row) → relay → Kafka → consumers.
 */
class EventPipelineKafkaTest extends KafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IntegrationEventCodec codec;

    @Test
    void completedTransferIsRelayedToKafkaKeyedByTransferWithTypeHeaders() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        String transferId = transfer(alice, bob, "12.50", "EUR").string("id");

        var record = TopicProbe.await(KAFKA.getBootstrapServers(), EventTopics.TRANSFERS,
                r -> transferId.equals(r.key()), TIMEOUT);

        assertThat(TopicProbe.header(record, EventTopics.HEADER_EVENT_TYPE)).contains("TransferCompleted");
        var event = (TransferCompletedEvent) codec.decode("TransferCompleted", record.value());
        assertThat(event.transferId()).hasToString(transferId);
        assertThat(event.sourceAccountId()).isEqualTo(alice);
        assertThat(event.amount()).isEqualByComparingTo("12.50");
        assertThat(TopicProbe.header(record, EventTopics.HEADER_EVENT_ID)).contains(event.eventId().toString());

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.sql(
                        "SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?")
                .param(UUID.fromString(transferId)).query(Boolean.class).single()).isTrue());
    }

    @Test
    void largeTransferRaisesExactlyOneRiskAlert() {
        UUID whale = newFundedWallet("EUR", "50000.00");
        UUID bob = newWallet("EUR");
        UUID transferId = UUID.fromString(transfer(whale, bob, "15000.00", "EUR").string("id"));
        transfer(whale, bob, "10.00", "EUR"); // below the threshold

        await().atMost(TIMEOUT).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> alerts = (List<Map<String, Object>>) as("sam", Role.SUPPORT)
                    .get("/api/v1/risk/alerts?accountId=" + whale).body().get("items");
            assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert)
                    .containsEntry("rule", RiskAssessmentService.LARGE_TRANSFER)
                    .containsEntry("referenceId", transferId.toString()));
        });
    }

    @Test
    void captureAndRefundNotifyTheMerchantOnceEach() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "30.00", "EUR");
        asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/capture", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/refund", Map.of("amount", "30.00", "currency", "EUR"));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.sql("""
                        SELECT event_type FROM webhook_deliveries WHERE payment_id = ? ORDER BY delivered_at""")
                .param(payment).query(String.class).list())
                .containsExactly("PaymentCaptured", "PaymentRefunded"));
        assertThat(jdbc.sql("SELECT DISTINCT merchant_account_id FROM webhook_deliveries WHERE payment_id = ?")
                .param(payment).query(UUID.class).list()).containsExactly(merchant);
    }
}
