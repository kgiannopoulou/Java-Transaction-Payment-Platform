package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.UUID;

/** A closed account is permanently inactive: no money movement, no status changes (HTTP 422). */
public class AccountClosedException extends BusinessRuleViolationException {

    public AccountClosedException(UUID accountId) {
        super("ACCOUNT_CLOSED", "Account " + accountId + " is closed");
    }

    @Override
    public String title() {
        return "Account closed";
    }
}
