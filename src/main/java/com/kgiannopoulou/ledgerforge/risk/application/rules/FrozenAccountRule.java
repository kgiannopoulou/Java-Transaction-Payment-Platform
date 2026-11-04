package com.kgiannopoulou.ledgerforge.risk.application.rules;

import com.kgiannopoulou.ledgerforge.risk.domain.PaymentContext;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskDecision;
import com.kgiannopoulou.ledgerforge.risk.domain.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** A frozen payer cannot start a payment. */
@Component
@Order(1)
public class FrozenAccountRule implements RiskRule {

    public static final String NAME = "FROZEN_ACCOUNT";

    @Override
    public RiskDecision evaluate(PaymentContext context) {
        return context.payerFrozen()
                ? RiskDecision.block(NAME, "Payer account is frozen")
                : RiskDecision.allow();
    }
}
