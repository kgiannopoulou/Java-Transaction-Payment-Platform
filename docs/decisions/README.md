# Architecture Decision Records

Short records of the decisions that shape the code: the context, what else was considered, what was
chosen, and what it costs. The first seven are the core of the design.

| ADR | Decision |
|-----|----------|
| [001](001-postgresql-as-source-of-truth.md) | PostgreSQL as the source of truth |
| [002](002-double-entry-ledger.md) | Double-entry ledger with a running balance |
| [003](003-pessimistic-locking.md) | Pessimistic row locks with ordered acquisition |
| [004](004-transactional-outbox.md) | Transactional outbox |
| [005](005-kafka-event-delivery.md) | Kafka event delivery |
| [006](006-redis-not-source-of-truth.md) | Redis is not a source of truth |
| [007](007-bigdecimal-for-money.md) | BigDecimal for money |
| [008](008-modular-monolith-package-by-feature.md) | Modular monolith, package by feature |
| [009](009-idempotency-in-the-business-transaction.md) | Idempotency keys stored in the business transaction |
| [010](010-jpa-entities-as-domain-model.md) | JPA-annotated entities as the domain model |
| [011](011-real-postgres-in-tests.md) | Real PostgreSQL (Testcontainers) in tests, no H2 |
| [012](012-sealed-domain-exceptions-and-one-error-shape.md) | Sealed domain exceptions and one error shape |
| [013](013-jwt-roles-and-ownership-checks.md) | Stateless JWT, non-hierarchical roles, ownership checks |
| [014](014-append-only-audit-in-the-business-transaction.md) | Append-only audit trail written in the business transaction |
| [015](015-migration-layout-and-rebaseline.md) | Migration layout, and a one-time re-baseline |
| [016](016-webhook-delivery-state-machine.md) | Webhook delivery as a state machine in PostgreSQL |
| [017](017-rule-based-risk-engine.md) | A small synchronous rule engine for payment risk |
| [018](018-metrics-and-logging-rules.md) | Metrics and logging rules |
