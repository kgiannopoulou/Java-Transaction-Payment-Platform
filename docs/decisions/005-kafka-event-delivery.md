# Kafka event delivery

**Status:** Accepted · 2026-11-03

## Context

Events have to reach several independent consumers, and each must be able to fail, retry or catch up
without affecting the others. The outbox delivers at-least-once, so consumers will see duplicates,
their dependencies will fail, and some records can never be processed.

## Options considered

| Topic | Options | Chosen |
|-------|---------|--------|
| Transport | direct calls between modules; RabbitMQ; **Kafka** | Kafka: consumers read independently, per-key order, and a replayable log. RabbitMQ would also work, but removes messages once consumed. Direct calls couple availability together |
| Duplicates | exactly-once Kafka transactions; **idempotent consumers** | Kafka transactions end at Kafka: side effects in PostgreSQL still need deduplication, so that is the single mechanism used |
| Failures | block forever; drop; **retry, then a dead-letter topic** | retries cover transient faults, and the DLT keeps the record without stalling the partition |

## Decision

- One topic per aggregate type (`ledgerforge.payments.v1`, ...), keyed by aggregate id, so one
  payment's events stay in order. Events are typed records on a sealed interface.
- **Idempotency:** each consumer inserts `(consumer, event_id)` into `processed_events` with
  `ON CONFLICT DO NOTHING`, in the **same transaction** as its side effects, and skips the event if
  the row existed.
- **Retries:** `DefaultErrorHandler`, exponential backoff (500 ms × 2ⁿ, at most 10 s, 4 retries), in
  place, to keep partition order.
- **Dead letters:** after the last retry the record goes to `<topic>.DLT` with its original payload.
  `MalformedEventException` skips retries, because retrying cannot help.
- Consumers have **separate groups**, so one failing does not hold up the others.

## Consequences

- Duplicates and DLT replays are harmless by construction.
- In-place retries block a partition briefly. That is why slow external work does not happen in
  consumers: webhooks have their own retry schedule in PostgreSQL
  ([ADR 016](016-webhook-delivery-state-machine.md)).
- External side effects stay at-least-once, so webhook receivers deduplicate on the event id.
- Kafka is one more system to operate, justified by several independent consumers.
