# Error handling

Every failure, from a business rule, Bean Validation, the framework or a bug, is returned in
one shape (`Content-Type: application/problem+json`, RFC 9457):

```json
{
  "type": "INSUFFICIENT_FUNDS",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Account 3f0c… has insufficient available funds",
  "traceId": "9d1c2f0e5b8a4c7d9e6f1a2b3c4d5e6f"
}
```

| Field | Meaning |
|-------|---------|
| `type` | Stable machine-readable identifier. Clients branch on this, never on `detail`. |
| `title` | Short human-readable summary of the problem type. |
| `status` | HTTP status, repeated in the body for convenience. |
| `detail` | What went wrong in *this* request. |
| `traceId` | Same value as the `X-Trace-Id` response header and the `[traceId]` prefix of every server log line for the request. A valid incoming `X-Trace-Id` (8–64 chars of `[A-Za-z0-9-]`) is adopted, otherwise one is generated. |
| `errors` | Only on `VALIDATION_FAILED`: `[{ "field": …, "message": … }]`. |

## Exception hierarchy

```
RuntimeException
└── DomainException (sealed)                               type + title + message
    ├── NotFoundException                    404           ACCOUNT_NOT_FOUND, PAYMENT_NOT_FOUND, …
    ├── InvalidRequestException              400
    │   └── InvalidMoneyException                          INVALID_AMOUNT
    ├── BusinessRuleViolationException       422
    │   ├── InsufficientFundsException                     INSUFFICIENT_FUNDS
    │   ├── AccountFrozenException                         ACCOUNT_FROZEN
    │   ├── AccountClosedException                         ACCOUNT_CLOSED
    │   └── CurrencyMismatchException                      CURRENCY_MISMATCH
    └── ConflictException                    409
        ├── InvalidPaymentStateException                   INVALID_PAYMENT_STATE
        │   └── PaymentAlreadyCapturedException            PAYMENT_ALREADY_CAPTURED
        └── DuplicateIdempotencyKeyException               IDEMPOTENCY_KEY_REUSED
```

`ApiExceptionHandler` maps the four sealed categories with an exhaustive `switch`. Adding a
fifth category is a compile error until it has a status. Named exceptions live in the module
that owns the rule (`account.domain`, `payment.domain`, `idempotency.domain`). Only the category
classes live in `shared.error`.

## Catalogue

| Status | `type` | When |
|--------|--------|------|
| 400 | `VALIDATION_FAILED` | Bean Validation failed. See `errors`. |
| 400 | `MALFORMED_REQUEST` | Body missing or not valid JSON for the endpoint. |
| 400 | `INVALID_PARAMETER` | Path/query parameter of the wrong type (e.g. not a UUID). |
| 400 | `INVALID_AMOUNT` | More decimal places than the currency allows. |
| 400 | `INVALID_IDEMPOTENCY_KEY` | Blank or longer than 255 characters. |
| 401 | `UNAUTHENTICATED` | Missing, invalid, expired or foreign token. The reason is not disclosed. |
| 403 | `ACCESS_DENIED` | Role not allowed, or the resource is not yours (also for ids that do not exist; see [security.md](security.md)). |
| 404 | `CUSTOMER_NOT_FOUND`, `ACCOUNT_NOT_FOUND`, `TRANSFER_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `LEDGER_TRANSACTION_NOT_FOUND` | Unknown id. |
| 404 | `NOT_FOUND` | Unknown route. |
| 405 / 415 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` | Framework errors, same shape. |
| 409 | `EMAIL_ALREADY_REGISTERED`, `ACCOUNT_ALREADY_EXISTS` | Uniqueness. |
| 409 | `INVALID_PAYMENT_STATE` | Transition not allowed, e.g. `CANCELLED → CAPTURED`. |
| 409 | `PAYMENT_ALREADY_CAPTURED` | Capture of a `CAPTURED` / `PARTIALLY_REFUNDED` / `REFUNDED` payment. |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Same `Idempotency-Key`, different request body. |
| 409 | `CONCURRENT_MODIFICATION` | Optimistic-locking retries exhausted. |
| 409 | `CONSTRAINT_VIOLATION` | A database constraint caught a race. |
| 422 | `INSUFFICIENT_FUNDS` | Debit exceeds the available balance. |
| 422 | `ACCOUNT_FROZEN` / `ACCOUNT_CLOSED` | Money movement on an inactive wallet. |
| 422 | `CURRENCY_MISMATCH` | Account, counterparty and request currencies differ. |
| 422 | `SAME_ACCOUNT`, `NOT_A_WALLET`, `UNSUPPORTED_CURRENCY`, `CUSTOMER_NOT_ACTIVE`, `ACCOUNT_BALANCE_NOT_ZERO`, `REFUND_EXCEEDS_CAPTURED`, `SYSTEM_ACCOUNT` | Other business rules. |
| 500 | `INTERNAL_ERROR` | Anything unexpected. The body is generic; the stack trace is in the logs under the `traceId`. |

## What is never returned

- Stack traces, exception class names and SQL. `server.error.include-stacktrace=never`,
  `include-exception=false` and `include-message=never` back this up for the container's
  fallback error page.
- Internal messages of unexpected exceptions. `ApiExceptionHandlerTest` throws one containing a
  password and a JDBC URL and asserts neither appears in the response.

Business failures roll back the whole transaction, including the idempotency key (see
[`idempotency.md`](idempotency.md)), so a client can fix the cause and retry with the same key.

Tests: `ErrorResponseApiTest` (over HTTP) and `ApiExceptionHandlerTest` (unit).
