# ADR 010: JPA-annotated entities as the domain model

**Status:** Accepted · 2026-11-02

## Context

Strict hexagonal architecture keeps persistence-free domain objects and maps them to separate
JPA entities. That doubles the model and the mapping code for little benefit while the domain
and the schema are 1:1.

## Decision

Domain entities carry JPA annotations but **own their invariants**: factories, guarded state
transitions, no public setters. Persistence is reached through domain-level repository
interfaces (`TransferRepository`), implemented by package-private Spring Data adapters in each
module's `infrastructure` package:

```java
interface JpaTransferRepository extends JpaRepository<Transfer, UUID>, TransferRepository { }
```

Entities implement `Persistable` via `BaseEntity`, so application-assigned UUIDs produce plain
INSERTs instead of `merge()` (which would cost an extra SELECT and skip PERSIST cascades).

## Consequences

- One model, no mapping layer; application code depends only on the repository port.
- The domain is not persistence-ignorant. If schema and model diverge, a dedicated persistence
  model can be introduced per module without touching application code.
