# Failure scenarios

Anything can claim to be reliable while everything is working. These experiments break the running
system on purpose and check what it promises.

**How:** [`failure-testing/run.py`](../failure-testing/run.py) runs against the real Compose stack and
uses `docker compose kill -s SIGKILL`, so nothing gets a chance to shut down cleanly. Run it with the
**Failure tests** workflow in GitHub Actions, or locally:

```bash
docker compose -f compose.yaml -f failure-testing/compose.failure.yaml up -d --build --wait
python3 failure-testing/run.py
```

**Latest result:** 30 of 30 checks passed on 2026-11-04, commit `a249f26`, on a GitHub Actions runner.
The numbers below come from that run, and the [complete timeline](../failure-testing/results-2026-11-04.txt)
is in the repository. The workflow also runs weekly.

| Experiment | What is broken | Result |
|------------|----------------|--------|
| [1](#1-kill-kafka) | Kafka | payment succeeds; the event waits in the outbox, then reaches the merchant |
| [2](#2-kill-the-webhook-receiver) | the merchant's server | retried with backoff, delivered once it returned |
| [3](#3-kill-the-application-during-transfers) | the application, mid-transfer | no half-transfers, no missing money, no duplicates |
| [4](#4-kill-postgresql-during-transfers) | PostgreSQL, mid-transfer | nothing acknowledged was lost |
| [5](#5-kill-redis) | Redis | payments continue, and decisions are marked `degraded` |

## 1. Kill Kafka

```
Payment succeeds
        ↓
Kafka unavailable
        ↓
outbox retains event
        ↓
Kafka returns
        ↓
event published
```

| Time | What happened |
|------|---------------|
| 1.5 s | Kafka killed |
| 1.8 s | capture returned `200`, and the money moved |
| 2.0 s | the event is an unpublished row in `outbox_events` |
| 17.2 s | still there, with 2 failed attempts recorded; Kafka restarted |
| 37.5 s | event published, 20.1 s after the restart |
| 38.3 s | the merchant's server received the webhook, once |

**Why it works.** The API never talks to Kafka. The event is a row in the same transaction as the
capture, and the relay retries with backoff. The 20 s is mostly that backoff plus Kafka's own
startup: the relay does not know the moment Kafka returns.

## 2. Kill the webhook receiver

```
Webhook fails
        ↓
retry
        ↓
retry
        ↓
receiver returns
        ↓
delivery succeeds
```

| Attempt | Time | Outcome |
|---------|------|---------|
| 1 | 10:28:37.1 | failed: receiver down |
| 2 | 10:28:49.1 (12 s later) | failed: receiver down |
| | | receiver restarted |
| 3 | 10:29:21.2 (32 s later) | **delivered** |

The payment itself was unaffected (`200`, 0.2 s after the kill). The merchant received the event
exactly once, and all three attempts are stored. The gaps are the 10 s and 30 s schedule plus the
polling interval and failed connection time.

## 3. Kill the application during transfers

The hard one. Four wallets hold €4000 in total, eight clients send random transfers as fast as they
can, and the application gets `SIGKILL` mid-load.

| | |
|---|---|
| Acknowledged before the kill | 241 |
| Requests left **in doubt** (no answer, or a failure, so the client cannot know what happened) | 632 |
| Time for the application to come back | 12.5 s after restart |

A client in doubt does what a client should: it retries with the same `Idempotency-Key`. Then:

| Promise | Result |
|---------|--------|
| Every in-doubt retry gets a definite answer | ✓ `201` or `422` |
| Nothing acknowledged was lost | ✓ 902 of 902 present |
| No missing money | ✓ the wallets total exactly €4000.00 |
| No half-transfer: none left incomplete | ✓ 0 |
| No half-transfer: every ledger transaction has both legs | ✓ 0 one-sided |
| Retries created no duplicates | ✓ 902 stored, 902 acknowledged |
| The whole ledger reconciles | ✓ |

Most of those 632 requests hit a closed port while the application was down, so they never started.
They count as in doubt because that is all a client can see. A few were really in flight at the kill.

**Why it works.** A transfer is one transaction, covering both balances, the ledger entries, the
audit event, the outbox row and the idempotency key. A process dying mid-transaction drops the
connection, and PostgreSQL rolls everything back. There is no intermediate state to clean up, so no
recovery code exists or is needed.

## 4. Kill PostgreSQL during transfers

Same load and same checks, but with the database killed instead, which tests durability: a `201`
means the transaction was committed, so it has to survive a crash.

| | |
|---|---|
| Acknowledged before the kill | 396 |
| Requests in doubt | 16 |
| Back and ready | 0.9 s after restart |

All the promises in experiment 3 held: 639 of 639 acknowledged transfers present after crash
recovery, €4000.00 exactly, no duplicates, and the ledger reconciles.

**A bug this found.** The first run showed the service reporting `ready` with its database dead,
because readiness did not include PostgreSQL, so an orchestrator would have kept sending traffic to
an instance that could only fail. Readiness now includes `db`, and the experiment checks that the
instance reports not ready while PostgreSQL is down. Kafka and Redis stay out of readiness on purpose,
because the service works without them.

## 5. Kill Redis

| Promise | Result |
|---------|--------|
| Payments keep working | ✓ the flow took 1.1 s, slower while Redis calls time out |
| The balance is right | ✓ |
| The risk decision records that it ran without Redis | ✓ `degraded = true` |
| Health recovers when Redis returns | ✓ 0.1 s after restart |

During the outage, rate limits and the two Redis-based risk rules do not apply. That is a deliberate
trade-off, explained in [ADR 006](decisions/006-redis-not-source-of-truth.md).

## What these experiments do not cover

- **A slow dependency.** A dead service fails fast. One that hangs is often worse, and only the Redis
  timeouts and the webhook deadline are tested for that.
- **Network partitions.** Everything here is a process dying on one machine.
- **Losing data on disk.** PostgreSQL recovered from its own WAL. Replication, backups and
  point-in-time recovery are outside this project.
- **Kafka losing data.** The single broker kept its log across a restart. With a real cluster,
  replication settings decide that.
- **Several instances.** Only one application instance ran. Concurrent relays and dispatchers are
  covered by tests instead (`OutboxRecoveryTest`, `WebhookDeliveryTest`).

## Related tests

Failure behaviour is also covered in the normal test suite, where it runs on every push:
`OutboxRecoveryTest` (broker outage, crash after Kafka's acknowledgement), `RedisOutageTest`,
`ConsumerFailureHandlingKafkaTest` (duplicates, retries, dead letters) and `WebhookDeliveryTest`
(timeouts, crash recovery through the lease).
