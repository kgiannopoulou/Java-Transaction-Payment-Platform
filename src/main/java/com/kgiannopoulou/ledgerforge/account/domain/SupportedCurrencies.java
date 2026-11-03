package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.Currency;
import java.util.Set;

/** Currencies for which system accounts are provisioned (see migration V3). */
public final class SupportedCurrencies {

    public static final Set<Currency> ALL = Set.of(
            Currency.getInstance("EUR"),
            Currency.getInstance("USD"),
            Currency.getInstance("GBP"));

    private SupportedCurrencies() {
    }

    public static void require(Currency currency) {
        if (!ALL.contains(currency)) {
            throw new BusinessRuleViolationException("UNSUPPORTED_CURRENCY",
                    "Currency " + currency + " is not supported; supported: EUR, USD, GBP");
        }
    }
}
