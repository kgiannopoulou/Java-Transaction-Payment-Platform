package com.kgiannopoulou.ledgerforge.outbox.application;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Runs the {@link OutboxRelay} on a fixed delay. Disable with
 * {@code ledgerforge.outbox.relay.enabled=false} (e.g. in tests that do not start Kafka); events
 * then simply accumulate in the table and are sent once a relay runs again.
 */
@Component
@ConditionalOnProperty(prefix = "ledgerforge.outbox.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxStore store, OutboxTransport transport, TransactionTemplate transactions,
                                OutboxProperties properties, Clock clock) {
        this.relay = new OutboxRelay(store, transport, transactions, properties, clock);
    }

    @Scheduled(fixedDelayString = "${ledgerforge.outbox.relay.poll-interval-ms:500}")
    public void relay() {
        try {
            relay.publishPending();
        } catch (RuntimeException e) {
            // e.g. database unavailable; the next run tries again
            log.error("Outbox relay run failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${ledgerforge.outbox.relay.purge-interval-ms:3600000}")
    public void purge() {
        int deleted = relay.purgePublished();
        if (deleted > 0) {
            log.info("Purged {} published outbox event(s)", deleted);
        }
    }
}
