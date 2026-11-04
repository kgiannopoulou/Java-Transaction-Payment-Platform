# Transactional outbox

**Status:** Accepted · 2026-11-03

## Context

Captures, refunds, transfers and freezes must be announced to other systems. Writing to PostgreSQL
and then to Kafka is a dual write. A crash between them loses the event, and sending first can
announce something that later rolls back.

## Options considered

| Option | For | Against |
|--------|-----|---------|
| Publish after commit | trivial | a crash in between silently loses the event |
| Publish before commit | trivial | announces things that never happened |
| XA / two-phase commit | atomic in theory | Kafka does not take part; slow and fragile even where supported |
| **Outbox table with a polling relay** | atomic with the business change; simple to run and to test | adds up to one poll interval of latency; the table needs housekeeping |
| Change data capture (Debezium) | no polling; lower latency | another distributed system to run; the table layout stays compatible if needed later |

## Decision

Services publish through a port whose implementation inserts into `outbox_events` in the **same
transaction** (`Propagation.MANDATORY`, so it cannot be used outside one).

A relay polls due rows with `FOR UPDATE SKIP LOCKED`, sends each one (`acks=all`, idempotent
producer) and marks it published. An event is not selected while an earlier event of the same
aggregate is unpublished, which keeps per-aggregate order without letting one stuck event stall
others. Failed sends back off exponentially, per row.

## Consequences

- No lost events and no phantom events: `OutboxRecoveryTest` and the Kafka kill experiment show it.
- Payments keep working while Kafka is down, because the API never talks to Kafka.
- Delivery is **at-least-once**: a crash after Kafka's acknowledgement sends the event again, so
  consumers must deduplicate ([ADR 005](005-kafka-event-delivery.md)).
- Published rows are deleted after 7 days, and `outbox_pending_events` is monitored.
