# Ledger invariants

These are the properties LedgerForge promises. Each one is enforced in **at least two places**
(domain code and database) and verified by tests.

| # | Invariant | Enforced by | Verified by |
|---|-----------|-------------|-------------|
| 1 | Every ledger transaction balances: Σ debits = Σ credits, **per currency** | `LedgerTransaction.record` · deferred constraint trigger `trg_ledger_entries_balanced` (checked at COMMIT) | `LedgerTransactionTest`, `LedgerDatabaseGuaranteesTest` |
| 2 | An entry's currency equals its account's currency | `Account.debit/credit` · trigger `trg_ledger_entries_currency` | `AccountTest`, `LedgerDatabaseGuaranteesTest` |
| 3 | The ledger is append-only | `@Immutable` entities · `BEFORE UPDATE OR DELETE` triggers | `LedgerDatabaseGuaranteesTest.ledgerIsAppendOnly` |
| 4 | Stored balance = Σ credits − Σ debits of the account's entries | balances change only inside `LedgerService.post`, under row locks | `LedgerReconciliationService`, every concurrency test |
| 5 | Per currency, Σ of all account balances = 0 | system accounts close the books (deposits debit `EXTERNAL_FUNDING`) | `reconcile()` in `PessimisticLockingConcurrencyTest` |
| 6 | Wallets never go negative | `Account.debit` · `CHECK (balance >= 0 OR type = 'EXTERNAL_FUNDING')` | `AccountTest`, `doubleSpendIsImpossible` |
| 7 | A business event is booked at most once | `UNIQUE (reference)` on `ledger_transactions` | `LedgerDatabaseGuaranteesTest.ledgerReferenceIsUnique` |
| 8 | Only the ledger moves money | ArchUnit: only `..ledger..` may call `Account.debit/credit` | `ArchitectureTest.only_the_ledger_moves_money` |

## Why a deferred trigger?

Entries are inserted one row at a time. After the first row a transaction is *always*
temporarily unbalanced, so a per-row check would reject every valid transaction. A
`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED` runs at `COMMIT`, after all entries
exist, and aborts the whole transaction if any currency does not net to zero:

```sql
CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_transaction_balanced();
```

The Java check gives a precise error early; the database check guarantees that no bug,
manual SQL or future service writing to the same tables can create or destroy money.

## Why keep a `balance` column at all?

Pure event-sourced balances (`SELECT SUM(...)` on every request) are correct but cost O(entries)
and give nothing to lock. LedgerForge stores a running balance on the account row:

- the balance check before a debit is O(1);
- the account row is the natural **lock** for serialising concurrent debits;
- the ledger stays the source of truth: invariant 4 is checked by reconciliation.

## Reconciliation

`GET /api/v1/admin/reconciliation` (and `LedgerReconciliationService.reconcile()`) re-derives
invariants 1, 4 and 5 with **raw SQL**, independent of the JPA model it is meant to audit, in one
`REPEATABLE READ` snapshot:

```json
{
  "consistent": true,
  "unbalancedTransactions": [],
  "accountMismatches": [],
  "currencyImbalances": [],
  "checkedAt": "2026-11-02T08:00:00Z"
}
```

The naive-locking race test shows what a failed reconciliation looks like: Alice's stored balance
says `90.00` while her ledger entries say `-100.00`.

## Corrections

Because history is immutable, mistakes are fixed with **compensating transactions** (a refund, a
reversal), never by editing or deleting entries. The audit trail is the ledger itself.
