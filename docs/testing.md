# Testing

The goal is confidence that money cannot be lost, not a coverage percentage. A line can be covered
by a test that proves nothing. So tests are organised by what they can prove, and the most
important ones state invariants directly.

```
                ┌────────────────────────┐
                │          E2E           │  whole journeys: HTTP in, signed webhook out,
                │    @Tag("e2e")         │  real PostgreSQL + Redis + Kafka
                └────────────────────────┘
          ┌──────────────────────────────────┐
          │           Integration            │  one feature over real HTTP and real
          │       @Tag("integration")        │  infrastructure, in Testcontainers
          └──────────────────────────────────┘
    ┌──────────────────────────────────────────────┐
    │              Unit / domain                   │  no Spring, no containers, milliseconds:
    │              (untagged)                      │  rules, state machines, properties
    └──────────────────────────────────────────────┘
```

```bash
./mvnw verify                                  # everything
./mvnw test -DexcludedGroups=integration       # unit tier only: seconds, no Docker
./mvnw test -Dgroups=integration -DexcludedGroups=concurrency,e2e
./mvnw test -Dgroups=concurrency               # races, locks, random concurrent traffic
./mvnw test -Dgroups=e2e                       # the full pipeline
```

The tags come from the base classes (`ApiTestSupport`, `KafkaIntegrationTest`), so a new test
lands in the right tier automatically.

## Tools

| Tool | Used for | Why |
|------|----------|-----|
| **JUnit 5 API** (on JUnit 6) | everything | |
| **Testcontainers** | PostgreSQL, Kafka, Redis | the guarantees under test (row locks, deferred triggers, `SKIP LOCKED`, Lua scripts, consumer groups) are behaviour of those products, so a fake would prove nothing ([ADR 011](decisions/011-real-postgres-in-tests.md)) |
| **Mockito** | `EphemeralStoreFallbacksTest` | only where a mock does what a real system cannot, such as a Redis read working and the following write failing |
| **jqwik** | `DomainPropertyTest` | generates inputs and shrinks a failure to a minimal example |
| **ArchUnit** | `ArchitectureTest` | architecture rules that fail the build |
| **JDK `HttpClient`, `HttpServer`** | API tests, webhook receiver | real sockets, thread pools and timeouts |

Mockito is deliberately rare. Mocking a repository in a service test proves the service calls the
mock, while the interesting failures here are in the SQL, the locks and the transactions. Fakes are
used where logic really is separate from infrastructure, as in `OutboxRelayTest`.

## The scenarios that matter most

| Scenario | Test |
|----------|------|
| Transfer succeeds | `TransferApiTest.aliceSendsBobTwentyEuros` |
| Insufficient balance fails | `TransferApiTest.insufficientFundsRollsBackEverything` |
| Ledger balances | `LedgerTransactionTest`, `LedgerDatabaseGuaranteesTest`, `MoneyConservationPropertyTest` |
| Rollback leaves no entries | `TransferApiTest.insufficientFundsRollsBackEverything`, `LedgerDatabaseGuaranteesTest.unbalancedTransactionIsRejectedAtCommitAndLeavesNoTrace` |
| 100 concurrent transfers | `PessimisticLockingConcurrencyTest` |
| Duplicate idempotency request | `IdempotencyApiTest`, plus 100 identical concurrent requests in `PessimisticLockingConcurrencyTest` |
| Payment state transitions | `PaymentStateMachineTest` (every from → to pair), `DomainPropertyTest.noCommandSequenceBreaksAPayment` |
| Refund cannot exceed captured amount | `PaymentStateMachineTest.cannotRefundMoreThanCaptured`, `PaymentApiTest.refundCannotExceedCapturedAmount`, `DatabaseConstraintsTest.refundCannotExceedThePayment` |
| Duplicate Kafka event | `ConsumerFailureHandlingKafkaTest.duplicateDeliveriesHaveTheirEffectExactlyOnce` |
| Webhook retry | `WebhookDeliveryTest.failuresBackOffTenSecondsThirtySecondsTwoMinutesThenSucceed` |
| Authorization isolation | `AuthorizationApiTest` |
| Outbox recovery | `OutboxRecoveryTest` |

## End-to-end journeys

