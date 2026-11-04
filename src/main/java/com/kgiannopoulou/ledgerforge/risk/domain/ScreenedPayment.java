package com.kgiannopoulou.ledgerforge.risk.domain;

import com.kgiannopoulou.ledgerforge.shared.money.Money;

import java.util.UUID;

/** A payment attempt with its verdict. {@code degraded}: Redis-based rules could not run. */
public record ScreenedPayment(UUID payerAccountId, UUID merchantAccountId, Money amount, RiskAssessment assessment,
                              boolean degraded) {

    public RiskOutcome outcome() {
        return assessment.outcome();
    }
}
