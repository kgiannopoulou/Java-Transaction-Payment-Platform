# ADR 0004: Pessimistic row locks with ordered acquisition

**Status:** Accepted · 2026-11-02

## Context

Without coordination, concurrent debits of the same wallet lose updates and over-spend. This
is reproduced by `NaiveLockingRaceTest`: 20 of 20 withdrawals of €10 succeeded from a €100
wallet. The options were optimistic locking (`@Version` + retry), pessimistic row locks
(`SELECT ... FOR UPDATE`), serializable isolation, and atomic conditional updates.

## Decision

Default to **pessimistic** row locks on every account whose balance will change, acquired in
**ascending UUID order** to rule out deadlocks. Keep `@Version` on accounts and payments as a
second line of defence. Make the strategy switchable (`ledgerforge.concurrency.locking-mode`)
so both approaches run through the same concurrency test suite.

## Consequences

- Hot accounts queue instead of retrying. The optimistic run of the same contended test needed
  about 6 retries per request.
- Throughput on a single hot account is bounded by transaction duration. System accounts are a
  known hotspot (see `docs/concurrency.md`).
- A transparent retry wrapper still handles deadlock and serialization failures if they occur.
