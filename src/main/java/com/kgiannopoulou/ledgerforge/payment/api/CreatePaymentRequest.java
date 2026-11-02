package com.kgiannopoulou.ledgerforge.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull UUID payerAccountId,
        @NotNull UUID merchantAccountId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull Currency currency,
        @Size(max = 255) String description) {
}
