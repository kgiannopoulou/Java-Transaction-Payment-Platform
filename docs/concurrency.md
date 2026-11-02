# Concurrency

> Concurrent transfer tests exposed lost-update scenarios in a naive read-modify-write
> implementation. LedgerForge uses database row locking for balance-critical operations, with
> deterministic account lock ordering to prevent deadlocks. Optimistic locking with bounded
> retries is available as an alternative and passes the same correctness tests.

## The bug

Alice has €100. Two requests each transfer €10 out of her wallet:

```
Thread A: SELECT balance  → 100
Thread B: SELECT balance  → 100
Thread A: UPDATE balance = 90   COMMIT
Thread B: UPDATE balance = 90   COMMIT     ← A's debit is lost
```

Both ledger debits exist, but the stored balance only reflects one. With enough concurrency
the wallet also **over-spends**, because every request checks funds against a stale balance.

## Three strategies, one switch

`ledgerforge.concurrency.locking-mode` selects how `AccountLocks` loads the accounts a
transaction is about to change:

| Mode | How | Conflict outcome | Use |
|------|-----|------------------|-----|
| `PESSIMISTIC` (default) | `SELECT ... FOR UPDATE`, one account at a time **in ascending UUID order** | later transactions wait for the row lock | production |
| `OPTIMISTIC` | plain `SELECT`; Hibernate issues `UPDATE ... WHERE version = ?` | 0 rows updated → `OptimisticLockException` → whole transaction retried with full-jitter backoff | low-contention alternative |
| `NAIVE` | plain `SELECT` on a detached entity, then blind `UPDATE accounts SET balance = ?` | lost updates | **tests only**, to reproduce the bug |

The same `TransferService`, `PaymentService` and `DepositService` code runs in all three modes.
Only the locking adapter changes.

## Measured results

From the test suite (`mvn verify`, PostgreSQL 17 in Testcontainers, numbers printed as
`[race-report]` lines):

| Scenario | NAIVE | OPTIMISTIC | PESSIMISTIC |
|----------|-------|------------|-------------|
| 20 × "transfer €10" from a €100 wallet (100 ms simulated work) | **20/20 succeeded**: €200 paid out of €100, stored balance €90 vs ledger −€100 | – | – |
| 40 × "transfer €1", same source, all at once | – | 40/40 succeeded after **234–244 retries** (varies per run) | – |
| 20 × "transfer €10" from a €100 wallet | (above) | – | **10 succeeded, 10 × 422**, €100 paid out |
| 100 × "transfer €100" from a €100 wallet | – | – | **1 success, 99 × 422**, balance €0 |
| 30 × "transfer €100" from a €100 wallet | – | 1 success, rest refused | – |
| 500 random transfers among 10 wallets (€1000 total) | – | – | €1000 total afterwards; 457–470 completed (varies per run), the rest cleanly refused |
| 100 × A→B interleaved with 100 × B→A | – | – | 200/200 succeeded, **no deadlocks** |

## Why pessimistic is the default

- **Hot accounts are normal in payments.** A popular merchant or the platform's own system
  accounts receive many concurrent postings. Under optimistic locking, every conflict costs a
  full rolled-back transaction plus backoff; the 40-request test above needed 234–244 retries,
  about 6 per request. Under pessimistic locking the same work simply queues on the row lock.
- **Predictable failure modes.** Waiting for a lock is bounded by transaction length (a few ms).
  Retries are probabilistic and can exhaust `max-attempts` under sustained contention, which
  surfaces as `409 concurrent_modification`.
- Optimistic locking remains a good fit for rarely-contended aggregates. `Payment` and `Account`
  both carry `@Version`, so even code paths that skip row locks cannot silently overwrite each
  other.

## Deadlock prevention: lock ordering

Transfer A→B locks A then B; a simultaneous B→A locks B then A. Each holds one lock and waits
for the other: deadlock. PostgreSQL detects it after `deadlock_timeout` and kills one
transaction.

`AccountLocks` always acquires locks in **ascending UUID order** (`new TreeSet<>(ids)`), so both
transfers lock `min(A,B)` first, and the second simply waits. Payments lock the payment row
first, then accounts in the same global order. Nothing locks in the opposite direction, so no
wait-for cycle can form. `opposingTransfersDoNotDeadlock` runs 200 interleaved opposing
transfers to check this.

As a safety net, `RetryingTransactionExecutor` still retries `40P01` (deadlock) and `40001`
(serialization failure) transparently.

## Time-of-check vs time-of-use

Every check that depends on mutable state (status, currency, **sufficient funds**) runs
**after** the locks are taken, inside the same transaction that writes the result. Checking
first and locking later would reopen the race.

A subtle JPA trap is also avoided: if an account entity is already in the persistence context,
a later `SELECT ... FOR UPDATE` returns the *cached* instance without refreshing its balance.
That is why system accounts are resolved by **id only** (`findSystemAccountId`) before locking.

## Retries

`RetryingTransactionExecutor` wraps a unit of work in a fresh transaction and retries on:
optimistic-lock failures, `PessimisticLockingFailureException`, SQLSTATE `40001`, `40P01`,
`55P03`. Backoff is full-jitter exponential (`initial-backoff` 10 ms, capped at `max-backoff`
200 ms, `max-attempts` 8). Business errors are never retried. Retrying is safe because the
**entire** unit, including idempotency-key bookkeeping, is rolled back first.

## Known hotspots (future work)

- `EXTERNAL_FUNDING` and `PAYMENT_HOLDS` are one row per currency, so every deposit / authorization
  serialises on them. Fixes for later: sharded system accounts, or skipping the running balance
  for accounts that never need a funds check.
- Lock waits are currently unbounded by the application; a `lock_timeout` per transaction would
  turn pathological waits into fast, retryable failures.

## Test harness

`Concurrently.run(n, task)` starts `n` virtual threads that block on a shared start gate, then
releases them together, so requests really arrive at the same time. Every request goes through
real HTTP → Tomcat (virtual threads) → Hikari → PostgreSQL. No mocks, no H2.
