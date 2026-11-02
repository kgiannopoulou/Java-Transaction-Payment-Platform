# ADR 0007: Real PostgreSQL (Testcontainers) in tests, no H2

**Status:** Accepted · 2026-11-02

## Context

The guarantees under test are PostgreSQL behaviour: row locks, deferred constraint triggers,
`ON CONFLICT`, check constraints and isolation levels. H2 emulates some of these partially and
others not at all, so a green H2 test would prove little.

## Decision

All integration and concurrency tests run against `postgres:17-alpine` via Testcontainers,
through the real HTTP stack on a random port. A singleton container is shared by the whole run;
the deliberately corrupting `NaiveLockingRaceTest` gets its own container.

## Consequences

- Tests prove production behaviour. CI needs Docker, which GitHub-hosted runners provide.
- Tests never clean tables (the ledger is append-only by design); each test creates its own
  customers and wallets.
