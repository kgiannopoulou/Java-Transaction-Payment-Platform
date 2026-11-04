# Logging

Logs go through SLF4J to stdout, as one JSON object per line (Elastic Common Schema) in Compose, and
as readable text during local development. `System.out` and `java.util.logging` are not used, and
`ArchitectureTest` fails the build if anyone adds them.

```json
{
  "@timestamp": "2026-11-04T09:13:44.781Z",
  "log": { "level": "INFO", "logger": "…payment.application.PaymentService" },
  "message": "Payment created: status=CREATED risk=ALLOW",
  "traceId": "d811d7be28914ded87f23d6b65785215",
  "customerId": "e7f30cea-b634-4361-9148-9b078986…",
  "paymentId": "3f0c8d2a-…"
}
```

Turn it on anywhere with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.

## Context on every line

| Field | Set by | Scope |
|-------|--------|-------|
| `traceId` | `TraceIdFilter` | the request; also the `X-Trace-Id` header and the `traceId` in error bodies |
| `customerId` | `CustomerLoggingFilter`, after token validation | the request |
| `paymentId`, `transferId` | the services, as soon as the id is known | the request |
| `eventId` | Kafka listeners and the webhook dispatcher | one event |
| `deliveryId` | the webhook dispatcher | one attempt |

So one query answers "what happened to this payment?", and a customer quoting the `traceId` from an
error response leads straight to the log lines for that request.

Two details matter here:

- **Context is cleared once, at the very end of the request** (`TraceIdFilter`). Clearing it earlier,
  when the service returns, would lose it just before the exception handler logs the failure.
- **Worker threads set their own context.** Kafka listeners and webhook workers have no request
  around them, so they use `MDC.putCloseable`, which also cleans up after each unit of work.

## What must never be logged

| Never | Why | How it is kept out |
|-------|-----|--------------------|
| Passwords, JWTs, `Authorization` headers | a logged token is a working credential until it expires | no request logging; rejected tokens are not echoed |
| Webhook signing secrets | they would allow forged webhooks | a secret exists in plaintext only at registration and while signing |
| Encryption keys, database passwords | | configuration values are never logged |
| Customer names and emails | personal data, and log retention rarely matches its rules | only opaque ids go in the context |
| Payment details and amounts | routine logs do not need them | ids and statuses only; amounts live in the ledger and audit trail, under access control |
| Request and response bodies | they contain all of the above | never logged |

The rule behind the table: **log identifiers, not data.** An id lets someone with the right access
look up the record, and is useless to anyone reading the logs without it. There is no card data in
this system, and if there were, it would need its own controls.

### A leak this review found

Database errors were logged in full:

```
Data integrity violation: ERROR: duplicate key value violates unique constraint "ux_customers_email"
  Detail: Key (lower(email::text))=(someone@example.com) already exists.
```

PostgreSQL's `Detail` line repeats the row's values, so a race on customer registration wrote an
email address to the logs. `ApiExceptionHandler` now logs only the first line, which still names the
constraint. It is an easy leak to miss, because no code ever looks like it is logging an email.

## Levels

| Level | Meaning |
|-------|---------|
| `ERROR` | needs attention: unexpected exceptions, jobs that failed |
| `WARN` | handled, but worth seeing: retries, Redis unavailable, a delivery going dead |
| `INFO` | business milestones: payment created, transfer completed, risk alert |
| `DEBUG` | diagnostics: rejected requests, replays. Off by default |

Rejected requests are `DEBUG` because they are normal outcomes, and an attacker should not be able
to fill the logs at `WARN`. They are still counted in metrics.

## Tests

| Test | Proves |
|------|--------|
| `StructuredLoggingTest` | at DEBUG level, real log events carry `traceId`, `customerId`, `paymentId` and `transferId`; the `traceId` in an error response finds its log line; context ends with the request; no token, webhook secret, name, email or amount appears in messages, context or exceptions |
| `ApiExceptionHandlerTest` | a database error's `Detail` line does not reach the log |
| `ArchitectureTest` | no `System.out`, `System.err` or `java.util.logging` in production code |

The JSON format itself was checked against the running Compose stack.
