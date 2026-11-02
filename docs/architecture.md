# Architecture

LedgerForge is a single Spring Boot service organised as a **modular monolith**: one
deployable, many business modules with enforced boundaries. It is deliberately not
microservices yet. The hard problems in payments (atomicity, concurrency, idempotency) are
easiest to get right, and to prove right, inside one ACID database. Splitting the service
comes later, once the events that would cross service boundaries exist (outbox → Kafka).

## Current scope (milestone 1: the financial core)

```
                 ┌──────────────────────────────────────────────┐
  HTTP/JSON ───► │              Spring Boot (MVC)                │
                 │  Tomcat on virtual threads (Java 21)          │
                 │                                              │
                 │  api/          controllers, request/response  │
                 │  application/  use cases, tx boundaries       │
                 │  domain/       entities, invariants, ports    │
                 │  infrastructure/ Spring Data adapters         │
                 └───────────────────────┬──────────────────────┘
                                         │ JDBC (Hikari)
                                         ▼
                 ┌──────────────────────────────────────────────┐
                 │ PostgreSQL 17                                 │
                 │  customers, accounts (balance + @Version)     │
                 │  ledger_transactions, ledger_entries          │
                 │    ├─ deferred trigger: debits = credits      │
                 │    ├─ trigger: entry currency = account's     │
                 │    └─ trigger: append-only                    │
                 │  transfers, payments, idempotency_keys        │
                 │  Flyway migrations V1..V6                     │
                 └──────────────────────────────────────────────┘
```

## Target architecture (later milestones)

```
API client ──HTTPS──► Spring Boot API ──► PostgreSQL (ledger, payments, outbox)
                           │                    │
                           ▼                    ▼ outbox relay
                         Redis               Kafka ──► webhooks / audit & risk / notifications
                   (rate limits, cache)
                                   Prometheus ──► Grafana
```

Kafka, Redis, JWT and dashboards are intentionally **not** in milestone 1. Each will be added
when there is a concrete problem for it to solve, and the problem will be documented first.

## Modules

| Module        | Responsibility                                                          | Depends on                       |
|---------------|-------------------------------------------------------------------------|----------------------------------|
| `shared`      | `Money`, error types, retrying transaction executor, base entity         | nothing (shared kernel)          |
| `customer`    | Customer registration                                                   | shared                           |
| `account`     | Wallets and system accounts, status lifecycle, **account locking**      | customer, shared                 |
| `idempotency` | `Idempotency-Key` storage and replay                                    | shared                           |
| `ledger`      | Double-entry posting (the only way to move money), deposits, reconciliation | account, idempotency, shared |
| `transfer`    | Wallet-to-wallet transfers                                              | ledger, account, idempotency     |
| `payment`     | Payment state machine: authorize / capture / cancel / refund            | ledger, account, idempotency     |
| `security`, `audit` | Placeholders for later milestones                                 |                                  |
| `infrastructure` | Cross-cutting technical config (clock)                               |                                  |

Each feature module has the same internal layout:

```
transfer/
├── api/             TransferController, CreateTransferRequest, TransferResponse
├── application/     TransferService (transaction boundary, orchestration)
├── domain/          Transfer, TransferStatus, TransferRepository (port)
└── infrastructure/  JpaTransferRepository (Spring Data adapter, package-private)
```

## Rules enforced by `ArchitectureTest` (ArchUnit)

- Feature modules have no dependency cycles.
- `domain` never depends on `api`, `application`, `infrastructure`, or Spring Web.
- `application` never depends on `api` or `infrastructure`.
- Nothing outside an `infrastructure` package uses an infrastructure class.
- `shared` does not depend on any feature.
- `@RestController`s live in `api` packages.
- **Only the `ledger` module may call `Account.debit/credit`**, so every balance change is part of
  a balanced ledger transaction.
- No `double`/`float` fields anywhere; domain amounts are `BigDecimal` or `Money`.
- No field injection.

## Request flow: `POST /api/v1/transfers`

```
TransferController
  └─ IdempotentOperations.execute(scope="transfer", key, request, ...)
       └─ RetryingTransactionExecutor.execute  ── BEGIN (retried on deadlock / version conflict)
            ├─ INSERT idempotency key ... ON CONFLICT DO NOTHING; SELECT ... FOR UPDATE
            ├─ completed already?  → return stored response (no money moves)
            └─ TransferService.transfer
                 ├─ AccountLocks.lockForBalanceChange([source, destination])  (sorted, FOR UPDATE)
                 ├─ validate: wallets, ACTIVE, currencies, sufficient funds   (after locking)
                 ├─ INSERT transfer (PENDING)
                 ├─ LedgerService.post → LedgerTransaction.record (balanced or throw)
                 │     ├─ account.debit / account.credit
                 │     └─ INSERT ledger_transaction + 2 ledger_entries
                 ├─ transfer.complete → UPDATE transfer (COMPLETED)
                 └─ store response on idempotency key (COMPLETED)
       COMMIT  ── deferred trigger re-checks that every ledger transaction balances
```

If anything fails before COMMIT, **everything** rolls back: transfer, entries, balances and
the idempotency key.

## Runtime choices

- **Java 21 virtual threads** (`spring.threads.virtual.enabled=true`): request threads are cheap,
  so the real concurrency limit is the Hikari pool (20 connections), which is the right place for it.
- **`open-in-view: false`**: no lazy loading during JSON rendering; transaction boundaries are explicit.
- **`ddl-auto: validate`**: Flyway owns the schema; Hibernate only checks that the mapping matches.
- **RFC 9457 problem details** for every error, each with a stable machine-readable `code`.
- **Actuator** health (with liveness/readiness probes), info and metrics. Compose enables ECS-format
  structured JSON logs.
