package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.UUID;

/** A frozen account cannot send or receive money until it is unfrozen (HTTP 422). */
public class AccountFrozenException extends BusinessRuleViolationException {

    public AccountFrozenException(UUID accountId) {
        super("ACCOUNT_FROZEN", "Account " + accountId + " is frozen and cannot move money");
    }

    @Override
    public String title() {
        return "Account frozen";
    }
}
