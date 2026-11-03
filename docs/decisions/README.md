# Architecture Decision Records

Short records of decisions that shape the code, in the
[Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
context, decision, consequences.

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-modular-monolith-package-by-feature.md) | Modular monolith, package by feature | Accepted |
| [0002](0002-money-as-bigdecimal-value-object.md) | Money as a `BigDecimal` value object | Accepted |
| [0003](0003-double-entry-ledger-with-running-balance.md) | Double-entry ledger as source of truth, with a running balance | Accepted |
| [0004](0004-pessimistic-locking-with-ordered-acquisition.md) | Pessimistic row locks with ordered acquisition | Accepted |
| [0005](0005-idempotency-in-the-business-transaction.md) | Idempotency keys stored in the business transaction | Accepted |
| [0006](0006-jpa-entities-as-domain-model.md) | JPA-annotated entities as the domain model | Accepted |
| [0007](0007-real-postgres-in-tests.md) | Real PostgreSQL (Testcontainers) in tests, no H2 | Accepted |
| [0008](0008-sealed-domain-exceptions-and-one-error-shape.md) | Sealed domain exceptions and one error shape | Accepted |
