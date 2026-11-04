# Observability

```bash
docker compose --profile demo up     # the platform, with generated traffic
```

| | URL | |
|---|-----|---|
| Grafana | <http://localhost:3002> | opens straight on the LedgerForge dashboard, read-only, no login |
| Prometheus | <http://localhost:9091> | queries, targets, alert rules |
| Health | <http://localhost:8080/actuator/health> | public, with liveness and readiness probes under it |

## The dashboard

| Panel | Query | What it answers |
|-------|-------|-----------------|
| Payment throughput | `rate(payments_created_total[1m])` | how much work is flowing? |
| Success rate | created ÷ (created + failed creations) | are customers being refused? |
| p95 latency | `histogram_quantile(0.95, … http_server_requests_seconds_bucket{uri="/api/v1/payments"} …)` | what do the slowest customers feel? |
| Failed payments | `rate(payments_failed_total[1m])` by operation and reason | why are they failing? |
| Kafka lag | `kafka_consumer_fetch_manager_records_lag_max` | are consumers keeping up? |
| Pending outbox events | `outbox_pending_events` | are events reaching Kafka? |
| Webhook failures | `rate(webhook_failures_total[1m])` | are customers' endpoints reachable? |

The dashboard and data source are provisioned from `monitoring/grafana/`, so nothing is set up by hand.

## Metrics

| Metric | Type | Labels | Notes |
|--------|------|--------|-------|
| `payments_created_total` | counter | | see the naming note below |
| `payments_failed_total` | counter | `operation`, `reason` | `reason` is the API error type, such as `PAYMENT_BLOCKED` |
| `transfers_total` | counter | `outcome` | `completed`, `failed`, `replayed` |
| `transfer_duration_seconds` | histogram | `outcome` | |
| `webhook_failures_total` | counter | | every failed attempt, including ones that later succeed |
| `outbox_pending_events` | gauge | | read from the table at each scrape |
| `http_server_requests_seconds` | histogram | `uri`, `method`, `status` | from Spring, with histogram buckets turned on |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | `client_id` | from the Kafka client |
| `ledgerforge_ratelimit_decisions_total` | counter | `result` | `unavailable` means Redis was down and the limiter failed open |
| `ledgerforge_risk_decisions_total` | counter | `outcome` | `ALLOW`, `REVIEW`, `BLOCK` |
| `ledgerforge_cache_requests_total` | counter | `result` | hit, miss, unavailable |
| `ledgerforge_webhooks_dead_total` | counter | | deliveries that used up all their retries |

### Design decisions

**Counted after the transaction resolves.** Payment and transfer metrics are recorded in the
controllers, once the outcome is known. Counting inside the transaction would also count work that
later rolls back, and an idempotent replay would look like a new payment.

**Labels are bounded.** Operation, outcome and error type are small fixed sets. Ids are never
labels, because every distinct value creates a new time series, and one series per payment would
take Prometheus down. `MetricsApiTest.noIdEverBecomesALabel` checks this.

**Histograms, not client-side percentiles.** A p95 computed by each instance cannot be combined
across instances, since an average of percentiles means nothing. Exporting buckets lets Prometheus
calculate the real p95 for the whole fleet.

**Counters exist from the start.** A Micrometer counter normally appears at its first increment,
so a healthy system would show "no data" rather than `0`, and alerts would have nothing to evaluate.
This came up when checking the running stack: the webhook panel was empty.

**`payments_created_total` is renamed in Prometheus.** The Prometheus client reserves the `_created`
suffix for OpenMetrics timestamps and strips it, so `/actuator/prometheus` shows `payments_total`.
A `metric_relabel_configs` rule in `monitoring/prometheus/prometheus.yml` stores it under the intended
name, which is what dashboards and alerts use.

## Access

| Endpoint | Access |
|----------|--------|
| `/actuator/health`, `/health/liveness`, `/health/readiness` | public, for probes; component details for ADMIN only |
| `/actuator/info` | public |
| `/actuator/metrics` | ADMIN |
| `/actuator/prometheus` | HTTP Basic, user `prometheus` |

Prometheus cannot present a short-lived JWT, so the scrape endpoint has its own small security chain
with a dedicated user who can read that endpoint and nothing else. An admin token does not open it,
and the scrape user cannot reach anything else (`MetricsApiTest`). The password comes from
`LEDGERFORGE_METRICS_PASSWORD`, and **with none configured, nobody can scrape**: metrics reveal
traffic volumes, so the endpoint is closed by default.

The password in `compose.yaml` and `prometheus.yml` is a local demo value.

## Alerts

Prometheus evaluates the rules in `monitoring/prometheus/alerts.yml`: `LedgerForgeDown`,
`OutboxBacklogGrowing`, `PaymentFailureRateHigh` and `WebhookFailuresHigh`. They are visible under
*Alerts* in Prometheus. There is no Alertmanager in the demo stack, so they do not notify anyone.

## Not included

- **Distributed tracing.** Each request has a `traceId` for logs, but spans are not exported. OpenTelemetry would be the next step.
- **Log aggregation.** Logs are structured JSON on stdout, ready for Loki or Elasticsearch, but the stack does not include one.
