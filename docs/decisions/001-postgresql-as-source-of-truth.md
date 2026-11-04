# PostgreSQL as the source of truth

**Status:** Accepted · 2026-11-02

## Context

A payment platform has one requirement that outranks the rest: money must not appear, disappear or
move twice, including under concurrency and after a crash. That calls for atomic multi-row writes,
row locks and constraints the database itself enforces. Everything else in the system (Kafka, Redis,
webhooks) can lag, fail or be rebuilt, as long as one place holds the authoritative answer.

## Options considered

| Option | For | Against |
|--------|-----|---------|
| **PostgreSQL** | ACID transactions across tables, row locks, `SKIP LOCKED`, `CHECK` and foreign key constraints, deferred constraint triggers | a fixed schema; one primary to scale |
| MongoDB | flexible documents, easy horizontal scaling | multi-document transactions are newer and costlier; relational integrity moves into application code, where a bug can bypass it |
| Event sourcing on Kafka | a natural audit log | no multi-account atomic check ("can this wallet afford it?") without building a transaction layer on top; reading a balance needs a projection that can lag |
| Distributed SQL (CockroachDB, Spanner) | horizontal scale with transactions | operational and latency cost nobody here needs yet |

## Decision

PostgreSQL is the single source of truth for balances, the ledger, payments, idempotency keys, the
audit trail and the delivery state of events. A request is finished when PostgreSQL has committed.

Rules live in the database as well as in Java: `CHECK (amount > 0)`, a deferred trigger that rejects
unbalanced ledger transactions, append-only triggers, and composite foreign keys for currencies.

## Consequences

- One transaction can cover the money movement, the idempotency key, the audit event and the outbox
  row, so they commit or roll back together. Most of this project's guarantees rest on that.
- A bug in Java cannot store invalid data: `DatabaseConstraintsTest` proves it with raw SQL.
- The schema is less flexible, and each change is a Flyway migration. That trade suits a domain where
  the data's shape is stable and its correctness is not negotiable.
- Writes scale with one primary. Hot rows, not raw capacity, are the first limit
  ([performance results](../../performance/README.md)).
- Other stores are derived. Kafka carries copies of facts, and Redis holds disposable state
  ([ADR 006](006-redis-not-source-of-truth.md)).
