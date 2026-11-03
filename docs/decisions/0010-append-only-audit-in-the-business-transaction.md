# ADR 0010: Append-only audit trail written in the business transaction

**Status:** Accepted · 2026-11-03

## Context

Every state-changing action needs a trustworthy record of actor, action, entity, time and
request. Options: application logs; an audit consumer fed from Kafka; or a table written in the
same transaction as the change.

## Decision

- An `audit_events` table, inserted into by `AuditTrail.record` with
  `Propagation.MANDATORY`, i.e. **inside** the business transaction.
- **Append-only**: no `UPDATE`, `DELETE` or `TRUNCATE`, enforced by PostgreSQL triggers. The Java
  side has no JPA entity (records + JDBC inserts) and the repository has no mutating methods. A
  correction is a new event.
- Actor = JWT subject; the request's trace id is stored; metadata is JSONB without secrets or PII.
- **Not** fed from Kafka. The Kafka pipeline is at-least-once and eventually consistent, and an
  audit trail must never lag behind, or miss, a committed change.

## Consequences

- An audited action and its audit event commit or roll back together. Rejected operations
  leave no trace in the trail (they are visible in logs via the trace id).
- One extra insert per write request.
- The table grows forever. Plan: monthly range partitions and archival, never updates.
- Least privilege in production: the app role gets `INSERT, SELECT` only on `audit_events`.
