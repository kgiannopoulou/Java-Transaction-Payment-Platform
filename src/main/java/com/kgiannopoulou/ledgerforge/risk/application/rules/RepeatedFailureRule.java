package com.kgiannopoulou.ledgerforge.risk.application.rules;

import com.kgiannopoulou.ledgerforge.risk.application.RiskProperties;
import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** A run of failures can mean probing, or just a customer in trouble. Either way, a person decides. */
@Component
@Order(4)
public class RepeatedFailureRule implements RiskRule {

    public static final String NAME = "REPEATED_FAILURE";

    private final RiskProperties.RepeatedFailure config;

    public RepeatedFailureRule(RiskProperties properties) {
        this.config = properties.rules().repeatedFailure();
    }

    @Override
    public RiskDecision evaluate(PaymentContext context) {
        if (context.signalsAvailable() && context.recentFailures() >= config.maxFailures()) {
            return RiskDecision.review(NAME, context.recentFailures() + " failed payments within "
                    + config.window().toMinutes() + " minutes");
        }
        return RiskDecision.allow();
    }
}
