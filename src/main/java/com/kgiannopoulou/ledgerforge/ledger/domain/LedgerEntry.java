package com.kgiannopoulou.ledgerforge.ledger.domain;

import com.kgiannopoulou.ledgerforge.shared.persistence.BaseEntity;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** One immutable debit or credit line. Accounts are referenced by id to keep modules decoupled. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6, updatable = false)
    private EntryDirection direction;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    LedgerEntry(LedgerTransaction transaction, Posting posting, Instant createdAt) {
        super(UUID.randomUUID());
        this.transaction = transaction;
        this.accountId = posting.accountId();
        this.direction = posting.direction();
        this.amount = posting.amount().amount();
        this.currency = posting.amount().currency();
        this.createdAt = createdAt;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
