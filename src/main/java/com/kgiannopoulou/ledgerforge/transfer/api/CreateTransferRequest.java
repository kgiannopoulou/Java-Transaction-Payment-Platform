package com.kgiannopoulou.ledgerforge.transfer.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/**
 * {@code amount} is accepted as a JSON string ({@code "25.00"}) or number and parsed straight into
 * {@link BigDecimal}, never through {@code double}.
 */
public record CreateTransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull Currency currency) {
}
