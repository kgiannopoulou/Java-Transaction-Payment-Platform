package com.kgiannopoulou.ledgerforge.risk.domain;

/**
 * One independent risk check. Adding a rule means adding a Spring bean that implements this; the
 * engine receives every implementation by injection and needs no change.
 *
 * <p>Rules are pure functions of {@link PaymentContext}. Anything slow or stateful is gathered
 * once, up front, which keeps them trivial to test.
 */
public interface RiskRule {

    RiskDecision evaluate(PaymentContext context);
}
