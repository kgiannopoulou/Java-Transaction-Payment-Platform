package com.kgiannopoulou.ledgerforge.messaging.application;

import com.kgiannopoulou.ledgerforge.messaging.domain.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Makes consumers idempotent. A consumer calls {@link #firstDelivery} at the start of its own
 * database transaction and does its work only if it returns {@code true}:
 *
 * <pre>
 *   BEGIN
 *     INSERT INTO processed_events (consumer, event_id) ... ON CONFLICT DO NOTHING
 *     -- 0 rows inserted -> duplicate delivery -> skip
 *     -- 1 row inserted  -> do the side effects
 *   COMMIT
 * </pre>
 *
 * Marker and side effects commit together. If processing fails, both roll back and the retry
 * starts from a clean slate. Two concurrent deliveries of the same event serialise on the
 * primary key, so exactly one of them does the work.
 */
@Service
public class ProcessedEventLog {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventLog.class);

    private final ProcessedEventRepository repository;
    private final Clock clock;
    private final MeterRegistry meters;

    public ProcessedEventLog(ProcessedEventRepository repository, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String consumer, UUID eventId) {
        boolean first = repository.insertIfAbsent(consumer, eventId, clock.instant());
        if (!first) {
            log.info("Skipping duplicate delivery of event {} to {}", eventId, consumer);
            meters.counter("ledgerforge.events.duplicates", "consumer", consumer).increment();
        }
        return first;
    }
}
