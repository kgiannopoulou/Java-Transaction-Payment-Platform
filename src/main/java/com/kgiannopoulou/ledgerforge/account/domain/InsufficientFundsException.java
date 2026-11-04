package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.UUID;

/** The debited account's balance does not cover the requested amount (HTTP 422). */
public class InsufficientFundsException extends BusinessRuleViolationException {

    private final UUID accountId;

    public InsufficientFundsException(UUID accountId) {
        super("INSUFFICIENT_FUNDS", "Account " + accountId + " has insufficient available funds");
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }

    @Override
    public String title() {
        return "Insufficient funds";
    }
}
