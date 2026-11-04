package com.kgiannopoulou.ledgerforge.risk.domain;

import com.kgiannopoulou.ledgerforge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything rules may look at.
 *
 * @param recentAttempts   payment attempts by the payer in the velocity window, this one included
 * @param recentFailures   failed payments by the payer in the failure window
 * @param signalsAvailable {@code false} if Redis was down, so the two counts above are unknown
 */
public record PaymentContext(
        UUID payerAccountId,
        UUID merchantAccountId,
        Money amount,
        boolean payerFrozen,
        Instant payerOpenedAt,
        Instant now,
        long recentAttempts,
        long recentFailures,
        boolean signalsAvailable) {
}
