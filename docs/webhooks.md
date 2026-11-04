# Webhooks

Customers register a URL and receive signed HTTP notifications about their accounts, with retries,
a full delivery history and a dead-letter state. Delivery is **at-least-once**.

```
 business tx ─► outbox ─► Kafka ─► fan-out consumer ─► webhook_deliveries (PENDING)
                                   one row per subscribed endpoint   │
                                                                     ▼
                                       dispatcher: claim ─► sign ─► HTTP POST
                                                                     │
                       ┌───────────────┬─────────────────────────────┤
                      2xx          failure, retries left        failure, none left
                       ▼               ▼                             ▼
                   DELIVERED    PENDING, next_attempt_at           DEAD ──manual retry──► PENDING
```

## Why delivery does not happen in the Kafka consumer

The first version sent notifications from inside the consumer, relying on Kafka's error handler for
retries. That cannot work for real endpoints:

- A merchant's outage lasts hours, while in-place Kafka retries block the partition. One broken
  endpoint would delay every customer whose events share that partition.
- The backoff is per consumer, whereas here it has to be per delivery and last for over an hour.
- Nothing recorded the failed attempts, and customers need to see those most of all.

So the consumer now only does fast local work, writing one `PENDING` row per subscribed endpoint.
From then on, delivery has its own state machine in PostgreSQL. See
[ADR 016](decisions/016-webhook-delivery-state-machine.md).

## API

| Method | Path | Who | |
|--------|------|-----|---|
| `POST` | `/api/v1/webhook-endpoints` | account owner | returns the signing `secret`, **once** |
| `GET` | `/api/v1/webhook-endpoints?accountId=` | owner · SUPPORT · AUDITOR · ADMIN | |
| `GET` | `/api/v1/webhook-endpoints/{id}` | owner · SUPPORT · AUDITOR · ADMIN | |
| `DELETE` | `/api/v1/webhook-endpoints/{id}` | owner | disables it; history is kept |
| `GET` | `/api/v1/webhook-endpoints/{id}/deliveries?status=` | owner · SUPPORT · AUDITOR · ADMIN | filter by `PENDING`, `DELIVERED`, `DEAD` |
| `GET` | `/api/v1/webhook-deliveries/{id}` | owner · SUPPORT · AUDITOR · ADMIN | payload and every attempt |
| `POST` | `/api/v1/webhook-deliveries/{id}/retry` | owner | replays a `DEAD` delivery |

```bash
curl -s localhost:8080/api/v1/webhook-endpoints -H "$J" -H "$BO" -d "{
  \"accountId\": \"$B\",
  \"url\": \"https://shop.example.com/webhooks/ledgerforge\",
  \"eventTypes\": [\"payment.captured\", \"payment.refunded\"]
}"
```

Event types: `payment.captured`, `payment.refunded`, `transfer.completed`, `account.frozen`. Both
parties to a payment or transfer are notified, if they have subscribed.

## What a receiver gets

```http
POST /webhooks/ledgerforge HTTP/1.1
Content-Type: application/json
User-Agent: LedgerForge-Webhooks/1.0
LedgerForge-Signature: t=1793793600,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
LedgerForge-Event-Id: evt_6f1c9a3e2b7d4c1f8e0a5b3d7c9f1e2a
LedgerForge-Delivery-Id: 3f0c8d2a-...
LedgerForge-Attempt: 1
```

```json
{
  "id": "evt_6f1c9a3e2b7d4c1f8e0a5b3d7c9f1e2a",
  "type": "payment.captured",
  "createdAt": "2026-11-04T10:00:00Z",
  "data": {
    "paymentId": "…",
    "payerAccountId": "…",
    "merchantAccountId": "…",
    "amount": "30.0000",
    "currency": "EUR"
  }
}
```

This envelope is a public contract, separate from the internal Kafka events, which are free to
change with the code. Amounts are strings so no JSON client rounds them through a `double`.

A `2xx` response within the timeout means delivered. Anything else, including a redirect, is a
failure.

## Verifying the signature

```
v1 = hex(HMAC_SHA256(secret, t + "." + rawBody))
```

1. Split the `LedgerForge-Signature` header into `t` and `v1`.
2. Compute the HMAC over `t`, a dot, and the **raw request bytes**. Parsing and re-serialising the
   JSON would change them.
3. Compare in constant time.
4. Reject the request if `t` is older than your tolerance (five minutes is typical).

Because the timestamp is inside the signature, a captured request cannot be replayed later with a
fresh timestamp. [`WebhookSignature.verify`](../src/main/java/com/kgiannopoulou/ledgerforge/webhook/domain/WebhookSignature.java)
is the reference implementation. Each retry is signed again with a new timestamp, so a long-delayed
retry still passes a five-minute tolerance.

**Deduplicate on `id`.** Delivery is at-least-once: if your server processes an event and the
response is lost, you will receive it again. Exactly-once delivery to an external system is not
achievable, so `id` stays the same across retries.

## Retries, timeout, dead letters