`PaymentJourneyE2ETest` is small on purpose. E2E tests are slow and say little about *why* something
failed, so they only answer "does it all work together?":

- A customer pays a merchant, the merchant's server receives signed webhooks, and part of the money
  is refunded. Balances, the ledger, reconciliation and the audit trail all agree.
- A large payment from a new account is held, approved, captured, and still flagged afterwards.
- A frozen customer is refused, and their endpoint hears about the freeze.

## Property and invariant tests

Example tests check the cases somebody thought of. These check a rule against traffic nobody
planned.

```
A = €1000   B = €1000   C = €1000   D = €1000            total = €4000

        thousands of random transfers, concurrent, many refused

A + B + C + D = €4000. Always.
```

| Test | Traffic | Must hold |
|------|---------|-----------|
| `MoneyConservationPropertyTest` (concurrent) | 5,000 random transfers per seed, 16 at a time, over real HTTP and PostgreSQL. Amounts up to €400, so overdrafts are common | total is exactly €4000; no wallet negative; only 201 or 422; stored balances equal the ledger |
| `MoneyConservationPropertyTest` (model) | 1,000 sequential transfers per seed | every status and balance matches a plain `Map`. Conservation alone would not notice money going to the wrong wallet |
| `PaymentConservationPropertyTest` | 3,000 random commands per seed (create, authorize, capture, cancel, refund), racing each other | wallets + held funds = €4000; nothing crashes; refunds never exceed payments |
| `DomainPropertyTest` | 2,000 random command sequences; random money arithmetic | a refused command changes nothing; terminal states are final; refunded + refundable = amount; no rounding drift |

**Reproducing a failure.** Each integration property runs twice: with a fixed seed, and with a new
seed every run, so the suite keeps exploring. A failure message names the seed, and
`./mvnw test -Dtest=MoneyConservationPropertyTest -Dproperty.seed=<seed>` generates the same traffic.
Concurrent runs can interleave differently, so the seed reproduces the inputs, not the exact timing.
jqwik prints its own seed and a shrunk counterexample.

**These tests can fail.** While writing them, the refund limit check was deliberately removed from
`Payment`, and `DomainPropertyTest` caught it at once with "refunds never exceed the payment".
`NaiveLockingRaceTest` shows the same for concurrency: with unsafe locking, money really is created.

## Continuous integration

Every push and pull request runs these stages, each as its own step, so a red build says at once what
kind of problem it is:

```
checkout → compile → Checkstyle → unit → integration → concurrency → end-to-end → package
```

| Stage | Tests (2026-11-04) |
|-------|--------------------|
| Unit | 165 |
| Integration | 174 |
| Concurrency | 17 |
| End-to-end | 10 |

Alongside them:

| Check | Tool | Notes |
|-------|------|-------|
| Style | Checkstyle | rules that catch mistakes (unused imports, missing braces), not a formatting guide |
| Coverage | JaCoCo | per-module table in each run's summary, and the HTML report as an artifact. 95.6% of lines and 84.5% of branches. No threshold is enforced, because a target rewards tests that prove nothing |
| Image | Docker | the image builds |
| Dependencies | CycloneDX SBOM + OSV-Scanner | fails the build on known vulnerabilities. The first scan found 6 in Kafka's compression libraries, now pinned to fixed versions |
| Code | CodeQL | weekly and on every push, `security-extended` queries |
| Updates | Dependabot | weekly pull requests |

Load tests and failure experiments run from their own workflows ([`performance/`](../performance/README.md),
[`failure-scenarios.md`](failure-scenarios.md)), since they take longer and their results are worth
reading rather than gating on.

## Notes for writing tests

- Tests never clean the database. The ledger is append-only by design, so each test creates its own
  customers and wallets.
- A test that needs the queue to itself (`WebhookDeliveryTest`, `OutboxRecoveryTest`) gets its own
  PostgreSQL container, because the Kafka test context keeps a live relay and dispatcher running.
- Use `Concurrently.run(n, maxInFlight, ...)` for large bursts. Opening hundreds of connections in
  the same millisecond overflows the OS accept backlog, which tests the laptop rather than the code.
- Surefire only finds classes named `*Test`, including jqwik ones. A class named `*Properties` would
  be silently skipped.
