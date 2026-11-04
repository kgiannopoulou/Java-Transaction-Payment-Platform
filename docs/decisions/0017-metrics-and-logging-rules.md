# ADR 0017: Metrics and logging rules

**Status:** Accepted · 2026-11-04

## Context

Operators need to see throughput, failures, latency and backlogs, and to follow a single payment
through the logs. Both tools have well-known failure modes: metrics with unbounded labels overload
Prometheus, and logs collect tokens and personal data unless something stops them.

## Decision

**Metrics**

- Micrometer, exported to Prometheus, with Grafana provisioned from files in the repository.
- Business metrics are recorded after the transaction resolves, so rollbacks and idempotent replays
  are not counted as new work.
- Labels come from small fixed sets. Ids are never labels.
- Latency uses histogram buckets, so percentiles can be computed across instances.
- `/actuator/prometheus` has its own HTTP Basic security chain and a user with no other access,
  because Prometheus cannot present a short-lived JWT. With no password configured, the endpoint is
  closed.

**Logging**

- SLF4J only, enforced by ArchUnit. Structured JSON on stdout, for a collector to pick up.
- Log identifiers, not data: context fields hold opaque ids, never tokens, secrets, personal data
  or amounts.
- Tests read real log events at DEBUG level to enforce this.

Alternatives considered for the scrape endpoint:

- **A separate management port** would keep metrics off the public port, but moves
  `/actuator/health` too, and Spring Security's chain still applies there.
- **A long-lived JWT for Prometheus** would create a token that never expires, against the point of
  short-lived tokens.

## Consequences

- One query follows a payment through the logs, and dashboards and alerts come up with the stack.
- The `_created` suffix is reserved by the Prometheus client, so `payments_created_total` has to be
  produced by a relabel rule at scrape time.
- Keeping amounts out of logs means an operator investigating an amount goes to the audit trail,
  which has proper access control.
- Metrics counted in controllers would miss any future entry point that bypasses them. Today there
  is none.
