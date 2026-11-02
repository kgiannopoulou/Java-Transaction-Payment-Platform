package com.kgiannopoulou.ledgerforge.transfer.domain;

import com.kgiannopoulou.ledgerforge.shared.money.Money;
import com.kgiannopoulou.ledgerforge.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** A wallet-to-wallet movement of money, backed by exactly one TRANSFER ledger transaction. */
@Entity
@Table(name = "transfers")
public class Transfer extends BaseEntity {

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private UUID destinationAccountId;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transfer() {
        // for JPA
    }

    private Transfer(UUID id, UUID sourceAccountId, UUID destinationAccountId, Money amount, Instant now) {
        super(id);
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        this.destinationAccountId = Objects.requireNonNull(destinationAccountId);
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.status = TransferStatus.PENDING;
        this.createdAt = now;
    }

    public static Transfer initiate(UUID sourceAccountId, UUID destinationAccountId, Money amount, Instant now) {
        return new Transfer(UUID.randomUUID(), sourceAccountId, destinationAccountId, amount, now);
    }

    public void complete(UUID ledgerTransactionId, Instant now) {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer " + getId() + " is already " + status);
        }
        this.ledgerTransactionId = Objects.requireNonNull(ledgerTransactionId);
        this.status = TransferStatus.COMPLETED;
        this.completedAt = now;
    }

    /** Ledger reference: a transfer can be booked at most once (unique constraint). */
    public String ledgerReference() {
        return "TRF-" + getId();
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public TransferStatus getStatus() {
        return status;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
