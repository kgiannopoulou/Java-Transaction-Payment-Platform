package com.kgiannopoulou.ledgerforge.risk.domain;

import org.jspecify.annotations.Nullable;

/** One rule's verdict. {@code rule} and {@code reason} are null for {@link RiskOutcome#ALLOW}. */
public record RiskDecision(RiskOutcome outcome, @Nullable String rule, @Nullable String reason) {

    private static final RiskDecision ALLOW = new RiskDecision(RiskOutcome.ALLOW, null, null);

    public static RiskDecision allow() {
        return ALLOW;
    }

    public static RiskDecision review(String rule, String reason) {
        return new RiskDecision(RiskOutcome.REVIEW, rule, reason);
    }

    public static RiskDecision block(String rule, String reason) {
        return new RiskDecision(RiskOutcome.BLOCK, rule, reason);
    }
}
