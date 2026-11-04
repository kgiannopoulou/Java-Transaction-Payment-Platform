# Redis

Redis has one job here: **fast, shared, disposable state**. Everything in it may vanish at any
moment, and losing all of it must never lose or change money.

```
              PostgreSQL                                 Redis
   ┌───────────────────────────────┐        ┌────────────────────────────────┐
   │ balances, ledger, payments    │        │ rate-limit windows             │
   │ idempotency keys, audit trail │        │ short-lived cache entries      │
   │ webhook deliveries, outbox    │        │ temporary risk signals         │
   │                               │        │                                │
   │ source of truth               │        │ may vanish at any moment       │
   │ transactions, constraints     │        │ every key expires              │
   └───────────────────────────────┘        └────────────────────────────────┘
```

## What it is used for

| Use | Key | Why Redis | If Redis is down |
|-----|-----|-----------|------------------|
| **Rate limit:** 100 × `POST /payments` per minute per account | `ratelimit:payments:<account>` | every instance has to see the same count | **fails open**: requests pass, counter `ledgerforge.ratelimit.decisions{result=unavailable}` rises |
| **Short-lived cache:** who owns an account | `cache:account-owner:<account>` | this runs on nearly every customer request | falls back to PostgreSQL |
| **Distributed counter:** payment attempts per account, 30 s window | `risk:attempts:<account>` | `VelocityRule` has to count attempts from all instances | velocity rule skipped, decision marked `degraded` |
| **Temporary risk signal:** failed payments per account, 10 min window | `risk:failures:<account>` | useful for minutes, and must survive the transaction rollback that the failure causes | failure rule skipped, decision marked `degraded` |

## What it must never be used for

Authoritative balances. Redis has no multi-key transaction with PostgreSQL, so a cached balance can
disagree with the ledger, and a balance check against a stale value is a double spend. A balance
read comes from PostgreSQL, under a row lock when money is about to move.

The same goes for idempotency keys, which have to commit atomically with the payment they protect
([ADR 0005](decisions/0005-idempotency-in-the-business-transaction.md)).

These rules are enforced, not just written down:

- `ArchitectureTest.the_financial_core_never_touches_redis`: the `ledger`, `account`, `transfer` and
  `payment` modules cannot depend on the `EphemeralStore` port. The build fails if they do.
- `ArchitectureTest.redis_stays_behind_the_port`: only the adapter may use Spring Data Redis.
- `RedisBackedFeaturesTest.redisHoldsNoBalancesAndNothingWithoutAnExpiry`: after a full payment, no
  key mentions a balance or the ledger, and every key has an expiry.
- `RedisOutageTest`: kills Redis mid-test, then runs create, authorize and capture. Balances are
  correct, the ledger reconciles, and the service still reports ready.

Compose runs Redis with persistence turned off. Restarting it is a non-event, and if that ever
stopped being true, something authoritative would have crept in.

## Rate limiting

```
POST /api/v1/payments
  JWT, role, @PreAuthorize ownership      ─ 401 / 403 (does not count against the limit)
  RateLimiter.checkPaymentCreation(payer) ─ 429 + Retry-After
  idempotency, business transaction ...
```

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37
Content-Type: application/problem+json

{ "type": "RATE_LIMIT_EXCEEDED", "title": "Too many requests", "status": 429,
  "detail": "At most 100 payments per 1 minute(s) per account", "traceId": "…" }
```

- The check runs **after authorization**, so nobody can burn another account's allowance by sending
  requests in its name.
- The window **slides**. A fixed window (`INCR` + `EXPIRE`) lets a client send 100 requests at
  0:59 and 100 more at 1:01. A sorted set with one member per hit does not, at the cost of memory
  proportional to the limit, which is trivial at 100.
- **Check and add are one atomic Lua script.** A separate read and write would let concurrent
  requests all see 99 and all pass. `concurrentRequestsCannotOvershootTheLimit` fires 150 at once
  and exactly 100 get through.
- **Time comes from the Redis server**, so instances with drifting clocks share one window.
- **Denied requests do not count**, so a client that backs off recovers on schedule.

### Why fail open?

A rate limit protects capacity. It is not what keeps money safe: row locks, the ledger and database
constraints still apply with Redis down. Failing closed would turn an outage of a helper into an
outage of payments. The price is that limits are not enforced during the outage, which is why the
counter exists to alert on.

## Caching

Only one thing is cached, and it was chosen because it **cannot go stale**: the owner of an account.
`customer_id` never changes after a wallet is created, so the entry can only be right or missing.
The 5-minute TTL exists only to bound memory. Unknown ids are not cached.

Balances, account status and payment state can all change, so none of them are cached. A cache
that could wrongly report "active" on a frozen account would be a security bug.

This saves a query per request, not a connection: `AccessPolicy` still runs in a read-only
transaction. Moving it out would be the next step if the pool ever became the bottleneck.

## Risk signals

Counters feed the [risk engine](risk-engine.md). They only matter for minutes, and they expire by
themselves. The lasting record of what was decided, and why, is in `risk_decisions` in PostgreSQL.

If Redis is down, the velocity and repeated-failure rules cannot run. The rules that rely on
PostgreSQL (frozen account, large transaction) still do, and the decision is stored with
`degraded = true`, so operators can find out exactly which decisions were made with less information.

## Operating it

| Setting | Value | Why |
|---------|-------|-----|
| `timeout`, `connect-timeout` | 200 ms | a hanging Redis must not hang payments |
| `LEDGERFORGE_REDIS_HOST` / `_PORT` | `localhost:6382` | |

During an outage each Redis call costs up to the timeout, so a request is slower but still works.
A circuit breaker would remove that cost. It was left out because the timeout bounds the damage.

`/actuator/health` turns `DOWN` when Redis is unreachable, which is useful to operators. The
readiness and liveness probes do not include it, so an orchestrator will not take instances out of
service over a dependency they can run without.
