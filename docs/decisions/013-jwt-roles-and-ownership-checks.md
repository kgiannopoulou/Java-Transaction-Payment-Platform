# ADR 013: Stateless JWT, non-hierarchical roles, ownership checks

**Status:** Accepted · 2026-11-03

## Context

The API moves money, so who may call what has to be explicit and tested. Four kinds of user
exist: customers, support agents, auditors and administrators. They have different and
partly *conflicting* needs. Support must see transactions but never change them. Auditors
must see the books but not customer PII. Admins run operations but must not spend customer
money.

## Decision

- Spring Security OAuth2 **resource server** with JWT bearer tokens; stateless, no sessions, CSRF
  disabled (no cookies). HS256 with a ≥ 32-byte secret for now; swapping in an IdP's JWKS only
  replaces the `JwtDecoder` bean.
- Roles `CUSTOMER`, `SUPPORT`, `AUDITOR`, `ADMIN`, deliberately **not hierarchical**.
- Two layers: URL role gates in the filter chain (deny by default), plus per-resource
  **ownership** checks via `@PreAuthorize("@access.…")` backed by `AccessPolicy`.
- Customers get **403 for any resource that is not theirs, including non-existent ids**, so ids
  cannot be enumerated.
- 401/403 use the standard error shape; reasons for token rejection are not disclosed.
- A dev-only token endpoint, off by default, enabled only in Compose.

## Consequences

- Every rule is covered by `AuthorizationApiTest` over real HTTP with real tokens.
- Ownership checks cost one indexed lookup per request. That is acceptable, and cacheable
  later because ownership is immutable.
- Feature modules stay free of security imports (SpEL names the bean), so module boundaries hold.
- No refresh tokens, token revocation or multi-tenant merchants yet. Those belong to the
  identity provider, or to a later phase.
