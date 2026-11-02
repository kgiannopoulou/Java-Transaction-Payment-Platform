# ADR 0001: Modular monolith, package by feature

**Status:** Accepted · 2026-11-02

## Context

A payment platform grows by business capability (transfers, payments, refunds, payouts).
A `controller/ service/ repository/ entity/` layout scatters each capability over four folders
and lets any class call any other. Microservices would enforce boundaries, but they would also
turn the core guarantee (debit, credit and business record commit atomically) into a
distributed-transaction problem before the domain is even understood.

## Decision

One Spring Boot deployable, split into feature modules (`customer`, `account`, `ledger`,
`transfer`, `payment`, `idempotency`) plus a `shared` kernel. Each module has
`api / application / domain / infrastructure` layers. Boundaries are enforced by ArchUnit
(`ArchitectureTest`): no cycles between modules, domain independent of adapters, and only the
ledger may change balances.

## Consequences

- Money movements stay ACID: a single PostgreSQL transaction.
- Modules are candidates for extraction later. The outbox/Kafka milestone will define the
  events that cross module boundaries.
- Discipline is automated: a violating import fails the build, not code review.
