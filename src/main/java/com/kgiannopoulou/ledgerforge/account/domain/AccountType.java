package com.kgiannopoulou.ledgerforge.account.domain;

public enum AccountType {

    /** A customer's wallet in one currency. Can never go negative. */
    CUSTOMER_WALLET,

    /**
     * Platform account representing money outside LedgerForge. Deposits debit it, so its balance
     * is the negated sum of everything ever deposited. The only account allowed to go negative.
     */
    EXTERNAL_FUNDING,

    /** Platform account holding funds of authorized-but-not-captured payments. */
    PAYMENT_HOLDS;

    public boolean allowsNegativeBalance() {
        return this == EXTERNAL_FUNDING;
    }
}
