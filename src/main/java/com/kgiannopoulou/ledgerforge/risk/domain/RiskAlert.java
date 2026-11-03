package com.kgiannopoulou.ledgerforge.risk.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** A transaction the risk engine wants a human to look at. */
public record RiskAlert(
        UUID id,
        UUID eventId,
        String rule,
        UUID accountId,
        String referenceType,
        UUID referenceId,
        BigDecimal amount,
        Currency currency,
        Instant createdAt) {
}
