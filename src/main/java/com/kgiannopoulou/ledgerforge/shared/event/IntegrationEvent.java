package com.kgiannopoulou.ledgerforge.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact other systems may react to, published through the transactional outbox to Kafka.
 *
 * <p>Sealed, so every consumer can {@code switch} over the complete set of events and the
 * compiler flags consumers that forget a new one. Events are immutable records: once published,
 * an event is a historical fact and its schema is a public contract (topics are versioned
 * {@code .v1}; incompatible changes get a new topic).
 */
public sealed interface IntegrationEvent
        permits PaymentCapturedEvent, PaymentRefundedEvent, TransferCompletedEvent, AccountFrozenEvent {

    /** Unique per event. Consumers deduplicate on it, because Kafka delivery is at-least-once. */
    UUID eventId();

    Instant occurredAt();

    /** The entity the event is about. Used as the Kafka key, so its events stay ordered. */
    UUID aggregateId();

    /** Stable wire name, e.g. {@code PaymentCaptured}; sent as the {@code eventType} header. */
    default String type() {
        return typeOf(getClass());
    }

    static String typeOf(Class<? extends IntegrationEvent> eventClass) {
        String name = eventClass.getSimpleName();
        return name.endsWith("Event") ? name.substring(0, name.length() - "Event".length()) : name;
    }
}
