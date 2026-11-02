package com.kgiannopoulou.ledgerforge.ledger.domain;

/**
 * A ledger transaction whose debits and credits do not cancel out. This is a programming error,
 * not a client error: no API input can produce it, so it maps to HTTP 500.
 */
public class UnbalancedTransactionException extends IllegalStateException {

    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
