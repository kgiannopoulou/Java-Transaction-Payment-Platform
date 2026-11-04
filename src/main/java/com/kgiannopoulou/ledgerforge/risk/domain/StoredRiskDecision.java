package com.kgiannopoulou.ledgerforge.risk.domain;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** The permanent record of a screening. {@code paymentId} is null for blocked attempts. */
public record StoredRiskDecision(
        UUID id,
        @Nullable UUID paymentId,
        UUID payerAccountId,
        UUID merchantAccountId,
        BigDecimal amount,
        Currency currency,
        RiskOutcome outcome,
        List<RiskDecision> reasons,
        boolean degraded,
        Instant createdAt) {
}
