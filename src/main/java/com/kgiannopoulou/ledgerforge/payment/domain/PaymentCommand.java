package com.kgiannopoulou.ledgerforge.payment.domain;

import com.kgiannopoulou.ledgerforge.shared.money.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * State-changing commands on an existing payment.
 *
 * <p>The hierarchy is {@code sealed}: the compiler knows every implementation, so a
 * {@code switch} over a {@code PaymentCommand} without a {@code default} branch fails to compile
 * when a new command is added and not handled. Records make each command an immutable value.
 */
public sealed interface PaymentCommand
        permits PaymentCommand.AuthorizePayment,
                PaymentCommand.CapturePayment,
                PaymentCommand.CancelPayment,
                PaymentCommand.RefundPayment {

    UUID paymentId();

    /** Reserve the payer's funds (payer wallet &rarr; PAYMENT_HOLDS). */
    record AuthorizePayment(UUID paymentId) implements PaymentCommand {
        public AuthorizePayment {
            Objects.requireNonNull(paymentId);
        }
    }

    /** Settle the reserved funds to the merchant (PAYMENT_HOLDS &rarr; merchant wallet). */
    record CapturePayment(UUID paymentId) implements PaymentCommand {
        public CapturePayment {
            Objects.requireNonNull(paymentId);
        }
    }

    /** Void before capture; releases any hold back to the payer. */
    record CancelPayment(UUID paymentId) implements PaymentCommand {
        public CancelPayment {
            Objects.requireNonNull(paymentId);
        }
    }

    /** Return (part of) a captured amount (merchant wallet &rarr; payer wallet). */
    record RefundPayment(UUID paymentId, Money amount) implements PaymentCommand {
        public RefundPayment {
            Objects.requireNonNull(paymentId);
            Objects.requireNonNull(amount);
        }
    }
}
