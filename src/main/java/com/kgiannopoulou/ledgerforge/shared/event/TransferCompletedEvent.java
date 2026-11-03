package com.kgiannopoulou.ledgerforge.shared.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** Money moved between two wallets; the ledger transaction is committed. */
public record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        Currency currency,
        Instant occurredAt) implements IntegrationEvent {

    public TransferCompletedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    public UUID aggregateId() {
        return transferId;
    }
}
