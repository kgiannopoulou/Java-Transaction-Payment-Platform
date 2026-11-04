# ADR 017: A small synchronous rule engine for payment risk

**Status:** Accepted · 2026-11-04

## Context

The existing risk consumer reads Kafka events, so it only learns about a payment after the money has
moved. It can raise alerts, but it cannot stop anything. The platform needs a check that runs before
a payment exists and can refuse it or hold it for a person.

## Decision

- A `RiskRule` interface with one method, one Spring bean per rule, and an engine that receives
  them all by injection and keeps the harshest verdict.
- Rules are pure functions of a `PaymentContext` assembled up front, including signals from Redis.
- Three outcomes: **ALLOW** carries on, **REVIEW** parks the payment in a new `UNDER_REVIEW` state
  until `SUPPORT` or `ADMIN` decides, and **BLOCK** refuses it with a generic `422`.
- Every decision is stored in `risk_decisions`. ALLOW and REVIEW go in the payment's transaction,
  BLOCK in a separate one after the rollback.
- No machine learning. At this scale, explainable rules beat a model: each decision can be traced
  to a line of code, thresholds are configuration, and no training data exists anyway.
- No rules library such as Drools. Four rules do not need a DSL, and plain Java gets compile-time
  checks, debuggers and unit tests for free.

## Consequences

- Adding a rule means adding one class.
- Screening adds two Redis calls and one insert to each payment creation.
- `POST /payments` from a frozen payer changes from `ACCOUNT_FROZEN` to the generic
  `PAYMENT_BLOCKED`, a deliberate API change so the refusal is recorded and reveals nothing.
- Because BLOCK is recorded after rollback, a crash in that gap loses the record of a refusal, but
  never moves money. `REQUIRES_NEW` would close the gap at the cost of two connections per request,
  which risks pool deadlock under load.
- Rules relying on Redis stand aside when it is down, and the decision is marked `degraded`.
  Availability is preferred over strictness here, and it is a one-line change to reverse.
