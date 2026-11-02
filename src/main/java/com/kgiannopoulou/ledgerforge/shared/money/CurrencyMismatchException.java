package com.kgiannopoulou.ledgerforge.shared.money;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.Currency;

/** Arithmetic or a transfer was attempted across two different currencies. */
public class CurrencyMismatchException extends BusinessRuleViolationException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("currency_mismatch", "Currency mismatch: expected " + expected + " but got " + actual);
    }
}
