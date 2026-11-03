package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.UUID;

/** The debited account's balance does not cover the requested amount (HTTP 422). */
public class InsufficientFundsException extends BusinessRuleViolationException {

    public InsufficientFundsException(UUID accountId) {
        super("INSUFFICIENT_FUNDS", "Account " + accountId + " has insufficient available funds");
    }

    @Override
    public String title() {
        return "Insufficient funds";
    }
}
