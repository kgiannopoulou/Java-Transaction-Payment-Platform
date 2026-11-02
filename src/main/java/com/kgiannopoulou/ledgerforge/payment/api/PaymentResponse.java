package com.kgiannopoulou.ledgerforge.payment.api;

import com.kgiannopoulou.ledgerforge.payment.domain.Payment;
import com.kgiannopoulou.ledgerforge.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        UUID payerAccountId,
        UUID merchantAccountId,
        String amount,
        String refundedAmount,
        String currency,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getStatus(), payment.getPayerAccountId(),
                payment.getMerchantAccountId(), payment.getAmount().toPlainString(),
                payment.getRefundedAmount().toPlainString(), payment.getCurrency().getCurrencyCode(),
                payment.getDescription(), payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
