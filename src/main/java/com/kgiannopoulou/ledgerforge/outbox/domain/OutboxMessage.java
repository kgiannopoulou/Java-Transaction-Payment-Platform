package com.kgiannopoulou.ledgerforge.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A serialized integration event waiting in (or read back from) the outbox table.
 *
 * @param id       the event id; also used as the Kafka {@code eventId} header
 * @param attempts failed publish attempts so far
 */
public record OutboxMessage(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String topic,
        String payload,
        Instant createdAt,
        int attempts) {

    public OutboxMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
