# Redis is not a source of truth

**Status:** Accepted · 2026-11-04

## Context

Rate limits must be shared by all instances, and some lookups happen on nearly every request.
In-process counters break with two instances, and a counter changing on every request is a poor fit
for PostgreSQL. Redis suits both. The danger is scope creep: speed makes it tempting to keep balances
or idempotency keys there too.

## Options considered

| Option | For | Against |
|--------|-----|---------|
| No Redis: counters in PostgreSQL | one less system | write load on the database that matters most, for data nobody needs to keep |
| In-process counters and caches | no dependency | wrong as soon as there are two instances |
| Redis as a primary store for balances | very fast | cannot commit atomically with PostgreSQL; a stale balance check is a double spend |
| **Redis for disposable state only** | fast and shared, and losing it costs nothing | one more system; limits go unenforced during an outage |

## Decision

Redis holds only what the platform can afford to lose: rate-limit windows, short-lived cache entries
and temporary risk signals. Every key expires.

All access goes through one port, `EphemeralStore`, whose contract says data may vanish at any
moment. `ArchitectureTest` stops the ledger, account, transfer and payment modules from depending on
it. The port throws when Redis is unreachable, and **each caller chooses its own fallback**: rate
limits fail open, the cache falls back to PostgreSQL, and risk rules that need Redis stand aside and
mark the decision `degraded`.

Only immutable data is cached, so there is no invalidation to get wrong. Compose runs Redis without
persistence, which keeps the design honest.

## Consequences

- A Redis outage degrades protection and speed, not correctness: `RedisOutageTest` and the failure
  experiments kill it during payment flows.
- Rate limits go unenforced while Redis is down. Failing closed would be stricter, but a helper could
  then take down payments. The outage shows in metrics.
- Sliding windows need a Lua script for atomic check-and-add, which is more code than `INCR`.
