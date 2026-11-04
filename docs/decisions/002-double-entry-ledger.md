# Double-entry ledger with a running balance

**Status:** Accepted · 2026-11-02

## Context

`account.balance = balance - 20` keeps no history, offers no audit trail and gives no way to prove
money was neither created nor destroyed. Finance solved this centuries ago: every movement is
recorded twice, as a debit and a credit that cancel out.

## Options considered

| Option | For | Against |
|--------|-----|---------|
| Balance column only | simple and fast | no history; a bug silently changes money and nothing can show it happened |
| Balance plus a transaction log | history exists | the log describes changes but proves nothing: one-sided entries are possible |
| Double entry, balance derived with `SUM` | provably correct | O(n) on every read, and no row to lock against concurrent debits |
| **Double entry plus a running balance** | proof and O(1) checks, with a natural row to lock | two representations of one fact, which must never diverge |

## Decision

Every money movement is a balanced `LedgerTransaction` of immutable `LedgerEntry` rows: debits equal
credits per currency, checked in Java **and** by a deferred PostgreSQL trigger at commit.

Accounts also keep a running `balance`, changed only by `LedgerService.post`, in the same transaction
as the entries. System accounts (`EXTERNAL_FUNDING`, `PAYMENT_HOLDS`) mean every currency sums to
zero. A reconciliation service re-derives every invariant with raw SQL.

## Consequences

- Funds checks are O(1), and the account row is what gets locked.
- History is immutable. A mistake is corrected by a compensating transaction, never an edit.
- Two representations can drift in theory. Reconciliation, the property tests and the crash
  experiments exist to prove they do not.
- `ArchitectureTest` makes it a build failure for anything outside `ledger` to call `Account.debit`.
- Every movement writes at least three rows instead of one update: a cost worth paying here.
