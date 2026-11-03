package com.kgiannopoulou.ledgerforge.messaging.domain;

import java.time.Instant;
import java.util.UUID;

/** The consumer-side "inbox": which events each consumer has already handled. */
public interface ProcessedEventRepository {

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING}. Returns {@code true} if the row was inserted,
     * i.e. this consumer has not processed the event before.
     */
    boolean insertIfAbsent(String consumer, UUID eventId, Instant processedAt);
}
