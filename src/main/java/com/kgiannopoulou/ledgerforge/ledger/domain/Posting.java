package com.kgiannopoulou.ledgerforge.ledger.domain;

import com.kgiannopoulou.ledgerforge.shared.money.Money;

import java.util.Objects;
import java.util.UUID;

/** One requested line of a ledger transaction, before it is booked as a {@link LedgerEntry}. */
public record Posting(UUID accountId, EntryDirection direction, Money amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amount must be positive: " + amount);
        }
    }

    public static Posting debit(UUID accountId, Money amount) {
        return new Posting(accountId, EntryDirection.DEBIT, amount);
    }

    public static Posting credit(UUID accountId, Money amount) {
        return new Posting(accountId, EntryDirection.CREDIT, amount);
    }

    /** Debits count positive, credits negative; a balanced transaction sums to zero. */
    Money signedAmount() {
        return direction == EntryDirection.DEBIT ? amount : amount.negate();
    }
}
