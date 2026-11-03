package com.kgiannopoulou.ledgerforge.shared.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A wallet was frozen and can no longer move money. */
public record AccountFrozenEvent(
        UUID eventId,
        UUID accountId,
        UUID customerId,
        Instant occurredAt) implements IntegrationEvent {

    public AccountFrozenEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    public UUID aggregateId() {
        return accountId;
    }
}
