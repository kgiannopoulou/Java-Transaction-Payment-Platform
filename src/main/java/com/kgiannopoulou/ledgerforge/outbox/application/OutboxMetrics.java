package com.kgiannopoulou.ledgerforge.outbox.application;

import com.kgiannopoulou.ledgerforge.outbox.domain.OutboxStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code outbox_pending_events}: the backlog, read from the table at each scrape. Registered whether
 * or not this instance runs the relay, because a backlog with no relay is exactly what to see.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(OutboxStore store, MeterRegistry meters) {
        Gauge.builder("outbox.pending.events", store, OutboxStore::countPending)
                .description("Outbox events not yet published to Kafka")
                .register(meters);
    }
}
