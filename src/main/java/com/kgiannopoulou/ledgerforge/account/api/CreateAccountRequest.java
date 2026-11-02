package com.kgiannopoulou.ledgerforge.account.api;

import jakarta.validation.constraints.NotNull;

import java.util.Currency;
import java.util.UUID;

public record CreateAccountRequest(
        @NotNull UUID customerId,
        @NotNull Currency currency) {
}
