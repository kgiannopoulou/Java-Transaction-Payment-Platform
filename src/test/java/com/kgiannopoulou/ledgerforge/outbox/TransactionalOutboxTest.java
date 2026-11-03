package com.kgiannopoulou.ledgerforge.outbox;

import com.kgiannopoulou.ledgerforge.shared.event.AccountFrozenEvent;
import com.kgiannopoulou.ledgerforge.shared.event.EventTopics;
import com.kgiannopoulou.ledgerforge.shared.event.IntegrationEventPublisher;
import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write side of the outbox, without Kafka: the event row is committed in the same database
 * transaction as the state change, or not at all. (Relaying to Kafka is covered by
 * {@code OutboxRelayTest} and the Kafka integration tests.)
 */
class TransactionalOutboxTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    IntegrationEventPublisher publisher;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void completedTransferCommitsExactlyOneEventWithIt() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");

        String transferId = transfer(alice, bob, "30.00", "EUR").string("id");

        List<Map<String, Object>> rows = outboxRowsFor(UUID.fromString(transferId));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("event_type", "TransferCompleted")
                    .containsEntry("topic", EventTopics.TRANSFERS)
                    .containsEntry("aggregate_type", "TRANSFER");
            Map<String, Object> payload = payload(row);
            assertThat(payload).containsEntry("transferId", transferId)
                    .containsEntry("sourceAccountId", alice.toString())
                    .containsEntry("destinationAccountId", bob.toString())
                    .containsEntry("currency", "EUR")
                    .containsKey("eventId");
            assertThat(payload.get("eventId")).isEqualTo(row.get("id").toString());
        });
    }

    @Test
    void rejectedTransferWritesNoEvent() {
        UUID alice = newFundedWallet("EUR", "10.00");
        UUID bob = newWallet("EUR");

        assertThat(transfer(alice, bob, "10.01", "EUR").status()).isEqualTo(422);

        long events = jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload ->> 'sourceAccountId' = ?")
                .param(alice.toString()).query(Long.class).single();
        assertThat(events).isZero();
    }

    @Test
    void paymentCaptureRefundAndAccountFreezeEmitEvents() {
        UUID payer = newFundedWallet("EUR", "100.00");
        UUID merchant = newWallet("EUR");
        UUID payment = newPayment(payer, merchant, "60.00", "EUR");
        asOwnerOf(payer).post("/api/v1/payments/" + payment + "/authorize", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/capture", null);
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/refund", Map.of("amount", "20.00", "currency", "EUR"));
        asOwnerOf(merchant).post("/api/v1/payments/" + payment + "/refund", Map.of("amount", "40.00", "currency", "EUR"));

        List<Map<String, Object>> rows = outboxRowsFor(payment);
        assertThat(rows).extracting(r -> r.get("event_type"))
                .containsExactly("PaymentCaptured", "PaymentRefunded", "PaymentRefunded"); // in commit order
        assertThat(payload(rows.get(1))).containsEntry("fullyRefunded", false);
        assertThat(payload(rows.get(2))).containsEntry("fullyRefunded", true);

        api.post("/api/v1/accounts/" + merchant + "/freeze", null);
        assertThat(outboxRowsFor(merchant)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("event_type", "AccountFrozen"));
    }

    @Test
    void publishingOutsideABusinessTransactionIsRejected() {
        var event = new AccountFrozenEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        // Propagation.MANDATORY: an event without a surrounding transaction would be a dual write.
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private List<Map<String, Object>> outboxRowsFor(UUID aggregateId) {
        return jdbc.sql("""
                        SELECT id, aggregate_type, event_type, topic, payload::text AS payload
                        FROM outbox_events WHERE aggregate_id = ? ORDER BY sequence_number""")
                .param(aggregateId).query().listOfRows();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> row) {
        return jsonMapper.readValue((String) row.get("payload"), Map.class);
    }
}
