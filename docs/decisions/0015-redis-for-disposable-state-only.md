# ADR 0015: Redis for disposable state only; PostgreSQL stays the source of truth

**Status:** Accepted · 2026-11-04

## Context

Rate limits have to be shared by all instances, and some lookups run on almost every request. An
in-process counter or cache is wrong as soon as there are two instances, and PostgreSQL is the wrong
place for a counter that changes on every request. Redis fits both needs.

The risk is scope creep. It is tempting to put balances or idempotency keys in Redis for speed, and
either would break guarantees the platform is built on: Redis cannot commit atomically with
PostgreSQL, so its copy of a balance can disagree with the ledger, and a check against a stale
balance is a double spend.

## Decision

- Redis holds only state the platform can afford to lose: rate-limit windows, short-lived cache
  entries, temporary risk signals. Every key has an expiry.
- All access goes through one port, `EphemeralStore`, whose contract says data may vanish at any
  moment. `ArchitectureTest` stops the ledger, account, transfer and payment modules from depending
  on it.
- The port throws when Redis is unreachable, and **each caller chooses its own fallback**. Rate
  limits fail open, and the cache falls back to PostgreSQL.
- Sliding windows use a sorted set and a Lua script, for atomic check-and-add on the server's clock.
- Only immutable data is cached, so there is no invalidation to get wrong.
- Compose runs Redis without persistence, which keeps the design honest.

## Consequences

- A Redis outage degrades protection and speed, not correctness. `RedisOutageTest` proves it by
  killing Redis during a payment flow.
- Rate limits go unenforced while Redis is down. Failing closed would be stricter, but it would
  let a helper take down payments. The outage shows up in metrics.
- There is one more component to run, justified by concrete uses rather than general speed.
