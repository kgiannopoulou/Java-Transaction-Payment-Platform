# Events and the transactional outbox

## Why Kafka, and why only now

Kafka was deliberately left out of the first milestone. It arrived once there were **facts other
systems need to react to**:

| Event | Topic | Consumers |
|-------|-------|-----------|
| `PaymentCaptured` | `ledgerforge.payments.v1` | risk engine, webhook fan-out |
| `PaymentRefunded` | `ledgerforge.payments.v1` | webhook fan-out |
| `TransferCompleted` | `ledgerforge.transfers.v1` | risk engine |
| `AccountFrozen` | `ledgerforge.accounts.v1` | (published for downstream systems) |

```
PaymentService / TransferService / AccountService
        │  (same DB transaction)
        ▼
  outbox_events ──► OutboxRelay ──► Kafka ──┬──► Risk engine      → risk_alerts
                                            └──► Webhook fan-out → webhook_deliveries
```

Events are **strongly typed records** implementing a sealed interface:

```java
public sealed interface IntegrationEvent
        permits PaymentCapturedEvent, PaymentRefundedEvent, TransferCompletedEvent, AccountFrozenEvent { … }

public record PaymentCapturedEvent(UUID eventId, UUID paymentId, UUID payerAccountId,
        UUID merchantAccountId, BigDecimal amount, Currency currency, Instant occurredAt)
        implements IntegrationEvent { … }
```

Consumers `switch` over `IntegrationEvent` exhaustively, so adding an event type is a compile
error in every consumer until it decides what to do with it. The record is keyed by aggregate
id (payment, transfer, account), so all events of one aggregate land on one partition, in order.
Topics are versioned (`.v1`); an incompatible schema change gets a new topic.

## The dual-write problem

The naive version:

```
BEGIN
  UPDATE payment SET status = 'CAPTURED'
  INSERT ledger entries
COMMIT
kafka.send(PaymentCaptured)     ← process crashes here: money moved, nobody is ever told
```

Publishing *before* the commit is no better: the event announces a capture that may still roll
back. Two systems (PostgreSQL and Kafka) cannot be updated atomically without distributed
transactions.

## The fix: transactional outbox

```
BEGIN
  UPDATE payment
  INSERT ledger entries
  INSERT audit_events
  INSERT outbox_events (PaymentCaptured)        ← same transaction
COMMIT                                          ← state + intent to publish, atomically

OutboxRelay (every 500 ms):
BEGIN
  SELECT … FROM outbox_events WHERE published_at IS NULL AND next_attempt_at <= now()
    AND no earlier unpublished event of the same aggregate
    ORDER BY sequence_number LIMIT 100 FOR UPDATE SKIP LOCKED
  for each: send to Kafka, wait for ack (acks=all, idempotent producer) → mark published
COMMIT
```

- Services publish through the `IntegrationEventPublisher` port. Its implementation is
  `@Transactional(propagation = MANDATORY)`: publishing outside a business transaction throws
  instead of silently creating the dual write again.
- A **crash at any point** loses nothing. Before the business commit, nothing happened. After
  it, the event is in the table and the relay will send it.
- **At-least-once:** if the relay dies after Kafka acknowledged but before `published_at`
  commits, the event is sent again. Every event carries an `eventId`, and consumers deduplicate
  on it (below).
- **Ordering:** `sequence_number` (identity column) gives commit order. An event is never
  selected while an earlier event *of the same aggregate* is unpublished, so a payment's
  `PaymentRefunded` can never reach Kafka before its `PaymentCaptured`, even if the capture's
  first send failed. Other aggregates are not blocked by one stuck event.
- **Failures:** a failed send increments `attempts`, stores `last_error` and schedules
  `next_attempt_at` with exponential backoff (1 s, 2 s, 4 s … capped at 5 min). The batch
  stops at the first failure.
- **Several relay instances** can run: `FOR UPDATE SKIP LOCKED` hands each row to one of them.
- **Housekeeping:** published rows are deleted after 7 days; the gauge
  `outbox_pending_events` exposes the backlog.

## Consumers: retries, backoff, DLT, idempotency

```
record ──► listener ── fails ──► retry in place: 500 ms, 1 s, 2 s, 4 s (max 10 s), 4 retries
                                    │ still failing, or MalformedEventException (retry can't help)
                                    ▼
                              <topic>.DLT   original key, payload and headers + exception headers
```

- `DefaultErrorHandler` + `ExponentialBackOff` + `DeadLetterPublishingRecoverer`. Retries seek
  back to the failed record, so the partition's order is kept.
- Poison records (unknown type, invalid JSON, missing fields) raise `MalformedEventException`,
  which is **not retryable** and goes straight to the DLT.
- DLT records keep the original payload and `eventId`, so they can be replayed after a fix.
  Because consumers are idempotent, replaying is always safe.
- Consumer groups are independent: a failing webhook consumer does not delay risk scoring.

### Idempotent consumers

Kafka delivers at-least-once, so a consumer may see `PaymentCaptured` twice: after a relay
retry, a consumer crash before committing its offset, or a rebalance. Each consumer records
handled event ids **in the same database transaction as its side effects**:

```
BEGIN
  INSERT INTO processed_events (consumer, event_id) VALUES (…) ON CONFLICT DO NOTHING
  -- 0 rows → duplicate → skip
  -- 1 row  → do the work (insert risk alert / insert webhook deliveries)
COMMIT     -- marker and effects together; on failure both roll back and the retry starts clean
```

`webhook_deliveries (endpoint_id, event_id)` and `risk_alerts (event_id, rule)` are additionally
`UNIQUE`, as a second line of defence. Duplicates are counted in
`ledgerforge.events.duplicates{consumer}`.

The webhook consumer makes no HTTP calls. It only records what has to be delivered, and a separate
dispatcher owns retries towards customers, so a merchant's outage cannot stall a partition. Delivery
to the merchant is at-least-once. See [webhooks.md](webhooks.md).

## Tests

| Test | Proves |
|------|--------|
| `TransactionalOutboxTest` | transfer, capture, refund, freeze each commit their event row in the business transaction; a rejected transfer writes none; publishing without a transaction throws |
| `OutboxRelayTest` | order across batches; failed send → backoff, retried later, no overtaking within an aggregate; a stuck aggregate does not block others; backoff cap; retention purge |
| `EventPipelineKafkaTest` | REST transfer → outbox → real Kafka (key, `eventType`/`eventId` headers) → row marked published; a large transfer raises exactly one risk alert; capture + refund reach the merchant's HTTP endpoint, signed, once each |
| `ConsumerFailureHandlingKafkaTest` | the same `PaymentCaptured` delivered twice → processed once, one risk alert; consumer failing twice → retried with backoff, processed once; failing for good → 1 + 3 attempts, then the DLT with the original payload, no side effects, risk scoring unaffected; malformed record → DLT without retries |
