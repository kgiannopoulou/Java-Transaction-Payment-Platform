package com.kgiannopoulou.ledgerforge.payment.domain;

import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;

import java.util.UUID;

/**
 * The requested command is not allowed from the payment's current status, e.g.
 * {@code REFUNDED -> CAPTURED} (HTTP 409). Use {@link #of} so the most specific subtype is chosen.
 */
public class InvalidPaymentStateException extends ConflictException {

    protected InvalidPaymentStateException(String type, String message) {
        super(type, message);
    }

    public InvalidPaymentStateException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        this("INVALID_PAYMENT_STATE", message(paymentId, from, to));
    }

    public static InvalidPaymentStateException of(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        boolean alreadyCaptured = to == PaymentStatus.CAPTURED && from.hasBeenCaptured();
        return alreadyCaptured
                ? new PaymentAlreadyCapturedException(paymentId, from)
                : new InvalidPaymentStateException(paymentId, from, to);
    }

    static String message(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        return "Payment " + paymentId + " cannot go from " + from + " to " + to
                + " (allowed: " + from.allowedTransitions() + ")";
    }

    @Override
    public String title() {
        return "Invalid payment state";
    }
}
