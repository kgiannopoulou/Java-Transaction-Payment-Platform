# ADR 0012: Idempotent consumers, in-place retries with backoff, dead-letter topics

**Status:** Accepted · 2026-11-03

## Context

The outbox relay and Kafka consumers both deliver at-least-once. Consumers will therefore see
duplicates, and their own dependencies (a merchant's webhook endpoint) fail transiently or
permanently. Some records can never be processed (malformed payloads).

## Decision

- **Idempotency:** each consumer inserts `(consumer, event_id)` into `processed_events` with
  `ON CONFLICT DO NOTHING` in the **same transaction** as its side effects, and skips the event
  if the row already existed. Unique constraints on the result tables are a second guard.
- **Retries:** Spring Kafka `DefaultErrorHandler` with exponential backoff (500 ms × 2ⁿ, max
  10 s, 4 retries), retrying in place to keep partition order.
- **Dead letters:** after the last retry the record goes to `<topic>.DLT` via
  `DeadLetterPublishingRecoverer`, with the original payload and exception headers.
  `MalformedEventException` is not retryable and goes there immediately.
- Consumers run in **separate consumer groups**, so one failing consumer does not hold up the
  others.

## Consequences

- Duplicates and replays from the DLT are harmless by construction.
- In-place retries block the partition while backing off. That is acceptable for these
  consumers; a non-blocking retry-topic chain would be the next step if a consumer's
  dependency were slow for long periods.
- External side effects (webhooks) remain at-least-once. Receivers deduplicate on the
  `eventId` we send.
