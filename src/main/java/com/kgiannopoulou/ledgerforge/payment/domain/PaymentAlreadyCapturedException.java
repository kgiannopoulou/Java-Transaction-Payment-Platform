package com.kgiannopoulou.ledgerforge.payment.domain;

import java.util.UUID;

/**
 * A capture was requested for a payment whose funds were already captured (HTTP 409). A distinct
 * type lets clients treat a duplicate capture as "already done" rather than as a generic error.
 */
public class PaymentAlreadyCapturedException extends InvalidPaymentStateException {

    public PaymentAlreadyCapturedException(UUID paymentId, PaymentStatus current) {
        super("PAYMENT_ALREADY_CAPTURED",
                message(paymentId, current, PaymentStatus.CAPTURED) + "; funds were already captured");
    }

    @Override
    public String title() {
        return "Payment already captured";
    }
}
