# ADR 016: Webhook delivery as a state machine in PostgreSQL

**Status:** Accepted · 2026-11-04 · Refines [ADR 005](005-kafka-event-delivery.md)

## Context

Webhooks were sent from inside the Kafka consumer, with Kafka's in-place retries handling failures.
That was fine while the sender was a stub. Real endpoints change the picture: customers need
retries lasting over an hour, a record of every attempt, a way to replay failures, and isolation
from each other's outages. In-place Kafka retries block the partition, keep no history and give up
after seconds.

## Decision

- The Kafka consumer only **fans out**: one `webhook_deliveries` row per subscribed endpoint, in the
  same transaction as its `processed_events` marker. It makes no HTTP calls.
- A separate **dispatcher** polls for due rows, claims them with `FOR UPDATE SKIP LOCKED`, and sends
  outside any transaction.
- The **lease is `next_attempt_at`**: claiming moves it forward, so a crashed worker's row simply
  becomes due again.
- Every attempt is a row in an **append-only** table. Exhausted deliveries become `DEAD`, and owners
  can replay them.

Alternatives considered:

- **Kafka retry topics** (`-retry-10s`, `-retry-30s`, ...) keep the partition free, but need one
  topic per delay, leave history to be built separately, and make "list my failed deliveries and
  replay one" awkward. That question is a query, so the data belongs in a database.
- **A job library such as Quartz or db-scheduler** would replace the claim query but add a
  dependency and its own tables. The whole mechanism here is one SQL statement.

## Consequences

- One customer's outage no longer affects anyone else's events.
- Delivery state is queryable, so the history API is a plain `SELECT`.
- Polling adds up to one poll interval of latency (1 s by default), which is fine for webhooks.
- PostgreSQL now also acts as a work queue. The partial index keeps polling cheap, and `SKIP LOCKED`
  avoids contention. Very high volumes would need partitioning or a dedicated queue.
- Kafka's retries and DLT still protect the fan-out itself, for example during a database blip.