| Attempt | Result | Then |
|---------|--------|------|
| 1 | fail | wait 10 seconds |
| 2 | fail | wait 30 seconds |
| 3 | fail | wait 2 minutes |
| 4 | fail | wait 10 minutes |
| 5 | fail | wait 1 hour |
| 6 | fail | **`DEAD`** |

The schedule is an explicit list (`ledgerforge.webhooks.retry-schedule`), with each wait roughly
three times the last, because customers and operators read it as a list. `connect-timeout` is 2 s,
and `request-timeout` is 5 s for the **whole exchange**: `HttpRequest.timeout` alone stops counting
once headers arrive, so an endpoint trickling its body could otherwise hold a call open
indefinitely.

`DEAD` is terminal until someone acts. `ledgerforge.webhooks.dead` counts these, and the owner can
list them and replay one with `POST /retry` once the receiver is fixed. A replay is one more attempt
rather than a new schedule: if it fails, the delivery goes straight back to `DEAD`.

## Delivery history

Every attempt is stored in `webhook_delivery_attempts`: number, start time, duration, outcome,
response status and error. The table is append-only (a trigger rejects `UPDATE` and `DELETE`), so a
disputed delivery has a record that cannot be quietly rewritten.

## Crash safety and scaling out

Claiming is a single statement:

```sql
WITH due AS (
    SELECT id FROM webhook_deliveries
    WHERE status = 'PENDING' AND next_attempt_at <= :now
    ORDER BY next_attempt_at LIMIT :n
    FOR UPDATE SKIP LOCKED
)
UPDATE webhook_deliveries d
SET attempts = d.attempts + 1, next_attempt_at = :leaseUntil
FROM due ...
```

- **`SKIP LOCKED`** lets any number of instances poll at once without claiming the same row or
  queueing behind each other.
- **The lease is just `next_attempt_at`.** If a dispatcher dies mid-send, the row becomes due again
  when the lease runs out (request timeout + 30 s). No separate recovery job is needed.
- **Attempts are numbered at claim time**, so a crashed attempt leaves a gap in the history: 1, 3
  means attempt 2 died without recording a result. The gap is the evidence.
- **Results are conditional** on the attempt number still being the latest, so a worker that
  outlives its lease cannot overwrite a newer result.
- **No connection is held during HTTP.** A slow merchant costs a virtual thread and nothing else.
- **A row is only claimed when a worker is free**, so the lease only ever has to cover one request,
  never time spent in a queue.

## Security

- **SSRF.** A webhook URL makes the platform call an address chosen by a customer. Without a check,
  `http://169.254.169.254/` reads the cloud metadata service and `http://10.0.0.5/` reaches internal
  systems. `WebhookUrlPolicy` requires HTTPS and refuses loopback, private, link-local, carrier-grade
  NAT, multicast and IPv6 local ranges. It runs at registration and **again before every attempt**,
  because DNS can change after a URL is accepted. Redirects are never followed.
  `allow-insecure-urls` turns this off for local development and tests only.
- **Secrets at rest.** An HMAC needs the secret itself, so it cannot be hashed like a password. It
  is stored AES-256-GCM encrypted under `LEDGERFORGE_WEBHOOK_ENCRYPTION_KEY`, which never enters the
  database, so a stolen dump alone cannot forge webhooks. The secret is shown once, at registration.
- **Authorization.** Only the account's owner can register, disable or replay. Staff can read, for
  support, but cannot change anything. Registering and disabling are audited.
- **Response bodies are discarded unread**, so a hostile endpoint cannot fill memory.
- **Fan-out is bounded** by a limit of 10 active endpoints per account.

### Known limits

- **DNS rebinding.** The URL is checked just before the request, but the HTTP client resolves the
  name again itself, so a very short window remains. Closing it fully needs connecting to the
  already-validated IP, or an egress proxy, which is what a production deployment should use.
- **Noisy neighbours.** All endpoints share the in-flight limit. Timeouts bound the damage, but a
  large customer with a slow endpoint can take a big share of it. Per-endpoint limits and
  auto-disabling endpoints that stay dead would be the next steps.
- **No backoff jitter.** Deliveries that failed together retry together.
- **No secret rotation or retention.** Rotating means registering a new endpoint for now, and
  delivery rows are kept indefinitely.

## Tests

| Test | Proves |
|------|--------|
| `WebhookDeliveryTest` | the 10 s → 30 s → 2 min → success scenario with all four attempts stored; dead after attempt 6, then replay; signature and envelope on the wire; same event id across retries; timeouts, including a stalled response body; unreachable endpoint; redirects not followed; disabled endpoint not called; crash recovery through the lease; fan-out by subscription; duplicate events fan out once; **8 concurrent dispatchers, 200 events, 200 requests, no duplicates**; attempt history cannot be rewritten |
| `WebhookEndpointApiTest` | secret returned once and encrypted at rest; ownership rules; validation; endpoint limit; audited disable |
| `WebhookUnitTest` | HMAC against an OpenSSL-computed value; tampered body, altered timestamp and stale requests rejected; retry schedule; SSRF address ranges; encryption round trip and tamper detection |
| `EventPipelineKafkaTest` | REST capture and refund → outbox → Kafka → fan-out → dispatcher → real HTTP, signed, once each |
