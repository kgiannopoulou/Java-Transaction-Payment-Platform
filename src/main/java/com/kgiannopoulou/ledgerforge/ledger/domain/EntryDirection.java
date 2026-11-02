package com.kgiannopoulou.ledgerforge.ledger.domain;

/**
 * Side of a ledger entry. LedgerForge uses one sign convention for every account:
 * {@code balance = sum(CREDIT) - sum(DEBIT)}. Paying out of a wallet debits it; receiving money
 * credits it.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
