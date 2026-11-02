# LedgerForge

**A production-style payment and double-entry ledger platform built with Java 21 and Spring Boot 4.**

LedgerForge supports wallets, transfers, deposits and a card-style payment lifecycle
(authorize → capture → refund) on top of an immutable double-entry ledger. Money movements are
race-safe under heavy concurrency and idempotent under client retries, and those claims are
backed by tests that attack the running API with hundreds of simultaneous requests against a
real PostgreSQL.

[![CI](https://github.com/kgiannopoulou/ledgerforge/actions/workflows/ci.yml/badge.svg)](https://github.com/kgiannopoulou/ledgerforge/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791)

> **Milestone 1: the financial core.** Kafka, Redis, JWT and Grafana are deliberately *not*
> here yet. The platform starts with the part that must be correct and becomes
> production-grade step by step. See the [roadmap](#roadmap).

---

## What this project demonstrates

| Area | Where to look |
|------|---------------|
| **Concurrency**: lost updates reproduced, then prevented with row locks + deterministic lock ordering; optimistic locking with retries as an alternative | [`docs/concurrency.md`](docs/concurrency.md), [`concurrency/`](src/test/java/com/kgiannopoulou/ledgerforge/concurrency) |
| **Double-entry ledger**: balanced, append-only, enforced by Java *and* PostgreSQL triggers, with raw-SQL reconciliation | [`docs/ledger-invariants.md`](docs/ledger-invariants.md), [`V2__ledger.sql`](src/main/resources/db/migration/V2__ledger.sql) |
| **Idempotency**: `Idempotency-Key` stored in the same transaction as the money movement; 100 identical concurrent requests → 1 transfer | [`docs/idempotency.md`](docs/idempotency.md) |
| **Modern Java**: records, sealed interfaces + exhaustive pattern-matching `switch`, `BigDecimal` money, virtual threads, streams | [`Money`](src/main/java/com/kgiannopoulou/ledgerforge/shared/money/Money.java), [`PaymentCommand`](src/main/java/com/kgiannopoulou/ledgerforge/payment/domain/PaymentCommand.java), [`PaymentService`](src/main/java/com/kgiannopoulou/ledgerforge/payment/application/PaymentService.java) |
| **Spring**: MVC, Bean Validation, Data JPA, transactions (propagation, isolation, programmatic retry), configuration properties, RFC 9457 problem details | [`docs/architecture.md`](docs/architecture.md) |
| **Databases**: Flyway migrations, constraints, partial unique indexes, deferred constraint triggers, `SELECT … FOR UPDATE`, `ON CONFLICT`, `REPEATABLE READ` | [`db/migration`](src/main/resources/db/migration) |
| **Architecture**: package-by-feature modular monolith, rules enforced by ArchUnit | [`ArchitectureTest`](src/test/java/com/kgiannopoulou/ledgerforge/architecture/ArchitectureTest.java), [ADRs](docs/decisions) |
| **Operations**: Docker multi-stage layered image, Compose, health probes, structured JSON logs, GitHub Actions CI | [`docker/Dockerfile`](docker/Dockerfile), [`compose.yaml`](compose.yaml), [`ci.yml`](.github/workflows/ci.yml) |

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

## Architecture

```
            HTTP/JSON
                │
┌───────────────▼──────────────────────────────────────────────────┐
│ Spring Boot 4 · Tomcat on virtual threads                         │
│                                                                    │
│  customer   account   ledger   transfer   payment   idempotency    │
│  ── each: api / application / domain / infrastructure ──           │
│                                                                    │
│  shared: Money · errors · RetryingTransactionExecutor              │
└───────────────┬──────────────────────────────────────────────────┘
                │ JDBC (Hikari, 20 connections)
┌───────────────▼──────────────────────────────────────────────────┐
│ PostgreSQL 17 · Flyway                                             │
│  accounts (balance, @Version) · ledger_transactions/entries        │
│  (deferred "debits = credits" trigger, append-only triggers)       │
│  transfers · payments · idempotency_keys                           │
└──────────────────────────────────────────────────────────────────┘
```

Every money movement follows one path:

```
lock accounts (sorted, FOR UPDATE) → validate under lock → LedgerService.post
   → balanced LedgerTransaction (DEBIT + CREDIT) → balances updated → COMMIT
   → PostgreSQL re-checks Σ debits = Σ credits at commit
```

If any step fails, **everything** rolls back: business record, ledger entries, balances and
idempotency key.

## Quick start

Requirements: **JDK 21** and **Docker**.

```bash
# Run everything in containers
docker compose up --build            # API on http://localhost:8080

# …or run the app locally against the Compose database
docker compose up -d postgres        # PostgreSQL on localhost:5437
./mvnw spring-boot:run

# Full test suite (unit, integration, concurrency, architecture). Needs Docker for Testcontainers.
./mvnw verify
```

## API walkthrough

```bash
# 1. Customers and wallets
ALICE=$(curl -s localhost:8080/api/v1/customers -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","fullName":"Alice"}' | jq -r .id)
BOB=$(curl -s localhost:8080/api/v1/customers -H 'Content-Type: application/json' \
  -d '{"email":"bob@example.com","fullName":"Bob"}' | jq -r .id)

A=$(curl -s localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$ALICE\",\"currency\":\"EUR\"}" | jq -r .id)
B=$(curl -s localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$BOB\",\"currency\":\"EUR\"}" | jq -r .id)

# 2. Fund Alice (DEBIT external-funding, CREDIT Alice)
curl -s localhost:8080/api/v1/accounts/$A/deposits -H 'Content-Type: application/json' \
  -d '{"amount":"100.00","currency":"EUR"}'

# 3. Transfer with an idempotency key (run it twice: the second call is replayed, not re-executed)
curl -si localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
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
# 4. A payment: Alice pays Bob's shop €30, then gets €10 back
P=$(curl -s localhost:8080/api/v1/payments -H 'Content-Type: application/json' \
  -d "{\"payerAccountId\":\"$A\",\"merchantAccountId\":\"$B\",\"amount\":\"30.00\",\"currency\":\"EUR\"}" | jq -r .id)
curl -s -X POST localhost:8080/api/v1/payments/$P/authorize   # funds held
curl -s -X POST localhost:8080/api/v1/payments/$P/capture     # funds to merchant
curl -s localhost:8080/api/v1/payments/$P/refund -H 'Content-Type: application/json' \
  -d '{"amount":"10.00","currency":"EUR"}'                     # PARTIALLY_REFUNDED

# 5. History and books
curl -s "localhost:8080/api/v1/accounts/$A/transactions?page=0&size=20"
curl -s localhost:8080/api/v1/admin/reconciliation              # {"consistent": true, ...}
```

### Endpoints

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/api/v1/customers` | |
| `GET`  | `/api/v1/customers/{id}` · `/api/v1/customers/{id}/accounts` | |
| `POST` | `/api/v1/accounts` | one wallet per customer per currency (EUR, USD, GBP) |
| `GET`  | `/api/v1/accounts/{id}` | includes `balance` and `version` |
| `POST` | `/api/v1/accounts/{id}/freeze` · `/unfreeze` · `/close` | close requires zero balance |
| `POST` | `/api/v1/accounts/{id}/deposits` | idempotent |
| `GET`  | `/api/v1/accounts/{id}/transactions` | ledger entries, newest first, paginated |
| `POST` | `/api/v1/transfers` | idempotent |
| `GET`  | `/api/v1/transfers/{id}` | |
| `POST` | `/api/v1/payments` | idempotent |
| `POST` | `/api/v1/payments/{id}/authorize` · `/capture` · `/cancel` · `/refund` | idempotent; invalid transitions → 409 |
| `GET`  | `/api/v1/payments/{id}` | |
| `GET`  | `/api/v1/ledger/transactions/{id}` | transaction with its entries |
| `GET`  | `/api/v1/admin/reconciliation` | raw-SQL invariant check |
| `GET`  | `/actuator/health` · `/actuator/health/liveness` · `/actuator/health/readiness` | |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details with a stable `code`:

```json
{ "status": 422, "title": "Unprocessable Content",
  "detail": "Account 3f0c… has insufficient funds", "code": "insufficient_funds" }
```

## Tests

`./mvnw verify` runs **123 tests**, all against real PostgreSQL 17 via Testcontainers:

| Suite | What it proves |
|-------|----------------|
| `MoneyTest`, `AccountTest`, `LedgerTransactionTest`, `PaymentStateMachineTest` | value-object rules, balancing, the full payment transition table (every from→to pair) |
| `CustomerAccountApiTest`, `TransferApiTest`, `PaymentApiTest` | REST behaviour, validation, status codes, rollback on failure |
| `LedgerDatabaseGuaranteesTest` | raw SQL cannot create unbalanced, mismatched-currency, negative or edited ledger data |
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
│   └── decisions/                 ADR 0001–0007
├── src/main/java/com/kgiannopoulou/ledgerforge/
│   ├── customer/ account/ ledger/ transfer/ payment/ idempotency/
│   │     └── api/ application/ domain/ infrastructure/
│   ├── shared/                    Money, errors, transactions, web
│   ├── security/ audit/           placeholders for later milestones
│   └── infrastructure/            cross-cutting config
├── src/main/resources/db/migration/   Flyway V1–V6
├── src/test/java/…                unit · api · concurrency · architecture
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

## Roadmap

- [x] **Phase 0**: goals, docs, ADRs
- [x] **Phase 1**: Spring Boot 4 / Java 21 bootstrap, package-by-feature layout
- [x] **Phase 2**: `Money` value object
- [x] **Phase 3**: customers, wallets, account lifecycle
- [x] **Phase 4**: double-entry ledger with database-enforced invariants
- [x] **Phase 5**: atomic transfers
- [x] **Phase 6**: race conditions reproduced; optimistic vs pessimistic locking
- [x] **Phase 7**: concurrency test suite on real PostgreSQL
- [x] **Phase 8**: idempotency keys
- [x] **Phase 9**: payment lifecycle state machine with sealed commands
- [ ] Transactional outbox → Kafka (webhooks, audit/risk, notifications)
- [ ] Spring Security (API keys / JWT, ownership checks), Redis rate limiting
- [ ] Micrometer metrics → Prometheus → Grafana, tracing
- [ ] Failure recovery: retries, dead-letter handling, reconciliation jobs
- [ ] OpenAPI documentation

## License

[MIT](LICENSE)
