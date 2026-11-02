package com.kgiannopoulou.ledgerforge.ledger.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Independently re-derives the ledger invariants from raw SQL (not from the JPA model whose
 * bugs it is meant to catch):
 *
 * <ol>
 *   <li>every ledger transaction balances per currency;</li>
 *   <li>every account's stored balance equals {@code sum(credits) - sum(debits)} of its entries;</li>
 *   <li>per currency, the balances of all accounts sum to zero (money is neither created nor destroyed).</li>
 * </ol>
 *
 * <p>Runs at REPEATABLE READ so all three checks see one consistent snapshot.
 */
@Service
public class LedgerReconciliationService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public LedgerReconciliationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record AccountMismatch(UUID accountId, BigDecimal storedBalance, BigDecimal ledgerBalance) {
    }

    public record CurrencyImbalance(String currency, BigDecimal total) {
    }

    public record Report(boolean consistent,
                         List<UUID> unbalancedTransactions,
                         List<AccountMismatch> accountMismatches,
                         List<CurrencyImbalance> currencyImbalances,
                         Instant checkedAt) {
    }

    /** Whole-ledger reconciliation. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report reconcile() {
        List<UUID> unbalanced = jdbc.sql("""
                        SELECT transaction_id
                        FROM ledger_entries
                        GROUP BY transaction_id, currency
                        HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                        """)
                .query(UUID.class).list();

        List<AccountMismatch> mismatches = jdbc.sql("""
                        SELECT a.id, a.balance, COALESCE(l.ledger_balance, 0) AS ledger_balance
                        FROM accounts a
                        LEFT JOIN (SELECT account_id,
                                          SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS ledger_balance
                                   FROM ledger_entries GROUP BY account_id) l ON l.account_id = a.id
                        WHERE a.balance <> COALESCE(l.ledger_balance, 0)
                        """)
                .query((rs, row) -> new AccountMismatch(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("balance"), rs.getBigDecimal("ledger_balance")))
                .list();

        List<CurrencyImbalance> imbalances = jdbc.sql("""
                        SELECT currency, SUM(balance) AS total
                        FROM accounts GROUP BY currency HAVING SUM(balance) <> 0
                        """)
                .query((rs, row) -> new CurrencyImbalance(rs.getString("currency"), rs.getBigDecimal("total")))
                .list();

        return new Report(unbalanced.isEmpty() && mismatches.isEmpty() && imbalances.isEmpty(),
                unbalanced, mismatches, imbalances, clock.instant());
    }

    /**
     * Checks invariant 2 only, for a subset of accounts. Used by tests that run against a shared
     * database and only want to judge the accounts they created.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AccountMismatch> mismatchesFor(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT a.id, a.balance,
                               COALESCE((SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
                                         FROM ledger_entries e WHERE e.account_id = a.id), 0) AS ledger_balance
                        FROM accounts a
                        WHERE a.id IN (:ids)
                        """)
                .param("ids", accountIds)
                .query((rs, row) -> new AccountMismatch(rs.getObject("id", UUID.class),
                        rs.getBigDecimal("balance"), rs.getBigDecimal("ledger_balance")))
                .list()
                .stream()
                .filter(m -> m.storedBalance().compareTo(m.ledgerBalance()) != 0)
                .toList();
    }
}
