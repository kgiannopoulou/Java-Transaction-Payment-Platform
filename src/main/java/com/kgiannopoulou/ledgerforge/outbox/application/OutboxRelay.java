package com.kgiannopoulou.ledgerforge.outbox.application;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxMessage;
import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Moves committed events from the outbox table to the broker.
 *
 * <pre>
 *   BEGIN
 *     SELECT ... WHERE published_at IS NULL AND next_attempt_at <= now
 *       ORDER BY sequence_number LIMIT n FOR UPDATE SKIP LOCKED
 *     for each row: send to Kafka, wait for the ack, mark published
 *   COMMIT
 * </pre>
 *
 * <p>Delivery is <b>at-least-once</b>: if the process dies after Kafka acknowledged a message
 * but before the "published" mark commits, the row is sent again on the next run. That is why
 * every event carries an {@code eventId} and every consumer deduplicates on it.
 *
 * <p>On a failed send the message gets an exponential backoff and the batch stops. Later events
 * of the same aggregate wait for it (the store never returns them first); events of other
 * aggregates continue on the next run, so one stuck event does not stall the whole outbox.
 *
 * <p>Not a Spring bean itself (see {@code OutboxRelayScheduler}), which keeps it trivially
 * testable with a fake store and transport.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxStore store;
    private final OutboxTransport transport;
    private final TransactionOperations transactions;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxStore store, OutboxTransport transport, TransactionOperations transactions,
                       OutboxProperties properties, Clock clock) {
        this.store = store;
        this.transport = transport;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Publishes everything that is due, batch by batch. Returns the number of messages sent. */
    public int publishPending() {
        int total = 0;
        while (true) {
            BatchResult result = transactions.execute(status -> publishBatch());
            if (result == null) {
                return total;
            }
            total += result.published();
            // Loop until nothing is left: an aggregate's next event only becomes selectable once the
            // previous one is marked published, so a short batch does not mean the backlog is empty.
            if (result.failed() || result.published() == 0) {
                return total;
            }
        }
    }

    private BatchResult publishBatch() {
        List<OutboxMessage> batch = store.lockNextBatch(clock.instant(), properties.batchSize());
        int published = 0;
        for (OutboxMessage message : batch) {
            try {
                transport.send(message);
            } catch (RuntimeException e) {
                int attempt = message.attempts() + 1;
                Instant retryAt = clock.instant().plus(backoff(attempt));
                store.markFailed(message.id(), retryAt, truncate(String.valueOf(e.getMessage())));
                log.warn("Publishing outbox event {} ({}) failed on attempt {}; retrying at {}",
                        message.id(), message.eventType(), attempt, retryAt, e);
                return new BatchResult(published, true);
            }
            store.markPublished(message.id(), clock.instant());
            published++;
        }
        if (published > 0) {
            log.debug("Published {} outbox event(s)", published);
        }
        return new BatchResult(published, false);
    }

    /** initialBackoff * 2^(attempt-1), capped at maxBackoff. */
    Duration backoff(int attempt) {
        Duration delay = properties.initialBackoff();
        for (int i = 1; i < attempt && delay.compareTo(properties.maxBackoff()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
    }

    /** Deletes published rows older than the retention period. */
    public int purgePublished() {
        Integer deleted = transactions.execute(
                status -> store.deletePublishedBefore(clock.instant().minus(properties.retention())));
        return deleted == null ? 0 : deleted;
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private record BatchResult(int published, boolean failed) {
    }
}
