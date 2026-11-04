package com.kgiannopoulou.ledgerforge.risk.application.rules;

import com.kgiannopoulou.ledgerforge.risk.application.RiskProperties;
import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** A large payment from a young account deserves a human look. Established accounts are not held. */
@Component
@Order(3)
public class LargeTransactionRule implements RiskRule {

    public static final String NAME = "LARGE_TRANSACTION";

    private final RiskProperties.LargeTransaction config;

    public LargeTransactionRule(RiskProperties properties) {
        this.config = properties.rules().largeTransaction();
    }

    @Override
    public RiskDecision evaluate(PaymentContext context) {
        boolean large = context.amount().amount().compareTo(config.threshold()) >= 0;
        Duration age = Duration.between(context.payerOpenedAt(), context.now());
        if (large && age.compareTo(config.newAccountAge()) < 0) {
            return RiskDecision.review(NAME, context.amount() + " from an account opened " + age.toDays() + " day(s) ago");
        }
        return RiskDecision.allow();
    }
}
