package com.kgiannopoulou.ledgerforge.risk.application.rules;

import com.kgiannopoulou.ledgerforge.risk.application.RiskProperties;
import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Too many attempts in a short time: card testing or a runaway client. */
@Component
@Order(2)
public class VelocityRule implements RiskRule {

    public static final String NAME = "VELOCITY";

    private final RiskProperties.Velocity config;

    public VelocityRule(RiskProperties properties) {
        this.config = properties.rules().velocity();
    }

    @Override
    public RiskDecision evaluate(PaymentContext context) {
        if (context.signalsAvailable() && context.recentAttempts() >= config.maxAttempts()) {
            return RiskDecision.block(NAME, context.recentAttempts() + " payment attempts within "
                    + config.window().toSeconds() + " seconds");
        }
        return RiskDecision.allow();
    }
}
