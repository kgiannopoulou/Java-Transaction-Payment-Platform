# Performance testing

k6 load tests at 50, 100, 500 and 1000 concurrent users. Everything below was measured, on the
environment described, and the raw output is in [`results/2026-11-04/`](results/2026-11-04).

```
performance/
├── transfer-load-test.js      wallet-to-wallet transfers
├── payment-load-test.js       create, authorize, capture
├── lib/setup.js               fixtures and shared settings
├── compose.performance.yaml   lifts the abuse controls for load testing
├── run.py                     runs every level and collects the metrics
└── results/
```

## Running it

Trigger the **Performance tests** workflow in GitHub Actions, or locally (Docker and Python, and
plenty of free memory):

```bash
docker compose -f compose.yaml -f performance/compose.performance.yaml up -d --build --wait
python3 performance/run.py                                  # all levels, about 20 minutes
python3 performance/run.py --users 50,100 --tests transfer  # a quick check
```

## Method

| | |
|---|---|
| **Environment** | one GitHub Actions runner: 4 vCPUs (AMD EPYC 7763), 15 GiB RAM, Ubuntu |
| **Shared machine** | the application, PostgreSQL, Kafka, Redis, Prometheus, Grafana and k6 all run on it |
| **Load** | a fixed number of virtual users, 60 s per level, each pausing 0.5 to 1.5 s between actions, spread over 200 funded wallets |
| **Measured** | the load phase only: fixtures are excluded, and a 30 s warm-up runs first |
| **Isolation** | each level waits for the outbox to drain, so one level's backlog cannot affect the next |
| **Application** | default configuration: 20 database connections, pessimistic locking, full audit trail, outbox and risk screening. Only the rate limit and velocity rule are raised, because they would refuse a load test |
| **Date** | 2026-11-04, commit `a249f26` |

## Results

### Transfers

| Users | Requests/s | p50 | p95 | p99 | Errors | DB connections: active / waiting (max) | App CPU | PostgreSQL CPU | Heap (max) | Outbox backlog | Drained in |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 | 49.8 | 7.6 ms | 20.0 ms | 146.7 ms | 0% | 3 / 0 | 58% | 21% | 150 MiB | 35 | 0 s |
| 100 | 99.0 | 7.4 ms | 20.6 ms | 182.0 ms | 0% | 6 / 0 | 65% | 42% | 147 MiB | 63 | 0 s |
| 500 | 397.0 | 121.4 ms | 920.6 ms | 1,590.9 ms | 0% | 20 / 188 | 119% | 163% | 289 MiB | 18,772 | 110 s |
| 1000 | 438.6 | 989.9 ms | 3,431.9 ms | 4,924.8 ms | 0% | 20 / 674 | 108% | 182% | 538 MiB | 23,582 | 135 s |

### Payments

Each iteration is three requests (create, authorize, capture).

| Users | Requests/s | p50 | p95 | p99 | Errors | DB connections: active / waiting (max) | App CPU | PostgreSQL CPU | Heap (max) | Outbox backlog |
|---|---|---|---|---|---|---|---|---|---|---|
| 50 | 145.6 | 6.4 ms | 26.1 ms | 167.9 ms | 0% | 4 / 0 | 61% | 50% | 523 MiB | 30 |
| 100 | 265.1 | 11.7 ms | 210.1 ms | 330.0 ms | 0% | 20 / 27 | 92% | 78% | 516 MiB | 63 |
| 500 | 350.6 | 1,051.4 ms | 2,139.0 ms | 2,821.3 ms | 0% | 20 / 412 | 98% | 103% | 573 MiB | 189 |
| 1000 | 352.5 | 2,534.0 ms | 4,701.7 ms | 5,891.6 ms | 0% | 20 / 899 | 102% | 108% | 623 MiB | 814 |

CPU is per core, so 100% is one core of the four. Time for a whole payment flow at 1000 users: 8.2 s
median, 12.8 s p99, against 21 ms median at 50 users.

## What the numbers say

**The good part.** Not one request failed at any level. Overload shows up as waiting, never as errors
or wrong balances. Up to 100 users, latency is flat and throughput tracks demand.

**Transfers run out of machine.** Throughput stops growing near 440 requests/s. Host CPU was 92% and
PostgreSQL alone used nearly two of the four cores, while also sharing the machine with k6 and Kafka.
At 1000 users, up to 674 requests were waiting for one of the 20 connections, and that queue is where
the 3.4 s p95 comes from.

**Payments hit a ceiling with CPU to spare.** Throughput flattens at about 350 requests/s, or 117
payments/s, from 500 users up, while the host stayed at 68 to 70%. Adding users only added waiting.
Since the machine was not the limit, this looks like contention, and the likely cause is the
`PAYMENT_HOLDS` system account: one row per currency that every authorize and capture must lock, so
those steps run one at a time, and connections wait in line while holding their place in the pool. This
was predicted in [ADR 003](../docs/decisions/003-pessimistic-locking.md), and the evidence here fits it,
but lock waits were not measured directly, so it remains the leading explanation rather than a proven
one. Sharding the holds account across several rows per currency would be the experiment that
settles it.

**The outbox relay is slower than the API.** This was not expected. Transfers publish one event
each, and the backlog reached 23,582 events, taking 135 s to clear afterwards. Both transfer levels
drained at about 170 events/s, and that matches payments, which only publish on capture (about
117/s) and built almost no backlog. The relay waits for Kafka's acknowledgement one event at a time,
so that is the likely limit. Nothing is lost, and the API is unaffected, but consumers and webhooks
fall behind, and above about 170 events/s sustained the backlog keeps growing. Sending a batch and
waiting for its acknowledgements together is the obvious next step.

**Virtual threads did their job.** The JVM stayed at 36 live threads throughout, even with 1000
concurrent users and hundreds of requests waiting. Waiting requests cost almost nothing, so the
limit is the connection pool, the right place for it.

## What these numbers are not

- **Not a capacity figure.** It is a shared 4-vCPU CI runner with the load generator on the same
  machine. Different hardware gives different numbers.
- **Not precise.** Each level ran once for 60 s on a shared cloud machine. An earlier run gave 470
  requests/s for transfers at 1000 users against 438.6 here, so read differences under about 10% as noise.
- **Not end to end.** There is no TLS, no network between services, and a small database. A table
  with millions of ledger rows may behave differently.
- **Not the whole platform.** Rate limits were raised, so abuse-control behaviour under load is not
  measured here. It is covered by the tests.
- Only 200 wallets were used. Contention on individual wallets would look different with far fewer.

## Next steps

1. Measure lock waits in PostgreSQL during the payment test, and then shard `PAYMENT_HOLDS`.
2. Pipeline the outbox relay's sends.
3. Test the connection pool size, because 20 is a default rather than a measured choice.
4. Run the application and the load generator on separate machines.
