# Risk engine

Every `POST /payments` is screened by a small rule engine before a payment exists. There is no
machine learning: each rule is a few readable lines, and every decision can be explained.

| Example | Result |
|---------|--------|
| Payment €5 | **ALLOW** |
| Payment €7,500 from a new account | **REVIEW** |
| 20 payment attempts in 30 seconds | **BLOCK** |
| Payment from a frozen account | **BLOCK** |

## Design

```java
public interface RiskRule {
    RiskDecision evaluate(PaymentContext context);
}
```

| Rule | Fires when | Verdict | Data from |
|------|-----------|---------|-----------|
| `FrozenAccountRule` | the payer is frozen | BLOCK | PostgreSQL |
| `VelocityRule` | 20 or more attempts in 30 s | BLOCK | Redis |
| `LargeTransactionRule` | amount ≥ 7,500 and the account is under 30 days old | REVIEW | PostgreSQL |
| `RepeatedFailureRule` | 5 or more failed payments in 10 min | REVIEW | Redis |

```
PaymentContext ─► RiskEngine ─► List<RiskRule> (injected) ─► harshest verdict + all reasons
```

- **Interfaces and polymorphism.** The engine only knows `RiskRule`, not any particular rule.
- **Dependency injection.** Spring hands the engine every `RiskRule` bean, so adding a rule means
  adding one class. No existing code changes (`aNewRuleNeedsNoChangeToTheEngine`).
- **Pure rules.** `PaymentContext` is gathered once, up front, including anything from Redis. A rule
  is then a function from a record to a verdict, so the whole table is a parameterized unit test
  with no Spring, database or network (`RiskEngineTest`).
- **All rules always run**, even after a BLOCK. A reviewer wants every reason, not just the first.
- **The harshest verdict wins**: `BLOCK > REVIEW > ALLOW`, by `RiskOutcome` declaration order.

## What each outcome does

```
POST /payments
   │
   ├─ ALLOW   201 status CREATED        carries on as normal
   │
   ├─ REVIEW  201 status UNDER_REVIEW   no money can move
   │             │
   │             ├─ authorize ─────────► 409, until a decision is made
   │             ├─ POST /review APPROVE ► CREATED, then the payer authorizes as usual
   │             ├─ POST /review REJECT ─► CANCELLED
   │             └─ payer cancels ──────► CANCELLED
   │
   └─ BLOCK   422 PAYMENT_BLOCKED       no payment exists
```

`POST /api/v1/payments/{id}/review` takes `{"decision": "APPROVE" | "REJECT", "note": "…"}` and is
open to `SUPPORT` and `ADMIN`. The reviewer and note go in the audit trail.
`GET /api/v1/risk/decisions?outcome=REVIEW` is the queue.

### A refusal says nothing about why

```json
{ "type": "PAYMENT_BLOCKED", "title": "Request rejected", "status": 422,
  "detail": "This payment cannot be processed", "traceId": "…" }
```

Naming the rule would tell a fraudster exactly what to change. The reasons are in `risk_decisions`,
for staff. That includes frozen accounts: creating a payment from one used to return
`ACCOUNT_FROZEN`, and now returns the same generic refusal, so that the attempt gets recorded.

## Storing decisions

Every screening writes a row to `risk_decisions`: outcome, reasons, and whether it was degraded.
Redis counters expire within minutes, but "why was this declined?" still needs an answer next year.

| Outcome | Stored |
|---------|--------|
| ALLOW, REVIEW | in the payment's transaction, so a decision exists exactly when the payment does |
| BLOCK | in its **own** transaction, after the rollback |

BLOCK is the interesting case. The refusal is an exception, which rolls back the transaction, so
anything written inside it would disappear along with the record of the refusal. The controller
catches the exception after the rollback and records it then. A crash in that small gap loses the
record of a refusal, but no money moved. A `REQUIRES_NEW` transaction would avoid the gap, but it
would hold two connections per request, and under load that can deadlock on the pool.

A database constraint ties this together: `(outcome = 'BLOCK') = (payment_id IS NULL)`.

## Interplay with other features

- **Idempotency.** Screening runs inside the idempotent operation, so a replay returns the stored
  response and is neither counted nor screened again. Retrying 30 times with one key is one attempt.
  A BLOCK rolls back the key too, so the same key can be used again once the cause is fixed.
- **Rate limit.** They work at different levels: the limiter (100 a minute) protects capacity and
  rejects before any work happens, while velocity (20 in 30 s) is a fraud signal with a stored
  decision. Blocked attempts still use up the rate limit.
- **Failure signals** are recorded after rollback: a declined authorization (insufficient funds) or
  a blocked attempt. Being in Redis, they survive the rollback that the failure causes, and nothing
  touches Redis while account row locks are held.
- **Authorization.** Screening is at creation. `authorize` still re-checks status and balance under
  lock, so an account frozen after approval cannot move money.
- **Kafka alerts.** The after-the-fact consumer ([outbox.md](outbox.md)) is a separate layer. It
  watches completed movements and raises alerts, with no power to stop anything.

## Failure modes

| Situation | Behaviour |
|-----------|-----------|
| Redis is down | velocity and repeated-failure rules cannot run, the others still do, and the decision is stored with `degraded = true` |
| A rule throws | the payment goes to **REVIEW**: failing would refuse good customers, and ignoring it would wave through what the rule exists to catch |
| The review queue grows | payments wait; none of their money has moved |

Failing open on Redis is a trade-off. During an outage, velocity attacks are stopped only by
whatever still runs: account state, amount thresholds and balances. For a payment platform with a
stricter risk appetite, the alternative is to send everything to REVIEW while `degraded`, a small
change in `PaymentScreening`.

## Limits

- Thresholds are plain numbers applied to any currency, because there is no FX data: €7,500 and
  $7,500 count the same. Real rules would convert or configure per currency.
- Rules are configuration, but not editable at runtime. They live in `ledgerforge.risk.rules`.
- Velocity is per payer account. A real engine would add device, IP and merchant dimensions.
- There are no "four-eyes" reviews yet: one reviewer can approve alone.

## Tests

| Test | Proves |
|------|--------|
| `RiskEngineTest` | the decision table including boundary values; harshest verdict wins and all reasons are kept; a new rule needs no engine change; a crashing rule leads to REVIEW and cannot hide a BLOCK; rules without signals stand aside |
| `RiskScreeningApiTest` | the plan's four examples over HTTP; refusals reveal nothing; declines lead to review; the whole review workflow and who may use it; replays are not attempts; **30 concurrent requests give exactly 19 allowed and 11 blocked** |
| `PaymentStateMachineTest` | every transition in and out of `UNDER_REVIEW` |
| `RedisOutageTest` | with Redis gone, payments work and the decision is marked `degraded` |
