# Architecture

LedgerForge is a single Spring Boot service organised as a **modular monolith**: one
deployable, many business modules with enforced boundaries. It is deliberately not
microservices yet. The hard problems in payments (atomicity, concurrency, idempotency) are
easiest to get right, and to prove right, inside one ACID database. Other systems are
integrated through events (transactional outbox → Kafka), which is also the seam along which
the service could later be split.

## Runtime view

```
                 ┌──────────────────────────────────────────────────────────┐
  HTTPS + JWT ─► │ Spring Boot (MVC) · Tomcat on virtual threads (Java 21)  │
                 │                                                          │
                 │  security      JWT filter, role gates, ownership checks  │
                 │  api/          controllers, request/response             │
                 │  application/  use cases, tx boundaries                  │
                 │  domain/       entities, invariants, ports               │
                 │  infrastructure/ Spring Data / JDBC / Kafka adapters     │
                 │                                                          │
                 │  outbox relay ──────────────┐   risk + webhook consumers │
                 │  webhook dispatcher (virtual threads) ──► customer HTTPS │
                 └───┬───────────┬─────────────┼──────────────▲─────────────┘
                     │ Redis     │ JDBC        │ produce      │ consume
                     │           ▼             ▼              │
  ┌────────────────────────────────────────┐  ┌─────────────────┴────────────┐
  │ PostgreSQL 17 (Flyway V001..V010)     │  │ Kafka 4.1 (KRaft)            │
  │  customers, accounts (+ @Version)      │  │  ledgerforge.payments.v1     │
  │  ledger_transactions / ledger_entries  │  │  ledgerforge.transfers.v1    │
  │    (debits = credits, append-only)     │  │  ledgerforge.accounts.v1     │
  │  transfers, payments, idempotency_keys │  │  … each with a .DLT topic    │
  │  audit_events (append-only)            │  └──────────────────────────────┘
  │  outbox_events                         │
  │  processed_events, risk_alerts,        │
  │  risk_decisions, webhook_endpoints,    │
  │  webhook_deliveries (+ attempts)       │
  └────────────────────────────────────────┘
  Redis 7 (disposable): rate limits, ownership cache, risk signals
```

Still deliberately absent: dashboards (metrics are exposed via Actuator/Micrometer;
Prometheus/Grafana come next).

## Modules

| Module        | Responsibility                                                          | Depends on                       |
|---------------|-------------------------------------------------------------------------|----------------------------------|
| `shared`      | `Money`, error types, integration events, `EphemeralStore` port, retrying transaction executor, web (errors, trace ids) | nothing (shared kernel) |
| `customer`    | Customer registration                                                   | audit, shared                    |
| `account`     | Wallets and system accounts, status lifecycle, **account locking**      | customer, audit, shared          |
| `idempotency` | `Idempotency-Key` storage and replay                                    | shared                           |
| `ledger`      | Double-entry posting (the only way to move money), deposits, reconciliation | account, idempotency, shared |
| `transfer`    | Wallet-to-wallet transfers                                              | ledger, account, audit, shared (events) |
| `payment`     | Payment state machine: authorize / capture / cancel / refund / review   | ledger, account, audit, risk, ratelimit, shared (events) |
| `audit`       | Append-only audit trail, written in the business transaction            | shared                           |
| `outbox`      | Transactional outbox (implements the events port) and the relay to Kafka | shared                          |
| `messaging`   | Consumer idempotency (`processed_events`), topics, retry/backoff/DLT policy | shared                       |
| `risk`        | Synchronous rule engine and decisions; Kafka consumer for after-the-fact alerts | messaging, shared        |
| `webhook`     | Endpoint registration, Kafka fan-out, signed delivery with retries      | messaging, audit, shared         |
| `ratelimit`   | Per-account limits on top of Redis                                      | shared                           |
| `security`    | JWT resource server, roles, `AccessPolicy` ownership checks             | account, transfer, payment, webhook (read-only ports) |
| `infrastructure` | Cross-cutting technical config (clock), Redis adapter                |                                  |

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
- No `double`/`float` fields anywhere; domain amounts are `BigDecimal` or `Money`. (It even caught
  a `double` retry multiplier in the Kafka configuration.)
- No field injection.
- **The financial core never touches Redis**: `ledger`, `account`, `transfer` and `payment` cannot
  depend on the `EphemeralStore` port, and only its adapter may use Spring Data Redis.

## Request flow: `POST /api/v1/transfers`

```
BearerTokenAuthenticationFilter → JWT valid? (else 401)
  └─ URL rule: POST /api/v1/transfers requires ROLE_CUSTOMER (else 403)
TransferController   @PreAuthorize("@access.ownsAccount(#request.sourceAccountId())")  (else 403)
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
                 ├─ AuditTrail.record(TRANSFER_CREATED)           INSERT audit_events
                 ├─ events.publish(TransferCompletedEvent)        INSERT outbox_events
                 └─ store response on idempotency key (COMPLETED)
       COMMIT  ── deferred trigger re-checks that every ledger transaction balances
```

If anything fails before COMMIT, **everything** rolls back: transfer, entries, balances, the
idempotency key, the audit event and the outbox event. After COMMIT the outbox relay sends the
event to Kafka, and the risk consumer processes it idempotently (see [outbox.md](outbox.md)).

## Runtime choices

- **Java 21 virtual threads** (`spring.threads.virtual.enabled=true`): request threads are cheap,
  so the real concurrency limit is the Hikari pool (20 connections), which is the right place for it.
- **`open-in-view: false`**: no lazy loading during JSON rendering; transaction boundaries are explicit.
- **`ddl-auto: validate`**: Flyway owns the schema; Hibernate only checks that the mapping matches.
- **One error shape** (RFC 9457 problem details) for every error: stable `type`, `title`, `status`,
  `detail` and a `traceId` that also appears in the `X-Trace-Id` header and in the logs. See
  [`error-handling.md`](error-handling.md).
- **Actuator** health (with liveness/readiness probes), info and metrics. Compose enables ECS-format
  structured JSON logs.
