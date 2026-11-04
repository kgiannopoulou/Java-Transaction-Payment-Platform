# ADR 015: Migration layout, and a one-time re-baseline

**Status:** Accepted · 2026-11-04

## Context

Migrations grew as `V1`..`V9`, in the order features were built. `V1` mixed customers and
accounts, the seed data had its own file between them, and single-digit versions stop sorting
correctly at `V10` in a plain file listing.

The normal rule is that an applied migration is never touched, and that rule is what makes
Flyway trustworthy. It protects databases that exist. At this point the only databases are
throwaway ones: Testcontainers instances and local Compose volumes. Nothing has been deployed.

## Decision

- Reorganise once into `V001__create_customers.sql` .. `V009__create_event_consumers.sql`, one
  concern per file, in dependency order. The statements are moved, not changed.
- New constraints go in a separate `V010__tighten_constraints.sql`, as `ALTER TABLE`, rather than
  being folded into the `CREATE TABLE` files. That keeps the re-baseline a pure move that can be
  reviewed as such.
- **From this commit on, the normal rule applies with no exceptions:** applied migrations are
  immutable, and every change is a new file.

## Consequences

- The schema reads in a sensible order, and three-digit versions sort correctly.
- Any existing local database has to be recreated once (`docker compose down -v`). Flyway would
  otherwise fail validation against the old history.
- This option expires at the first real deployment. After that, the same cleanup would need a
  baseline migration and `flyway.baselineVersion`, not a rename.
