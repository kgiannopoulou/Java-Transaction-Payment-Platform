package com.kgiannopoulou.ledgerforge.shared.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** The payer's held funds were moved to the merchant. */
public record PaymentCapturedEvent(
        UUID eventId,
        UUID paymentId,
        UUID payerAccountId,
        UUID merchantAccountId,
        BigDecimal amount,
        Currency currency,
        Instant occurredAt) implements IntegrationEvent {

    public PaymentCapturedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(payerAccountId, "payerAccountId");
        Objects.requireNonNull(merchantAccountId, "merchantAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    public UUID aggregateId() {
        return paymentId;
    }
}
