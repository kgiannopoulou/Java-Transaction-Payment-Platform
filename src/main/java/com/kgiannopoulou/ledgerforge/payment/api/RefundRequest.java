package com.kgiannopoulou.ledgerforge.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Currency;

/** The currency is required (rather than implied) so a client bug cannot refund in the wrong unit. */
public record RefundRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull Currency currency) {
}
