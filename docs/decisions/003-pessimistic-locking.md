# Pessimistic row locks with ordered acquisition

**Status:** Accepted · 2026-11-02

## Context

Without coordination, concurrent debits lose updates and overspend. `NaiveLockingRaceTest` reproduces
it: all 20 withdrawals of €10 succeeded against a €100 wallet.

## Options considered

| Option | For | Against |
|--------|-----|---------|
| **Pessimistic row locks** (`SELECT ... FOR UPDATE`) | requests queue in order; simple to reason about; no wasted work | a hot account is limited by transaction length; deadlocks unless locks are ordered |
| Optimistic locking (`@Version` and retry) | no locks held; excellent with low contention | on a hot account most attempts fail: about 6 retries per request in the contended test here |
| `SERIALIZABLE` isolation | the database detects every anomaly | broad retry handling on all transactions, and harder to reason about under load |
| Atomic update (`UPDATE ... WHERE balance >= ?`) | one statement, very fast | fine for one balance, but awkward when a ledger write and several rows must stay consistent |

## Decision

Lock every account whose balance will change with `SELECT ... FOR UPDATE`, in **ascending UUID
order**, so two transfers between A and B in opposite directions cannot deadlock. All checks that
depend on balance or status run after locking, because checking first is a time-of-check race.

`@Version` stays on accounts and payments as a second line of defence, and the strategy is switchable
(`ledgerforge.concurrency.locking-mode`), so both approaches run through the same test suite.

## Consequences

- 100 concurrent withdrawals of the whole balance: exactly 1 succeeds and 99 are refused.
- 100 × A→B interleaved with 100 × B→A: all succeed, with no deadlocks.
- A hot account is limited by how long its transaction takes. `PAYMENT_HOLDS` is one row per currency
  that every authorization touches, which is the platform's known scaling limit. Sharding it into
  several accounts per currency is the known fix ([performance results](../../performance/README.md)).
- Connections are held while waiting for locks, so a hot row can use up the pool.
