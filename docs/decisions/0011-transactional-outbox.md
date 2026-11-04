# ADR 0011: Transactional outbox for publishing events to Kafka

**Status:** Accepted · 2026-11-03

## Context

Captures, refunds, transfers and freezes must be announced to other systems (risk, merchant
webhooks). Writing to PostgreSQL and then sending to Kafka is a dual write. A crash between the
two loses the event; sending before the commit can announce something that rolls back. XA/2PC
across PostgreSQL and Kafka is not a realistic option.

## Decision

- Services publish through an `IntegrationEventPublisher` port, implemented by the outbox:
  an `INSERT INTO outbox_events` in the **same transaction** (`Propagation.MANDATORY`).
- A relay polls unpublished, due rows with `FOR UPDATE SKIP LOCKED`, sends each synchronously
  (`acks=all`, idempotent producer), and marks it published in the same relay transaction.
- Commit order via an identity `sequence_number`. An event is not selected while an earlier
  event of the same aggregate is unpublished (per-aggregate ordering survives retries without
  head-of-line blocking across aggregates).
- Failed sends: exponential backoff per row (`attempts`, `next_attempt_at`, `last_error`).
- Polling instead of CDC (Debezium). It is simpler to run and good enough at this scale; the
  table layout is CDC-compatible if that changes.

## Consequences

- No lost events and no phantom events. Delivery is **at-least-once**, so consumers must be
  idempotent (ADR 0012).
- Publishing latency ≈ poll interval (500 ms).
- The outbox table needs housekeeping (published rows deleted after 7 days) and monitoring
  (`outbox_pending_events`).
