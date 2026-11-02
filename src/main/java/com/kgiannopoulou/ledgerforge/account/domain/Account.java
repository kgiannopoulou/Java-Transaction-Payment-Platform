package com.kgiannopoulou.ledgerforge.account.domain;

import com.kgiannopoulou.ledgerforge.shared.persistence.BaseEntity;
import com.kgiannopoulou.ledgerforge.shared.error.BusinessRuleViolationException;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.money.CurrencyMismatchException;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Anything that can hold ledger entries: a customer's wallet or a platform system account.
 *
 * <p>{@link #balance} is a denormalised running balance kept in the same database transaction
 * as the ledger entries that change it (balance = credits - debits). It exists so balance checks
 * are O(1) and lockable; the ledger remains the source of truth and the reconciliation query in
 * {@code LedgerReconciliationService} proves the two never disagree.
 *
 * <p>The only way to move money is to post a balanced ledger transaction: {@link #debit} and
 * {@link #credit} may be called exclusively from the {@code ledger} module, a rule enforced by
 * {@code ArchitectureTest}.
 */
@Entity
@Table(name = "accounts")
public class Account extends BaseEntity {

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AccountType type;

    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /** Optimistic-locking version; incremented by Hibernate on every update of this row. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    private Account(UUID id, UUID customerId, AccountType type, Currency currency, Instant createdAt) {
        super(id);
        this.customerId = customerId;
        this.type = Objects.requireNonNull(type);
        this.currency = Objects.requireNonNull(currency);
        this.status = AccountStatus.ACTIVE;
        this.balance = BigDecimal.ZERO;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Account openWallet(UUID customerId, Currency currency, Instant now) {
        Objects.requireNonNull(customerId, "customerId");
        return new Account(UUID.randomUUID(), customerId, AccountType.CUSTOMER_WALLET, currency, now);
    }

    // ---- balance (callable only from the ledger module, see ArchitectureTest) ---------------

    public void debit(Money amount) {
        requirePositiveAmountInAccountCurrency(amount);
        Money next = getBalance().subtract(amount);
        if (next.isNegative() && !type.allowsNegativeBalance()) {
            throw new InsufficientFundsException(getId());
        }
        balance = next.amount();
    }

    public void credit(Money amount) {
        requirePositiveAmountInAccountCurrency(amount);
        balance = getBalance().add(amount).amount();
    }

    private void requirePositiveAmountInAccountCurrency(Money amount) {
        if (!amount.currency().equals(currency)) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amounts must be positive: " + amount);
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    public void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new BusinessRuleViolationException("account_not_active",
                    "Account " + getId() + " is " + status + " and cannot move money");
        }
    }

    public void freeze() {
        requireWallet();
        if (status == AccountStatus.CLOSED) {
            throw new ConflictException("account_closed", "Account " + getId() + " is closed");
        }
        status = AccountStatus.FROZEN;
    }

    public void unfreeze() {
        requireWallet();
        if (status == AccountStatus.CLOSED) {
            throw new ConflictException("account_closed", "Account " + getId() + " is closed");
        }
        status = AccountStatus.ACTIVE;
    }

    public void close() {
        requireWallet();
        if (!getBalance().isZero()) {
            throw new BusinessRuleViolationException("account_balance_not_zero",
                    "Account " + getId() + " must have a zero balance before it can be closed");
        }
        status = AccountStatus.CLOSED;
    }

    private void requireWallet() {
        if (type != AccountType.CUSTOMER_WALLET) {
            throw new BusinessRuleViolationException("system_account", "System accounts cannot change status");
        }
    }

    // ---- accessors ---------------------------------------------------------------------------

    public Money getBalance() {
        return Money.of(balance, currency);
    }

    public boolean canCover(Money amount) {
        return type.allowsNegativeBalance() || !getBalance().isLessThan(amount);
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public AccountType getType() {
        return type;
    }

    public Currency getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
