package com.kgiannopoulou.ledgerforge.shared.money;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.Currency;

/** Arithmetic or a transfer was attempted across two different currencies (HTTP 422). */
public class CurrencyMismatchException extends BusinessRuleViolationException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("CURRENCY_MISMATCH", "Currency mismatch: expected " + expected + " but got " + actual);
    }

    @Override
    public String title() {
        return "Currency mismatch";
    }
}
