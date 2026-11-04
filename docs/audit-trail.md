# Audit trail

Financial systems must answer "who did what, to which entity, when, and from which request?"
months later, and the answer must be trustworthy. LedgerForge keeps an **append-only**
`audit_events` table.

```
audit_events
------------
id           UUID
actor        JWT subject (customer id or staff username), "system" for jobs/consumers
action       ACCOUNT_CREATED, ACCOUNT_FROZEN, TRANSFER_CREATED, PAYMENT_CAPTURED, ...
entity_type  CUSTOMER | ACCOUNT | TRANSFER | PAYMENT
entity_id    UUID
occurred_at  timestamptz
trace_id     same value as the X-Trace-Id response header and the log lines
metadata     JSONB: amounts, counterparties, resulting status (no secrets, no PII)
```

Actions recorded: `CUSTOMER_REGISTERED`, `ACCOUNT_CREATED`, `ACCOUNT_FROZEN`,
`ACCOUNT_UNFROZEN`, `ACCOUNT_CLOSED`, `DEPOSIT_RECORDED`, `TRANSFER_CREATED`, `PAYMENT_CREATED`,
`PAYMENT_AUTHORIZED`, `PAYMENT_CAPTURED`, `PAYMENT_CANCELLED`, `PAYMENT_REFUNDED`.

## Written in the business transaction

`AuditTrail.record(...)` is `@Transactional(propagation = MANDATORY)`. It *cannot* run outside
the caller's transaction, so:

- a committed transfer always has its `TRANSFER_CREATED` event;
- a rejected transfer (insufficient funds, frozen account, …) leaves **no** audit event,
  because it rolled back together with everything else.

Why not feed the audit trail from Kafka? An audit log that is written asynchronously can lag,
and if a consumer is broken it can miss events. Writing it in the same ACID transaction is the
stronger guarantee, and here it is cheap. The trail can still be *exported* downstream (e.g.
to a SIEM) later. See [ADR 014](decisions/014-append-only-audit-in-the-business-transaction.md).

## Append-only: never `UPDATE audit_events`

A correction is a **new** event. History is never edited. This is enforced three ways:

1. **Database:** `BEFORE UPDATE OR DELETE` row trigger and `BEFORE TRUNCATE` statement trigger
   raise `audit_events is append-only`. They fire even for the table owner.
2. **Code shape:** `AuditEvent` is a Java `record`, not a JPA entity, so no managed object can be
   dirty-checked into an `UPDATE`. `AuditEventRepository` has `append` and `search`, and nothing
   else.
3. **Deployment (recommended):** run the application as a database role with only
   `INSERT, SELECT` on `audit_events`. Migrations run as the owner.

`AuditTrailApiTest` proves it: raw `UPDATE`, `DELETE` and `TRUNCATE` all fail, and the row is
unchanged afterwards.

Growth: the table only grows. At scale it would be range-partitioned by `occurred_at` (monthly)
and old partitions moved to cheaper storage. They would still never be updated.

## Reading the trail

`GET /api/v1/audit-events?entityType=&entityId=&actor=&action=&page=&size=` (AUDITOR, ADMIN),
newest first:

```json
{
  "items": [{
    "id": "…", "actor": "3a4412c5-…", "action": "TRANSFER_CREATED",
    "entityType": "TRANSFER", "entityId": "bfb67e01-…", "occurredAt": "2026-11-03T09:28:41Z",
    "traceId": "9d1c2f0e…",
    "metadata": { "sourceAccountId": "…", "destinationAccountId": "…",
                  "amount": "12000.00", "currency": "EUR", "ledgerTransactionId": "…" }
  }],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```
