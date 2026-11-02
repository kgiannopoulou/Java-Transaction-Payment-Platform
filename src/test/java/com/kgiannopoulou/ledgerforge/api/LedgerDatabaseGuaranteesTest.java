package com.kgiannopoulou.ledgerforge.api;

import com.kgiannopoulou.ledgerforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger invariants are enforced by PostgreSQL itself, not only by Java code. These tests
 * bypass the domain model with raw SQL to prove a buggy (or malicious) writer is stopped.
 */
class LedgerDatabaseGuaranteesTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void unbalancedTransactionIsRejectedAtCommitAndLeavesNoTrace() {
        UUID alice = newFundedWallet("EUR", "20.00");
        UUID bob = newWallet("EUR");
        UUID ledgerTx = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertTransaction(ledgerTx);
            insertEntry(ledgerTx, alice, "DEBIT", "20.00", "EUR");
            insertEntry(ledgerTx, bob, "CREDIT", "19.00", "EUR"); // 1 EUR vanished
        })).rootCause().hasMessageContaining("unbalanced");

        assertThat(jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE id = ?").param(ledgerTx)
                .query(Long.class).single()).isZero();
    }

    @Test
    void balancedTransactionInsertedBySqlIsAccepted() {
        UUID alice = newFundedWallet("EUR", "5.00");
        UUID bob = newWallet("EUR");
        UUID ledgerTx = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            insertTransaction(ledgerTx);
            insertEntry(ledgerTx, alice, "DEBIT", "5.00", "EUR");
            insertEntry(ledgerTx, bob, "CREDIT", "5.00", "EUR");
        });

        assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?").param(ledgerTx)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void entryCurrencyMustMatchAccountCurrency() {
        UUID eurWallet = newFundedWallet("EUR", "5.00");
        UUID usdWallet = newWallet("USD");
        UUID ledgerTx = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertTransaction(ledgerTx);
            insertEntry(ledgerTx, eurWallet, "DEBIT", "5.00", "USD");
            insertEntry(ledgerTx, usdWallet, "CREDIT", "5.00", "USD");
        })).rootCause().hasMessageContaining("does not match account");
    }

    @Test
    void ledgerIsAppendOnly() {
        UUID alice = newFundedWallet("EUR", "50.00");

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1000 WHERE account_id = ?")
                .param(alice).update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE account_id = ?")
                .param(alice).update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_transactions").update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void walletBalanceCannotBeNegativeEvenViaSql() {
        UUID alice = newWallet("EUR");
        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET balance = -1 WHERE id = ?").param(alice).update())
                .hasMessageContaining("ck_accounts_non_negative");
    }

    @Test
    void ledgerReferenceIsUnique() {
        UUID alice = newFundedWallet("EUR", "0.01");
        UUID bob = newWallet("EUR");
        UUID first = UUID.randomUUID();
        String reference = "DUP-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            insertTransaction(first, reference);
            insertEntry(first, alice, "DEBIT", "0.01", "EUR");
            insertEntry(first, bob, "CREDIT", "0.01", "EUR");
        });
        assertThatThrownBy(() -> insertTransaction(UUID.randomUUID(), reference))
                .hasMessageContaining("ux_ledger_transactions_reference");
    }

    private void insertTransaction(UUID id) {
        insertTransaction(id, "TEST-" + id);
    }

    private void insertTransaction(UUID id, String reference) {
        jdbc.sql("INSERT INTO ledger_transactions (id, reference, type, created_at) VALUES (?, ?, 'TRANSFER', ?)")
                .params(id, reference, Timestamp.from(Instant.now())).update();
    }

    /** Inserts an entry and keeps the account's running balance in step, like LedgerService does. */
    private void insertEntry(UUID transactionId, UUID accountId, String direction, String amount, String currency) {
        BigDecimal signed = "CREDIT".equals(direction) ? new BigDecimal(amount) : new BigDecimal(amount).negate();
        jdbc.sql("UPDATE accounts SET balance = balance + ? WHERE id = ?").params(signed, accountId).update();
        jdbc.sql("""
                        INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount, currency, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), transactionId, accountId, direction, new BigDecimal(amount), currency,
                        Timestamp.from(Instant.now()))
                .update();
    }
}
