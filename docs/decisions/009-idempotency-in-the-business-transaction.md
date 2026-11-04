# ADR 009: Idempotency keys stored in the business transaction

**Status:** Accepted · 2026-11-02

## Context

Clients must retry after timeouts, and a retry must not move money twice. Common
implementations store keys in Redis or in a separate transaction. That leaves windows where the
money moved but the key was not recorded (double-spend on retry), or the key was recorded but
the money did not move (a false success is replayed).

## Decision

Store keys in PostgreSQL, in the **same transaction** as the operation:
`INSERT ... ON CONFLICT DO NOTHING` + `SELECT ... FOR UPDATE`, compare a SHA-256 request
fingerprint, replay stored responses, and return 409 when a key is reused with a different
body. Keys are namespaced by operation scope and expire after 24 h.

## Consequences

- Exactly-once effect per key, proven with 100 simultaneous identical requests.
- Concurrent duplicates wait for the first request and then replay its response.
- Business failures are not cached (the key is rolled back), so clients can fix the cause and
  retry with the same key.
- Keys add load to the primary database. That is acceptable at this scale; Redis would only be
  considered if measurements called for it.
