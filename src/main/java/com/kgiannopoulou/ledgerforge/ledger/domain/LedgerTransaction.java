package com.kgiannopoulou.ledgerforge.ledger.domain;

import com.kgiannopoulou.ledgerforge.shared.persistence.BaseEntity;
import com.kgiannopoulou.ledgerforge.shared.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An immutable, balanced group of ledger entries describing one business event.
 *
 * <p>The only way to create one is {@link #record}, which refuses unbalanced input. The database
 * re-checks the same invariant with a deferred constraint trigger at commit time.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction extends BaseEntity {

    @Column(nullable = false, length = 100, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private LedgerTransactionType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("direction desc, id")
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
        // for JPA
    }

    private LedgerTransaction(UUID id, String reference, LedgerTransactionType type, Instant createdAt) {
        super(id);
        this.reference = reference;
        this.type = type;
        this.createdAt = createdAt;
    }

    /**
     * Builds a balanced transaction.
     *
     * @throws UnbalancedTransactionException if, for any currency, debits != credits, or fewer than
     *                                        two entries are supplied
     */
    public static LedgerTransaction record(UUID id, String reference, LedgerTransactionType type,
                                           List<Posting> postings, Instant now) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(type, "type");
        assertBalanced(postings);

        LedgerTransaction transaction = new LedgerTransaction(id, reference, type, now);
        postings.forEach(posting -> transaction.entries.add(new LedgerEntry(transaction, posting, now)));
        return transaction;
    }

    static void assertBalanced(List<Posting> postings) {
        if (postings == null || postings.size() < 2) {
            throw new UnbalancedTransactionException("A ledger transaction needs at least two entries");
        }
        Map<Currency, Money> netByCurrency = postings.stream()
                .collect(Collectors.groupingBy(
                        posting -> posting.amount().currency(),
                        Collectors.reducing(null, Posting::signedAmount,
                                (left, right) -> left == null ? right : left.add(right))));

        netByCurrency.forEach((currency, net) -> {
            if (!net.isZero()) {
                throw new UnbalancedTransactionException(
                        "Debits and credits differ by " + net + " in " + currency);
            }
        });
    }

    public String getReference() {
        return reference;
    }

    public LedgerTransactionType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
