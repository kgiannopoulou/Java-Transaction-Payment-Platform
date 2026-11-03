package com.kgiannopoulou.ledgerforge.outbox.application;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Relay logic against an in-memory store and a scriptable broker. */
class OutboxRelayTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryStore store = new InMemoryStore();
    private final ScriptedTransport broker = new ScriptedTransport();
    private final OutboxProperties properties =
            new OutboxProperties(2, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofDays(7), Duration.ofSeconds(5));
    private final OutboxRelay relay =
            new OutboxRelay(store, broker, TransactionOperations.withoutTransaction(), properties, clock);

    @Test
    void publishesEverythingDueInOrderAcrossBatches() {
        List<UUID> ids = List.of(store.add(), store.add(), store.add(), store.add(), store.add());

        assertThat(relay.publishPending()).isEqualTo(5);

        assertThat(broker.sent).containsExactlyElementsOf(ids);
        assertThat(store.pending()).isEmpty();
    }

    @Test
    void failedSendIsRetriedWithBackoffAndBlocksLaterEventsOfTheSameAggregate() {
        UUID payment = UUID.randomUUID();
        UUID first = store.add(payment);
        UUID second = store.add(payment);
        broker.failNext(first);

        assertThat(relay.publishPending()).isZero();
        assertThat(broker.sent).isEmpty(); // `second` was not sent ahead of `first`
        assertThat(store.rows.get(first).attempts).isEqualTo(1);
        assertThat(store.rows.get(first).nextAttemptAt).isEqualTo(clock.instant().plusSeconds(1));
        assertThat(store.rows.get(first).lastError).contains("broker unavailable");

        assertThat(relay.publishPending()).as("not due yet").isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(broker.sent).containsExactly(first, second);
    }

    @Test
    void aStuckEventDoesNotHoldUpOtherAggregates() {
        UUID stuck = store.add();
        UUID other = store.add();
        broker.failNext(stuck);

        relay.publishPending();             // stuck fails, batch stops
        assertThat(relay.publishPending()).isEqualTo(1);

        assertThat(broker.sent).containsExactly(other);
        assertThat(store.pending()).extracting(r -> r.message.id()).containsExactly(stuck);
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(relay.backoff(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(relay.backoff(50)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void publishedRowsArePurgedAfterRetention() {
        UUID old = store.add();
        relay.publishPending();
        clock.advance(Duration.ofDays(8));
        UUID recent = store.add();
        relay.publishPending();

        assertThat(relay.purgePublished()).isEqualTo(1);
        assertThat(store.rows).doesNotContainKey(old).containsKey(recent);
    }

    // ---- fakes -------------------------------------------------------------------------------

    private final class InMemoryStore implements OutboxStore {

        private final Map<UUID, Row> rows = new LinkedHashMap<>();
        private long sequence;

        UUID add() {
            return add(UUID.randomUUID());
        }

        UUID add(UUID aggregateId) {
            UUID id = UUID.randomUUID();
            append(new OutboxMessage(id, "TRANSFER", aggregateId, "TransferCompleted", "topic", "{}",
                    clock.instant(), 0));
            return id;
        }

        List<Row> pending() {
            return rows.values().stream().filter(r -> r.publishedAt == null).toList();
        }

        @Override
        public void append(OutboxMessage message) {
            rows.put(message.id(), new Row(message, ++sequence, message.createdAt()));
        }

        @Override
        public List<OutboxMessage> lockNextBatch(Instant now, int limit) {
            return pending().stream()
                    .filter(r -> !r.nextAttemptAt.isAfter(now))
                    .filter(r -> pending().stream().noneMatch(earlier ->
                            earlier.message.aggregateId().equals(r.message.aggregateId()) && earlier.sequence < r.sequence))
                    .sorted(Comparator.comparingLong(r -> r.sequence))
                    .limit(limit)
                    .map(r -> new OutboxMessage(r.message.id(), r.message.aggregateType(), r.message.aggregateId(),
                            r.message.eventType(), r.message.topic(), r.message.payload(), r.message.createdAt(),
                            r.attempts))
                    .toList();
        }

        @Override
        public void markPublished(UUID id, Instant publishedAt) {
            rows.get(id).publishedAt = publishedAt;
        }

        @Override
        public void markFailed(UUID id, Instant nextAttemptAt, String error) {
            Row row = rows.get(id);
            row.attempts++;
            row.nextAttemptAt = nextAttemptAt;
            row.lastError = error;
        }

        @Override
        public int deletePublishedBefore(Instant cutoff) {
            List<UUID> doomed = rows.values().stream()
                    .filter(r -> r.publishedAt != null && r.publishedAt.isBefore(cutoff))
                    .map(r -> r.message.id()).toList();
            doomed.forEach(rows::remove);
            return doomed.size();
        }

        @Override
        public long countPending() {
            return pending().size();
        }
    }

    private static final class Row {
        final OutboxMessage message;
        final long sequence;
        Instant nextAttemptAt;
        Instant publishedAt;
        int attempts;
        String lastError;

        Row(OutboxMessage message, long sequence, Instant nextAttemptAt) {
            this.message = message;
            this.sequence = sequence;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private static final class ScriptedTransport implements OutboxTransport {

        final List<UUID> sent = new ArrayList<>();
        final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();

        void failNext(UUID id) {
            failOnce.add(id);
        }

        @Override
        public void send(OutboxMessage message) {
            if (failOnce.remove(message.id())) {
                throw new IllegalStateException("broker unavailable");
            }
            sent.add(message.id());
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
