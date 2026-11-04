# Architecture Decision Records

Short records of decisions that shape the code, in the
[Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
context, decision, consequences.

| ADR | Title | Status |
|-----|-------|--------|
| [0001](008-modular-monolith-package-by-feature.md) | Modular monolith, package by feature | Accepted |
| [0002](007-bigdecimal-for-money.md) | Money as a `BigDecimal` value object | Accepted |
| [0003](002-double-entry-ledger.md) | Double-entry ledger as source of truth, with a running balance | Accepted |
| [0004](003-pessimistic-locking.md) | Pessimistic row locks with ordered acquisition | Accepted |
| [0005](009-idempotency-in-the-business-transaction.md) | Idempotency keys stored in the business transaction | Accepted |
| [0006](010-jpa-entities-as-domain-model.md) | JPA-annotated entities as the domain model | Accepted |
| [0007](011-real-postgres-in-tests.md) | Real PostgreSQL (Testcontainers) in tests, no H2 | Accepted |
| [0008](012-sealed-domain-exceptions-and-one-error-shape.md) | Sealed domain exceptions and one error shape | Accepted |
| [0009](013-jwt-roles-and-ownership-checks.md) | Stateless JWT, non-hierarchical roles, ownership checks | Accepted |
| [0010](014-append-only-audit-in-the-business-transaction.md) | Append-only audit trail written in the business transaction | Accepted |
| [0011](004-transactional-outbox.md) | Transactional outbox for publishing events to Kafka | Accepted |
| [0012](005-kafka-event-delivery.md) | Idempotent consumers, retries with backoff, dead-letter topics | Accepted |
| [0013](015-migration-layout-and-rebaseline.md) | Migration layout, and a one-time re-baseline | Accepted |
| [0014](016-webhook-delivery-state-machine.md) | Webhook delivery as a state machine in PostgreSQL | Accepted |
| [0015](006-redis-not-source-of-truth.md) | Redis for disposable state only; PostgreSQL stays the source of truth | Accepted |
| [0016](017-rule-based-risk-engine.md) | A small synchronous rule engine for payment risk | Accepted |
| [0017](018-metrics-and-logging-rules.md) | Metrics and logging rules | Accepted |
