# BigDecimal for money

**Status:** Accepted · 2026-11-02

## Context

Binary floating point cannot represent most decimal fractions: `0.1 + 0.2 != 0.3`. In a ledger,
errors like that accumulate, and books that are off by a cent do not balance.

## Options considered

| Option | For | Against |
|--------|-----|---------|
| `double` | fast, simple | wrong: it rounds silently |
| `long` in minor units | exact and fast | the scale depends on the currency, so it leaks into every call site; adding cents to yen compiles fine |
| Bare `BigDecimal` | exact | `equals` depends on scale; nothing stops mixed currencies |
| **`Money` value object around `BigDecimal`** | exact, and currency rules live in one place | more verbose, slower than primitives (negligible beside a database round trip) |

## Decision

`record Money(BigDecimal amount, Currency currency)`:

- normalised to the currency's minor-unit scale, and input that would need rounding is **rejected**
  rather than rounded;
- arithmetic and comparison refuse mixed currencies;
- `NUMERIC(19,4)` in PostgreSQL and strings in JSON, so no client rounds through a `double`.

`ArchitectureTest` forbids `double` and `float` fields anywhere in production code. It caught one, a
retry multiplier in the Kafka configuration.

## Consequences

- Arithmetic is exact and currency handling is explicit everywhere.
- Normalising in the constructor neutralises `BigDecimal.equals` being scale-sensitive.
- `DatabaseConstraintsTest` checks that every money column is `NUMERIC(19,4)`, and
  `DomainPropertyTest` that repeated arithmetic never drifts.
