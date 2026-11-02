# Idempotency

## The problem

```
client ── POST /transfers (Alice → Bob €50) ──► server   transfer committed
client ◄──────────── response lost (timeout) ──── server
client ── POST /transfers (same request) ──────► server   transfer committed AGAIN 😬
```

The client cannot tell "never arrived" from "arrived, response lost", so it must retry, and
the server must make the retry harmless.

## The contract

Every money-moving `POST` accepts an optional `Idempotency-Key` header:

| Endpoint | Scope |
|----------|-------|
| `POST /api/v1/transfers` | `transfer` |
| `POST /api/v1/accounts/{id}/deposits` | `deposit` |
| `POST /api/v1/payments` | `payment.create` |
| `POST /api/v1/payments/{id}/authorize`, `/capture`, `/cancel`, `/refund` | `payment.<action>` |

| Situation | Response |
|-----------|----------|
| First request with key K | executed; response stored with K |
| Retry with K and the same request | **stored response replayed**, header `Idempotent-Replayed: true`, no side effects |
| Retry with K and a **different** request | `409 idempotency_key_reused` |
| First request with K failed with a business error (e.g. 422 insufficient funds) | nothing stored; a retry with K executes again |
| K older than `ledgerforge.idempotency.ttl` (24 h) | treated as a new key |
| Same K on two different endpoints | independent (keys are namespaced by scope) |

"Same request" means the same SHA-256 fingerprint of the scope plus the canonical JSON of the
request body and path ids.

## Table

```sql
idempotency_keys (
  scope, idempotency_key  PRIMARY KEY,
  request_hash, status (IN_PROGRESS | COMPLETED),
  response_code, response_body,
  created_at, completed_at, expires_at
)
```

## The algorithm: one transaction, one row lock

```
BEGIN
  INSERT INTO idempotency_keys (...) VALUES (K, 'IN_PROGRESS', ...) ON CONFLICT DO NOTHING
  SELECT * FROM idempotency_keys WHERE key = K FOR UPDATE
  if hash differs      → 409
  if COMPLETED         → return stored response
  else                 → run the transfer, store response, status = COMPLETED
COMMIT
```

Why this shape:

1. **The key and the money commit atomically.** There is no window in which a transfer exists
   without its key (a retry would double-spend) or a key exists without its transfer (a retry
   would wrongly replay a success that never happened). A crash at any point rolls back both.
2. **Concurrent duplicates serialise in the database.** If request #2 arrives while #1 is still
   running, its `INSERT ... ON CONFLICT` blocks on #1's uncommitted row. When #1 commits, #2's
   insert becomes a no-op, its `SELECT ... FOR UPDATE` sees `COMPLETED`, and it replays. No
   "409 request in progress" is returned to clients that retried too eagerly; they just get
   the answer.
3. **Failures release the key.** A 422 rolls back the key together with everything else, so a
   client can fix the cause (top up the wallet) and retry with the same key.

`IN_PROGRESS` is therefore never visible to another transaction; it only exists inside the
transaction that owns the key.

## Proof

`PessimisticLockingConcurrencyTest.hundredIdenticalRequestsWithOneIdempotencyKeyMoveMoneyOnce`
fires 100 identical transfers with the same key **at the same instant**:

- all 100 responses are `201` with the **same transfer id**;
- exactly 1 is fresh, 99 carry `Idempotent-Replayed: true`;
- Alice is debited €50 once, and Bob has exactly one ledger entry.

`IdempotencyApiTest` covers sequential replay, body mismatch (409), failure-then-retry, scope
isolation, deposits and key validation. `PaymentApiTest.captureIsIdempotentPerKey` shows that a
retried capture is replayed as `200` instead of failing with "already captured".

## Housekeeping

`IdempotentOperations.purgeExpiredKeys()` runs on a schedule (`cleanup-interval`, default
`PT1H`) and deletes keys past `expires_at` (indexed).
