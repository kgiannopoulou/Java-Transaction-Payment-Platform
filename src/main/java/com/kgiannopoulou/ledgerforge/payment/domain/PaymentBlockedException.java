package com.kgiannopoulou.ledgerforge.payment.domain;

import com.kgiannopoulou.ledgerforge.risk.domain.ScreenedPayment;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

/**
 * The risk engine refused the payment. The message is deliberately generic: saying which rule
 * fired would tell a fraudster exactly what to change. The reasons are in {@code risk_decisions}.
 */
public class PaymentBlockedException extends BusinessRuleViolationException {

    private final transient ScreenedPayment screened;

    public PaymentBlockedException(ScreenedPayment screened) {
        super("PAYMENT_BLOCKED", "This payment cannot be processed");
        this.screened = screened;
    }

    public ScreenedPayment screened() {
        return screened;
    }
}
