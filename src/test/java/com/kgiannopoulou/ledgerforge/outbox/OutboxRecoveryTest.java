package com.kgiannopoulou.ledgerforge.outbox;

import com.kgiannopoulou.ledgerforge.outbox.application.OutboxProperties;
import com.kgiannopoulou.ledgerforge.outbox.application.OutboxRelay;
import com.kgiannopoulou.ledgerforge.outbox.application.OutboxTransport;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import com.kgiannopoulou.ledgerforge.support.ApiTestSupport;
import com.kgiannopoulou.ledgerforge.support.Concurrently;
import com.kgiannopoulou.ledgerforge.support.PostgresContainers;
import com.kgiannopoulou.ledgerforge.support.RedisContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the outbox is for: staying correct while the broker is down or the relay dies. Real
 * PostgreSQL and real rows, with only the broker scripted. {@code OutboxRelayTest} covers the
 * ordering and backoff logic on its own.
 *
 * <p>Own database, so the live relay of the Kafka test context cannot take these rows.
 */
class OutboxRecoveryTest extends ApiTestSupport {

    private static final PostgreSQLContainer DATABASE = PostgresContainers.start();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, DATABASE);
        RedisContainers.register(registry, RedisContainers.SHARED);
    }

    @Autowired
    OutboxStore store;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    OutboxProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    private final ScriptedBroker broker = new ScriptedBroker();

    /** Stands in for the process dying: not an exception the relay is written to handle. */
    private static final class ProcessCrash extends Error {
    }

    private static final class ScriptedBroker implements OutboxTransport {

        final List<OutboxMessage> acknowledged = new CopyOnWriteArrayList<>();
        volatile boolean down;
        volatile boolean crashAfterNextAck;

        @Override
        public void send(OutboxMessage message) {
            if (down) {
                throw new IllegalStateException("broker unavailable");
            }
            acknowledged.add(message);
            if (crashAfterNextAck) {
                crashAfterNextAck = false;
                throw new ProcessCrash();
            }
        }

        List<UUID> acknowledgedAggregates() {
            return acknowledged.stream().map(OutboxMessage::aggregateId).toList();
        }
    }

    @BeforeEach
    void startFromAnEmptyQueue() {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL").update();
    }

    @Test
    void aBrokerOutageNeitherFailsPaymentsNorLosesEvents() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        startFromAnEmptyQueue();
        broker.down = true;

        List<UUID> transfers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var response = transfer(alice, bob, "1.00", "EUR");
            assertThat(response.status()).as("the API does not depend on the broker").isEqualTo(201);
            transfers.add(UUID.fromString(response.string("id")));
        }
        OutboxRelay relay = relay();
        assertThat(relay.publishPending()).isZero();
        assertThat(pending()).isEqualTo(5);

        broker.down = false;
        drain(relay);

        assertThat(pending()).isZero();
        assertThat(broker.acknowledgedAggregates()).containsExactlyElementsOf(transfers); // nothing lost, still in order
    }

    @Test
    void aCrashAfterTheBrokersAckSendsTheEventAgainButNeverLosesIt() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        startFromAnEmptyQueue();
        UUID transfer = UUID.fromString(transfer(alice, bob, "1.00", "EUR").string("id"));

        broker.crashAfterNextAck = true;
        assertThatThrownBy(relay()::publishPending).isInstanceOf(ProcessCrash.class);
        assertThat(pending()).as("the 'published' mark rolled back with the crash").isEqualTo(1);

        drain(relay()); // the restarted process

        assertThat(pending()).isZero();
        assertThat(broker.acknowledged).hasSize(2);
        assertThat(broker.acknowledgedAggregates()).containsOnly(transfer);
        assertThat(broker.acknowledged.get(0).id()).as("same eventId, so consumers can deduplicate")
                .isEqualTo(broker.acknowledged.get(1).id());
    }

    @Test
    void aRolledBackBusinessTransactionLeavesNothingToRecover() {
        UUID poor = newWallet("EUR");
        UUID bob = newWallet("EUR");
        startFromAnEmptyQueue();

        assertThat(transfer(poor, bob, "1.00", "EUR").status()).isEqualTo(422);

        assertThat(pending()).isZero();
    }

    @Test
    void concurrentRelaysShareTheWorkWithoutSendingAnythingTwice() {
        UUID alice = newFundedWallet("EUR", "100.00");
        UUID bob = newWallet("EUR");
        startFromAnEmptyQueue();
        for (int i = 0; i < 40; i++) {
            transfer(alice, bob, "1.00", "EUR");
        }

        Concurrently.run(4, i -> relay().publishPending());
        drain(relay());

        assertThat(broker.acknowledged).hasSize(40);
        assertThat(broker.acknowledged).extracting(OutboxMessage::id).doesNotHaveDuplicates();
    }

    private OutboxRelay relay() {
        return new OutboxRelay(store, broker, transactions, properties, clock);
    }

    /** Runs the relay until the queue is empty, skipping the real backoff delay. */
    private void drain(OutboxRelay relay) {
        for (int i = 0; i < 20 && pending() > 0; i++) {
            jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() WHERE published_at IS NULL").update();
            relay.publishPending();
        }
    }

    private long pending() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }
}
