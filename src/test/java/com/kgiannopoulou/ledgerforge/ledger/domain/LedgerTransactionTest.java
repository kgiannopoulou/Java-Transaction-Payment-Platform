package com.kgiannopoulou.ledgerforge.ledger.domain;

import com.kgiannopoulou.ledgerforge.shared.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @Test
    void recordsABalancedTransfer() {
        var tx = LedgerTransaction.record(UUID.randomUUID(), "TX-001", LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(alice, eur("20")), Posting.credit(bob, eur("20"))), NOW);

        assertThat(tx.getEntries()).hasSize(2);
        assertThat(tx.getEntries()).extracting(LedgerEntry::getDirection)
                .containsExactly(EntryDirection.DEBIT, EntryDirection.CREDIT);
        assertThat(tx.getEntries()).extracting(LedgerEntry::getAmount).containsOnly(eur("20"));
    }

    @Test
    void supportsSplitPostingsAsLongAsTheyBalance() {
        var tx = LedgerTransaction.record(UUID.randomUUID(), "TX-002", LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(alice, eur("30")),
                        Posting.credit(bob, eur("10")),
                        Posting.credit(carol, eur("20"))), NOW);
        assertThat(tx.getEntries()).hasSize(3);
    }

    @Test
    void rejectsUnbalancedPostings() {
        assertThatThrownBy(() -> LedgerTransaction.record(UUID.randomUUID(), "TX-003", LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(alice, eur("20")), Posting.credit(bob, eur("19.99"))), NOW))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("EUR 0.01");
    }

    @Test
    void balancesPerCurrencyNotInTotal() {
        // 10 EUR debit + 10 USD credit "sum" to zero numerically but are not balanced.
        assertThatThrownBy(() -> LedgerTransaction.record(UUID.randomUUID(), "TX-004", LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(alice, eur("10")), Posting.credit(bob, Money.of("10", "USD"))), NOW))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void requiresAtLeastTwoEntries() {
        assertThatThrownBy(() -> LedgerTransaction.record(UUID.randomUUID(), "TX-005", LedgerTransactionType.TRANSFER,
                List.of(Posting.debit(alice, eur("10"))), NOW))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void postingsMustBePositive() {
        assertThatThrownBy(() -> Posting.debit(alice, eur("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Posting.credit(alice, eur("-5"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }
}
