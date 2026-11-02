package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;

import java.util.UUID;

public class InsufficientFundsException extends BusinessRuleViolationException {

    public InsufficientFundsException(UUID accountId) {
        super("insufficient_funds", "Account " + accountId + " has insufficient funds");
    }
}
