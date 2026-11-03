package com.kgiannopoulou.ledgerforge.shared.error;

/**
 * The request is well-formed but violates a business rule, e.g. insufficient funds or a frozen
 * account (HTTP 422).
 */
public non-sealed class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String type, String message) {
        super(type, message);
    }

    @Override
    public String title() {
        return "Request rejected";
    }
}
