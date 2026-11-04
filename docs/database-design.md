# Database design

PostgreSQL is the source of truth for everything that matters: balances, the ledger, payments,
the audit trail. The schema is treated as part of the application's correctness, not as a place
to park objects. Hibernate never creates or changes it (`ddl-auto: validate`); Flyway owns it.

## Migrations

```
db/migration/
  V001__create_customers.sql
  V002__create_accounts.sql          + the seeded system accounts
  V003__create_ledger.sql            + balance, currency and append-only triggers
  V004__create_transfers.sql
  V005__create_payments.sql
  V006__create_idempotency.sql
  V007__create_outbox.sql
  V008__create_audit_events.sql      + append-only triggers
  V009__create_event_consumers.sql   processed_events, risk_alerts
  V010__tighten_constraints.sql      the constraints listed below
  V011+                              one per later feature
```

Rules:

- **A migration that has been applied is never edited.** Changes are new migrations. Flyway's
  checksum validation fails the application at startup if an applied file changes.
- **Three-digit versions.** Files sort correctly in any file listing.
- **One purpose per migration**, named after what it does.
- A fresh database and a migrated one always end up identical, because there is only one path:
  every test run starts from an empty PostgreSQL and applies every migration.

The numbering was re-baselined once, before the first deployment. See
[ADR 015](decisions/015-migration-layout-and-rebaseline.md).

## Defence in depth

Every important rule is enforced at three levels, each with a different job:

| Layer | Job | Example |
|-------|-----|---------|
| **Java validation** (Bean Validation on request records) | reject bad input at the edge, with a helpful `400` | `@DecimalMin(value = "0", inclusive = false) BigDecimal amount` |
| **Domain invariants** (entities, value objects) | keep the model correct while it changes | `Payment.create` refuses non-positive amounts; `requireTransition` guards the state machine |
| **Database constraints** | make invalid rows impossible to store, whoever writes them | `CHECK (amount > 0)` |

The first two are for users and developers. The third is for the day something gets past them:
a bug, a manual `UPDATE` during an incident, a future service writing to the same table, or a
race between two valid-looking requests. If a rule only exists in Java, it holds only for code
paths that remember to call it.

### What the database enforces

| Rule | Mechanism | Where |
|------|-----------|-------|
| Amounts are positive | `CHECK (amount > 0)` | payments, transfers, ledger entries, risk alerts |
| Money is exact | `NUMERIC(19,4)` everywhere; a test fails if a money column is anything else | all |
| Wallets never go negative | `ck_accounts_non_negative` | accounts |
| A closed account is empty | `ck_accounts_closed_is_empty` | accounts |
| Wallets have an owner, system accounts do not | `ck_accounts_owner` | accounts |
| One wallet per customer per currency | partial unique index | accounts |
| Email unique, ignoring case | unique index on `lower(email)` | customers |
| Every ledger transaction balances | deferred constraint trigger, checked at `COMMIT` | ledger entries |
| A business event is booked once | `UNIQUE (reference)` | ledger transactions |
| The ledger and audit trail are append-only | `BEFORE UPDATE/DELETE/TRUNCATE` triggers | ledger, audit_events |
| Transfer and payment currency match both accounts | composite foreign keys to `accounts (id, currency)` | transfers, payments |
| A completed transfer has its ledger transaction | `ck_transfers_completed` | transfers |
| Refunds never exceed the payment | `ck_payments_refund_limit` | payments |
| Refunded amount agrees with the status | `ck_payments_refund_matches_status` | payments |
| Status values are known | `CHECK (status IN (...))` | all state columns |
| A completed idempotency key has a response to replay | `ck_idempotency_completed_has_response` | idempotency_keys |
| A consumer handles an event once | primary key `(consumer, event_id)` | processed_events |

### Composite foreign keys instead of triggers

"A payment's currency must equal both accounts' currency" spans tables, which a `CHECK` cannot
do. The usual answer is a trigger. A composite foreign key is simpler and cannot be disabled by
accident:

```sql
ALTER TABLE accounts ADD CONSTRAINT ux_accounts_id_currency UNIQUE (id, currency);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_payer_currency FOREIGN KEY (payer_account_id, currency)
        REFERENCES accounts (id, currency);
```

`(id, currency)` is trivially unique because `id` already is. Declaring it gives the foreign key
something to reference, and a wrong-currency row then has no parent to point at.

## Indexes

Indexes follow actual queries rather than every column:

| Query | Index |
|-------|-------|
| Account statement, newest first | `ix_ledger_entries_account_created (account_id, created_at DESC, id)` |
| A customer's payments and transfers | `(account_id, created_at DESC)` on each side |
| Outbox relay: oldest pending | partial `ix_outbox_events_pending (sequence_number) WHERE published_at IS NULL` |
| Audit search by entity or actor | `(entity_type, entity_id, occurred_at DESC)`, `(actor, occurred_at DESC)` |

Partial indexes matter for queue-like tables. The relay's index holds only unpublished rows, so
it stays small however many published rows accumulate.

## Tests

| Test | Proves |
|------|--------|
| `DatabaseConstraintsTest` | raw SQL that skips Java entirely cannot store a non-positive amount, a negative wallet, a wrong-currency payment or transfer, a refund that disagrees with its status, a non-empty closed account, an unknown status, a completed idempotency key without a response, a duplicate wallet or a blank name; every table has a primary key; every money column is `NUMERIC(19,4)` |
| `LedgerDatabaseGuaranteesTest` | raw SQL cannot create unbalanced, mismatched-currency or edited ledger data |
| `AuditTrailApiTest` | `UPDATE`, `DELETE` and `TRUNCATE` on the audit trail are rejected |
