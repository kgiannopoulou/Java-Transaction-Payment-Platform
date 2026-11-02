package com.kgiannopoulou.ledgerforge.payment.domain;

import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;

import java.util.UUID;

public class InvalidPaymentTransitionException extends ConflictException {

    public InvalidPaymentTransitionException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super("invalid_payment_transition",
                "Payment " + paymentId + " cannot go from " + from + " to " + to
                        + " (allowed: " + from.allowedTransitions() + ")");
    }
}
