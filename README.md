# LedgerForge

**A production-style payment and double-entry ledger platform built with Java 21 and Spring Boot 4.**

LedgerForge supports wallets, transfers, deposits and a card-style payment lifecycle
(authorize → capture → refund) on top of an immutable double-entry ledger. Money movements are
race-safe under heavy concurrency and idempotent under client retries. Every call is
authenticated (JWT) and authorized per role and per owner. Every change is recorded in an
append-only audit trail, and every business event reaches Kafka through a transactional outbox,
consumed by idempotent consumers with retries and dead-letter topics. Each of those claims is
backed by tests against a real PostgreSQL and a real Kafka broker.

[![CI](https://github.com/kgiannopoulou/Java-Transaction-Payment-Platform/actions/workflows/ci.yml/badge.svg)](https://github.com/kgiannopoulou/Java-Transaction-Payment-Platform/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791)
![Kafka 4.1](https://img.shields.io/badge/Kafka-4.1-231f20)

> Built in phases: first the part that must be correct (ledger, concurrency, idempotency),
> then security, audit and events, each added once there was a concrete problem for it to
> solve. See the [roadmap](#roadmap).

---

## What this project demonstrates

| Area | Where to look |
|------|---------------|
| **Concurrency**: lost updates reproduced, then prevented with row locks + deterministic lock ordering; optimistic locking with retries as an alternative | [`docs/concurrency.md`](docs/concurrency.md), [`concurrency/`](src/test/java/com/kgiannopoulou/ledgerforge/concurrency) |
| **Double-entry ledger**: balanced, append-only, enforced by Java *and* PostgreSQL triggers, with raw-SQL reconciliation | [`docs/ledger-invariants.md`](docs/ledger-invariants.md), [`V003__create_ledger.sql`](src/main/resources/db/migration/V003__create_ledger.sql) |
| **Idempotency**: `Idempotency-Key` stored in the same transaction as the money movement; 100 identical concurrent requests → 1 transfer | [`docs/idempotency.md`](docs/idempotency.md) |
| **Modern Java**: records, sealed interfaces + exhaustive pattern-matching `switch`, `BigDecimal` money, virtual threads, streams | [`Money`](src/main/java/com/kgiannopoulou/ledgerforge/shared/money/Money.java), [`PaymentCommand`](src/main/java/com/kgiannopoulou/ledgerforge/payment/domain/PaymentCommand.java), [`PaymentService`](src/main/java/com/kgiannopoulou/ledgerforge/payment/application/PaymentService.java) |
| **Spring**: MVC, Bean Validation, Data JPA, transactions (propagation, isolation, programmatic retry), configuration properties, RFC 9457 problem details | [`docs/architecture.md`](docs/architecture.md) |
| **Databases**: Flyway migrations, defence-in-depth constraints (raw-SQL tested), composite foreign keys, partial unique indexes, deferred constraint triggers, `SELECT … FOR UPDATE`, `ON CONFLICT`, `REPEATABLE READ` | [`docs/database-design.md`](docs/database-design.md), [`db/migration`](src/main/resources/db/migration) |
| **Error handling**: sealed `DomainException` hierarchy, named exceptions (`InsufficientFundsException`, `AccountFrozenException`, `PaymentAlreadyCapturedException`, …), one `@RestControllerAdvice`, consistent error body with `traceId`, no stack traces leaked | [`docs/error-handling.md`](docs/error-handling.md), [`ApiExceptionHandler`](src/main/java/com/kgiannopoulou/ledgerforge/shared/web/ApiExceptionHandler.java) |
| **Security**: Spring Security JWT resource server; `CUSTOMER` / `SUPPORT` / `AUDITOR` / `ADMIN`; role gates + per-resource ownership checks; tested over HTTP (Alice → Bob's account = 403) | [`docs/security.md`](docs/security.md), [`AuthorizationApiTest`](src/test/java/com/kgiannopoulou/ledgerforge/security/AuthorizationApiTest.java) |
| **Audit trail**: append-only (`UPDATE`/`DELETE`/`TRUNCATE` rejected by triggers), written in the business transaction, with actor and trace id | [`docs/audit-trail.md`](docs/audit-trail.md), [`V008__create_audit_events.sql`](src/main/resources/db/migration/V008__create_audit_events.sql) |
| **Events**: typed event records on a sealed interface; **transactional outbox** solves the dual write; Kafka consumers with retries, exponential backoff, DLT and **idempotent processing** | [`docs/outbox.md`](docs/outbox.md), [`events/`](src/test/java/com/kgiannopoulou/ledgerforge/events) |
| **Architecture**: package-by-feature modular monolith, rules enforced by ArchUnit | [`ArchitectureTest`](src/test/java/com/kgiannopoulou/ledgerforge/architecture/ArchitectureTest.java), [ADRs](docs/decisions) |
| **Operations**: Docker multi-stage layered image, Compose (PostgreSQL + Kafka KRaft), health probes, structured JSON logs, GitHub Actions CI | [`docker/Dockerfile`](docker/Dockerfile), [`compose.yaml`](compose.yaml), [`ci.yml`](.github/workflows/ci.yml) |

## The headline result

Concurrent transfer tests exposed lost-update scenarios in a naive read-modify-write
implementation. LedgerForge uses database row locking for balance-critical operations, with
deterministic account lock ordering to prevent deadlocks. The suite runs the same attacks
against each strategy (numbers from `mvn verify`):

| Attack | Naive read-modify-write | Pessimistic (default) |
|--------|-------------------------|-----------------------|
| 20 × withdraw €10 from a €100 wallet | **20/20 "succeed"**: €200 paid out of €100; stored balance €90, ledger says −€100 | 10 succeed, 10 refused |
| 100 × withdraw €100 from a €100 wallet | – | **1 × 201, 99 × 422**, balance €0.00 |
| 500 random transfers among 10 wallets holding €1000 | – | total is still **€1000.00**; ledger reconciles |
| 100 × A→B interleaved with 100 × B→A | – | 200/200 succeed, **no deadlocks** |
| 100 × same request, same `Idempotency-Key` | – | **one** transfer; 99 responses replayed |

Optimistic locking passes the same correctness tests, at the cost of ~6 retries per request on a
hot account (234–244 retries across runs for 40 concurrent transfers). See [`docs/concurrency.md`](docs/concurrency.md).

The event pipeline is tested the same way, by staging failures on a real broker:

| Situation | Result |
|-----------|--------|
| The same `PaymentCaptured` delivered twice | **one** webhook, one delivery row, one risk alert |
| Merchant endpoint down for 2 attempts | retried with exponential backoff, delivered **once** |
| Merchant endpoint down for good | 1 + 3 attempts, then `ledgerforge.payments.v1.DLT` with the original payload; no side effects; risk scoring unaffected |
| Malformed record | straight to the DLT, no retries |
| Transfer rejected (insufficient funds) | no ledger entries, **no audit event, no outbox event** |

## Architecture

```
            HTTPS + Bearer JWT
                │
┌───────────────▼──────────────────────────────────────────────────────┐
│ Spring Boot 4 · Tomcat on virtual threads                            │
│  security: JWT filter → role gates → @PreAuthorize ownership checks  │
│                                                                      │
│  customer  account  ledger  transfer  payment  idempotency  audit    │
│  ── each: api / application / domain / infrastructure ──             │
│  outbox (relay)   risk + webhook (Kafka consumers)   messaging       │
│  shared: Money · errors · events · RetryingTransactionExecutor       │
└───────┬──────────────────────────────────────────────▲───────────────┘
        │ JDBC (one transaction per command)           │ consume
┌───────▼──────────────────────────────┐   relay   ┌────┴─────────────────┐
│ PostgreSQL 17 · Flyway               │ ────────► │ Kafka 4.1 (KRaft)    │
│  accounts · ledger (append-only)     │           │  payments.v1 (+.DLT) │
│  transfers · payments · idempotency  │           │  transfers.v1 (+.DLT)│
│  audit_events (append-only)          │           │  accounts.v1  (+.DLT)│
│  outbox_events · processed_events    │           └──────────────────────┘
│  risk_alerts · webhook_deliveries    │
└──────────────────────────────────────┘
```

Every money movement follows one path:

```
authenticate (JWT) → authorize (role + ownership) → lock accounts (sorted, FOR UPDATE)
   → validate under lock → LedgerService.post → balanced LedgerTransaction (DEBIT + CREDIT)
   → balances updated → INSERT audit_event → INSERT outbox_event → COMMIT
   → PostgreSQL re-checks Σ debits = Σ credits at commit
   → (later) outbox relay → Kafka → risk engine / merchant webhooks (idempotent)
```

If any step before COMMIT fails, **everything** rolls back: business record, ledger entries,
balances, idempotency key, audit event and outbox event.

## Quick start

Requirements: **JDK 21** and **Docker**.

```bash
# Run everything in containers (PostgreSQL, Kafka, app; dev tokens enabled)
docker compose up --build                  # API on http://localhost:8080

# …or run the app locally against the Compose infrastructure
docker compose up -d postgres kafka        # PostgreSQL on :5437, Kafka on :9094
LEDGERFORGE_DEV_TOKENS=true ./mvnw spring-boot:run

# Full test suite (unit, integration, security, concurrency, Kafka, architecture).
# Needs Docker for Testcontainers (PostgreSQL 17 + Kafka 4.1).
./mvnw verify
```

## API walkthrough

```bash
J='Content-Type: application/json'
token() { curl -s localhost:8080/api/v1/auth/token -H "$J" -d "$1" | jq -r .accessToken; }  # dev only

# 0. An operator token
ADMIN="Authorization: Bearer $(token '{"subject":"ops","roles":["ADMIN"]}')"

# 1. Customers and wallets (onboarding is an ADMIN operation)
ALICE=$(curl -s localhost:8080/api/v1/customers -H "$J" -H "$ADMIN" \
  -d '{"email":"alice@example.com","fullName":"Alice"}' | jq -r .id)
BOB=$(curl -s localhost:8080/api/v1/customers -H "$J" -H "$ADMIN" \
  -d '{"email":"bob@example.com","fullName":"Bob"}' | jq -r .id)
AL="Authorization: Bearer $(token "{\"subject\":\"$ALICE\",\"roles\":[\"CUSTOMER\"],\"customerId\":\"$ALICE\"}")"
BO="Authorization: Bearer $(token "{\"subject\":\"$BOB\",\"roles\":[\"CUSTOMER\"],\"customerId\":\"$BOB\"}")"

A=$(curl -s localhost:8080/api/v1/accounts -H "$J" -H "$AL" \
  -d "{\"customerId\":\"$ALICE\",\"currency\":\"EUR\"}" | jq -r .id)
B=$(curl -s localhost:8080/api/v1/accounts -H "$J" -H "$BO" \
  -d "{\"customerId\":\"$BOB\",\"currency\":\"EUR\"}" | jq -r .id)

curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/accounts/$B -H "$AL"   # 403: not Alice's

# 2. Fund Alice (ADMIN; DEBIT external-funding, CREDIT Alice)
curl -s localhost:8080/api/v1/accounts/$A/deposits -H "$J" -H "$ADMIN" \
  -d '{"amount":"100.00","currency":"EUR"}'

# 3. Alice transfers, with an idempotency key (run it twice: the second call is replayed)
curl -si localhost:8080/api/v1/transfers -H "$J" -H "$AL" \
  -H 'Idempotency-Key: order-42' \
  -d "{\"sourceAccountId\":\"$A\",\"destinationAccountId\":\"$B\",\"amount\":\"25.00\",\"currency\":\"EUR\"}"
```

```json
{
  "id": "6f1c…",
  "status": "COMPLETED",
  "sourceAccountId": "…",
  "destinationAccountId": "…",
  "amount": "25.00",
  "currency": "EUR",
  "ledgerTransactionId": "…",
  "createdAt": "2026-11-02T08:00:00Z",
  "completedAt": "2026-11-02T08:00:00Z"
}
```

```bash
# 4. A payment: Alice pays Bob's shop €30 (payer authorizes, merchant captures and refunds)
P=$(curl -s localhost:8080/api/v1/payments -H "$J" -H "$AL" \
  -d "{\"payerAccountId\":\"$A\",\"merchantAccountId\":\"$B\",\"amount\":\"30.00\",\"currency\":\"EUR\"}" | jq -r .id)
curl -s -X POST localhost:8080/api/v1/payments/$P/authorize -H "$AL"   # funds held
curl -s -X POST localhost:8080/api/v1/payments/$P/capture -H "$BO"     # funds to merchant → PaymentCaptured on Kafka
curl -s localhost:8080/api/v1/payments/$P/refund -H "$J" -H "$BO" \
  -d '{"amount":"10.00","currency":"EUR"}'                              # PARTIALLY_REFUNDED

# 5. History, books and the audit trail
curl -s "localhost:8080/api/v1/accounts/$A/transactions?page=0&size=20" -H "$AL"
AUD="Authorization: Bearer $(token '{"subject":"ava","roles":["AUDITOR"]}')"
curl -s localhost:8080/api/v1/admin/reconciliation -H "$AUD"            # {"consistent": true, ...}
curl -s "localhost:8080/api/v1/audit-events?entityId=$P" -H "$AUD"      # CREATED → AUTHORIZED → CAPTURED → REFUNDED
```

### Endpoints

"own" = the token's `customer_id` owns the wallet / is a party to the transfer or payment.

| Method | Path | Who | Notes |
|--------|------|-----|-------|
| `POST` | `/api/v1/customers` | ADMIN | |
| `GET`  | `/api/v1/customers/{id}` · `/{id}/accounts` | own · SUPPORT · ADMIN | |
| `POST` | `/api/v1/accounts` | own · ADMIN | one wallet per customer per currency (EUR, USD, GBP) |
| `GET`  | `/api/v1/accounts/{id}` · `/{id}/transactions` | own · SUPPORT · AUDITOR · ADMIN | balance, `version`; ledger entries newest first |
| `POST` | `/api/v1/accounts/{id}/freeze` · `/unfreeze` · `/close` | ADMIN | close requires zero balance |
| `POST` | `/api/v1/accounts/{id}/deposits` | ADMIN | idempotent |
| `POST` | `/api/v1/transfers` | owner of the source wallet | idempotent |
| `GET`  | `/api/v1/transfers/{id}` | either party · SUPPORT · AUDITOR · ADMIN | |
| `POST` | `/api/v1/payments` · `/{id}/authorize` | payer | idempotent |
| `POST` | `/api/v1/payments/{id}/capture` · `/refund` | merchant | idempotent; invalid transitions → 409 |
| `POST` | `/api/v1/payments/{id}/cancel` | payer or merchant | |
| `GET`  | `/api/v1/payments/{id}` | either party · SUPPORT · AUDITOR · ADMIN | |
| `GET`  | `/api/v1/ledger/transactions/{id}` | SUPPORT · AUDITOR · ADMIN | transaction with its entries |
| `GET`  | `/api/v1/risk/alerts` | SUPPORT · AUDITOR · ADMIN | produced by the Kafka risk consumer |
| `GET`  | `/api/v1/audit-events` | AUDITOR · ADMIN | filter by `entityType`, `entityId`, `actor`, `action` |
| `GET`  | `/api/v1/admin/reconciliation` | AUDITOR · ADMIN | raw-SQL invariant check |
| `POST` | `/api/v1/auth/token` | anyone, **dev only** | disabled unless `LEDGERFORGE_DEV_TOKENS=true` |
| `GET`  | `/actuator/health` (+ `/liveness`, `/readiness`) | anyone | details for ADMIN only |

Every error, whether it comes from the domain, validation or the framework, has the same shape
([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`):

```json
{
  "type": "INSUFFICIENT_FUNDS",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Account 3f0c… has insufficient available funds",
  "traceId": "9d1c2f0e5b8a4c7d9e6f1a2b3c4d5e6f"
}
```

`type` is stable and safe to branch on; `traceId` matches the `X-Trace-Id` response header and
every server log line for that request. Unexpected failures return a generic `500 INTERNAL_ERROR`;
stack traces are logged, never returned. Full catalogue: [`docs/error-handling.md`](docs/error-handling.md).

## Tests

`./mvnw verify` runs **172 tests** against real PostgreSQL 17 and Kafka 4.1 via Testcontainers:

| Suite | What it proves |
|-------|----------------|
| `MoneyTest`, `AccountTest`, `LedgerTransactionTest`, `PaymentStateMachineTest` | value-object rules, balancing, the full payment transition table (every from→to pair) |
| `CustomerAccountApiTest`, `TransferApiTest`, `PaymentApiTest` | REST behaviour, validation, status codes, rollback on failure |
| `LedgerDatabaseGuaranteesTest` | raw SQL cannot create unbalanced, mismatched-currency, negative or edited ledger data |
| `ErrorResponseApiTest`, `ApiExceptionHandlerTest` | one error shape for domain *and* framework errors, `traceId` header/body/propagation, named exception types, no leaked internals on 500 |
| `AuthorizationApiTest` | the role table and every ownership rule over HTTP with real JWTs: Alice → Bob's account **403**; forged / expired / foreign-issuer tokens 401; admins cannot spend customer money |
| `AuditTrailApiTest` | actor + trace id recorded; full payment lifecycle audited; rejected operations leave no event; `UPDATE`/`DELETE`/`TRUNCATE` rejected |
| `TransactionalOutboxTest`, `OutboxRelayTest` | events commit with the state change (and only then); relay ordering, backoff, no overtaking within an aggregate, purge |
| `EventPipelineKafkaTest`, `ConsumerFailureHandlingKafkaTest` | REST → outbox → Kafka → consumers end to end; duplicates processed once; retries with backoff; DLT; poison records |
| `IdempotencyApiTest` | replay, 409 on body mismatch, failure releases the key, scoping |
| `PessimisticLockingConcurrencyTest` | conservation over 500 concurrent transfers, double-spend, deadlock-freedom, retry storm, concurrent captures, whole-ledger reconciliation |
| `OptimisticLockingConcurrencyTest` | same guarantees via `@Version` + retries |
| `NaiveLockingRaceTest` | **reproduces** lost updates and over-spending, so the fixes are justified |
| `ArchitectureTest` | module boundaries, layering, "only the ledger moves money", no floating-point money |

## Project layout

```
ledgerforge/
├── README.md
├── docs/
│   ├── architecture.md · domain-model.md · concurrency.md · idempotency.md · ledger-invariants.md
│   ├── error-handling.md · security.md · audit-trail.md · outbox.md · database-design.md
│   └── decisions/                 ADR 0001–0013
├── src/main/java/com/kgiannopoulou/ledgerforge/
│   ├── customer/ account/ ledger/ transfer/ payment/ idempotency/
│   │     └── api/ application/ domain/ infrastructure/
│   ├── security/                  JWT config, roles, AccessPolicy (ownership)
│   ├── audit/                     append-only audit trail
│   ├── outbox/                    transactional outbox + relay to Kafka
│   ├── messaging/                 consumer idempotency, retry/DLT config, topics
│   ├── risk/ webhook/             Kafka consumers
│   ├── shared/                    Money, error hierarchy, integration events, transactions, web
│   └── infrastructure/            cross-cutting config
├── src/main/resources/db/migration/   Flyway V001–V010
├── src/test/java/…                unit · api · security · audit · outbox · events (Kafka) · concurrency · architecture
├── docker/Dockerfile
├── compose.yaml
└── .github/workflows/ci.yml
```

## Configuration

| Property | Default | |
|----------|---------|---|
| `ledgerforge.concurrency.locking-mode` | `PESSIMISTIC` | `PESSIMISTIC` · `OPTIMISTIC` · `NAIVE` (tests only) |
| `ledgerforge.concurrency.max-attempts` | `8` | retries for deadlocks / version conflicts |
| `ledgerforge.concurrency.initial-backoff` / `max-backoff` | `10ms` / `200ms` | full-jitter exponential backoff |
| `ledgerforge.idempotency.ttl` | `24h` | how long keys are replayed |
| `LEDGERFORGE_DB_URL` / `_USER` / `_PASSWORD` | `localhost:5437` | |
| `LEDGERFORGE_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | |
| `LEDGERFORGE_JWT_SECRET` | dev value | HS256 key, ≥ 32 bytes; **set in every real deployment** |
| `LEDGERFORGE_DEV_TOKENS` | `false` | enables `POST /api/v1/auth/token` (local/demo only) |
| `ledgerforge.outbox.relay.poll-interval-ms` / `batch-size` | `500` / `100` | |
| `ledgerforge.outbox.relay.initial-backoff` / `max-backoff` | `1s` / `5m` | per-event publish retry |
| `ledgerforge.kafka.retry.initial-interval` / `multiplier` / `max-interval` / `max-retries` | `500ms` / `2` / `10s` / `4` | consumer retries before the DLT |
| `ledgerforge.risk.large-amount-threshold` | `10000.00` | |

## Roadmap

- [x] **Phase 1**: Spring Boot 4 / Java 21 bootstrap, package-by-feature layout, docs + ADRs
- [x] **Phase 2**: `Money` value object
- [x] **Phase 3**: customers, wallets, account lifecycle
- [x] **Phase 4**: double-entry ledger with database-enforced invariants
- [x] **Phase 5**: atomic transfers
- [x] **Phase 6**: race conditions reproduced; optimistic vs pessimistic locking
- [x] **Phase 7**: concurrency test suite on real PostgreSQL
- [x] **Phase 8**: idempotency keys
- [x] **Phase 9**: payment lifecycle state machine with sealed commands
- [x] **Phase 10**: domain-specific exceptions, consistent error responses with `traceId`, no leaked stack traces
- [x] **Phase 11**: Spring Security with JWT; CUSTOMER / SUPPORT / AUDITOR / ADMIN; ownership checks; authorization tests
- [x] **Phase 12**: append-only audit trail in the business transaction
- [x] **Phase 13**: Kafka with strongly typed event records (risk engine, merchant webhooks)
- [x] **Phase 14**: transactional outbox (dual-write problem solved)
- [x] **Phase 15**: Kafka failure handling: retries, exponential backoff, dead-letter topics, idempotent consumers
- [ ] Redis rate limiting
- [ ] Micrometer metrics → Prometheus → Grafana, tracing
- [ ] Reconciliation jobs, DLT replay tooling
- [ ] OpenAPI documentation

## License

[MIT](LICENSE)
