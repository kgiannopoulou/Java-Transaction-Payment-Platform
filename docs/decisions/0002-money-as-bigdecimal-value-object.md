# ADR 0002: Money as a `BigDecimal` value object

**Status:** Accepted · 2026-11-02

## Context

Binary floating point cannot represent most decimal fractions (`0.1 + 0.2 != 0.3`). Storing
minor units in a `long` is exact, but it leaks a currency-dependent scale into every call site
and makes mistakes such as adding cents to yen easy.

## Decision

`record Money(BigDecimal amount, Currency currency)`:

- normalised to the currency's minor-unit scale; inputs that would need rounding are rejected;
- arithmetic and comparison refuse mixed currencies;
- `NUMERIC(19,4)` in PostgreSQL, JSON strings on the API.

An ArchUnit rule forbids `double`/`float` fields anywhere in production code.

## Consequences

- Exact arithmetic and explicit currency handling everywhere.
- Slightly more verbose than primitives. `BigDecimal.equals` is scale-sensitive, which the
  normalisation in the compact constructor neutralises.
