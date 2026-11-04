package com.kgiannopoulou.ledgerforge.risk.domain;

import java.util.List;

/**
 * The combined verdict: the harshest outcome, plus every rule that fired. All of them are kept,
 * because a reviewer wants the whole picture, not only the worst finding.
 */
public record RiskAssessment(RiskOutcome outcome, List<RiskDecision> triggered) {

    public RiskAssessment {
        triggered = List.copyOf(triggered);
    }

    public static RiskAssessment of(List<RiskDecision> decisions) {
        List<RiskDecision> triggered = decisions.stream().filter(d -> d.outcome() != RiskOutcome.ALLOW).toList();
        RiskOutcome worst = triggered.stream().map(RiskDecision::outcome)
                .max(Enum::compareTo).orElse(RiskOutcome.ALLOW);
        return new RiskAssessment(worst, triggered);
    }
}
