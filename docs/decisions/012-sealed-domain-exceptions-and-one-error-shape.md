# ADR 012: Sealed domain exceptions and one error shape

**Status:** Accepted · 2026-11-03

## Context

Clients of a payment API branch on errors: "insufficient funds" is shown to the user, "already
captured" is treated as success, "idempotency key reused" is a client bug. Generic exceptions
with string codes scattered through services make the catalogue hard to see, easy to break, and
easy to map to the wrong HTTP status. Default Spring error pages differ between framework and
application errors and can leak exception class names or stack traces.

## Decision

- `DomainException` is a **sealed** class with four categories, each mapped to exactly one HTTP
  status by an exhaustive pattern-matching `switch` in `ApiExceptionHandler`:
  `NotFoundException` (404), `InvalidRequestException` (400),
  `BusinessRuleViolationException` (422) and `ConflictException` (409).
- Business failures clients care about get a **named type** in the module that owns the rule:
  `InsufficientFundsException`, `AccountFrozenException`, `AccountClosedException`,
  `CurrencyMismatchException`, `InvalidPaymentStateException`,
  `PaymentAlreadyCapturedException`, `DuplicateIdempotencyKeyException`.
- Every error response has the same body: `type` (stable SCREAMING_SNAKE_CASE), `title`,
  `status`, `detail`, `traceId`. Framework errors (unknown route, 405, 415, malformed JSON,
  validation) are normalised into it.
- A per-request trace id (`TraceIdFilter`) goes into the MDC, the `X-Trace-Id` header and the
  error body.
- Unexpected exceptions return a generic `500 INTERNAL_ERROR`. The stack trace is logged with
  the trace id and never returned.
- Domain exceptions skip `fillInStackTrace()`. They are expected outcomes, and a refused payment
  under load should not pay for stack walking.

## Consequences

- Adding a new failure means adding a class. The compiler forces it into a category, so it
  cannot be mapped to the wrong status by accident.
- `type` values are part of the public API contract and must not be renamed casually.
- Domain exceptions carry no stack trace. Their message and trace id are enough, because the
  throw site is unique per type.
