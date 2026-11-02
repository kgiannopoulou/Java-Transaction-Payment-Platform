# ADR 0003: Double-entry ledger as source of truth, with a running balance

**Status:** Accepted · 2026-11-02

## Context

`account.balance = balance - 20` gives no history, no audit trail and no way to prove that
money was not created or destroyed. A purely derived balance (`SUM` over entries on every
request) is provable, but it is O(n) and leaves nothing to lock against concurrent debits.

## Decision

- Every money movement is a balanced `LedgerTransaction` of immutable `LedgerEntry` rows
  (Σ debits = Σ credits per currency). This is validated in Java **and** by a deferred
  PostgreSQL constraint trigger at commit.
- Accounts also store a running `balance`, changed only by `LedgerService.post` in the same
  transaction as the entries.
- System accounts (`EXTERNAL_FUNDING`, `PAYMENT_HOLDS`) make every currency sum to zero.
- A reconciliation service re-derives all invariants with raw SQL.

## Consequences

- O(1) funds checks and a natural row to lock.
- Two representations of the same fact. Reconciliation (and the concurrency tests) prove that
  they never diverge.
- History is immutable; corrections are compensating transactions.
