# Security: authentication and authorization

LedgerForge is a stateless **OAuth2 resource server**. Every request carries a signed JWT
(`Authorization: Bearer …`). There are no sessions or cookies, so CSRF protection is not
needed and is disabled.

## Tokens

| Claim | Meaning |
|-------|---------|
| `sub` | customer id (customers) or staff username |
| `roles` | e.g. `["CUSTOMER"]`, `["SUPPORT"]`. Mapped to `ROLE_*` authorities |
| `customer_id` | customers only. Ownership checks compare against it |
| `iss`, `exp`, `iat`, `jti` | validated: issuer must match, expired tokens are rejected |

Tokens are HS256-signed with `ledgerforge.security.jwt.secret` (≥ 32 bytes, injected via
`LEDGERFORGE_JWT_SECRET`; startup fails if it is shorter). In production you would typically
point the resource server at an identity provider's JWKS (Keycloak, Auth0, Entra ID) instead.
Only the `JwtDecoder` bean changes; none of the authorization rules do.

For local use, Compose enables `POST /api/v1/auth/token`, which mints a token for any role
(`ledgerforge.security.dev-tokens.enabled`). It is **off by default** and must never be enabled
outside a demo environment.

## Roles

| Role | May | May not |
|------|-----|---------|
| `CUSTOMER` | open own wallets; read own customer profile, wallets, history, transfers, payments; transfer from own wallets; pay (as payer: create, authorize, cancel); collect (as merchant: capture, refund, cancel); manage webhook endpoints on own wallets | anything belonging to another customer; deposits; freeze/close; back-office reads |
| `SUPPORT` | read any customer, wallet, history, transfer, payment, ledger transaction, risk alert, risk decision, webhook delivery; **approve or reject payments under review** | move money, deposit, freeze, read the audit trail, change webhooks |
| `AUDITOR` | read wallets, history, ledger transactions, transfers, payments, reconciliation, **audit events**, risk alerts, risk decisions, webhook deliveries | modify anything; read customer profiles (PII) |
| `ADMIN` | onboard customers, open wallets for anyone, deposit, freeze / unfreeze / close, approve or reject payments under review, read everything incl. audit events, actuator details | move a customer's money (transfer or pay on their behalf); change a customer's webhooks |

Roles are **not hierarchical**. An admin who could also spend customers' money, or an auditor
who could also change what they audit, would defeat separation of duties.

## Two layers of checks

```
request ─► BearerTokenAuthenticationFilter ─► URL rules (role gates)  ─► @PreAuthorize (ownership) ─► controller
              401 if missing/invalid           403 e.g. SUPPORT POST     403 e.g. Alice → Bob's account
```

1. **Role gates** (`SecurityConfiguration`): e.g. only `CUSTOMER` may `POST /api/v1/transfers`,
   only `ADMIN` may freeze, only `AUDITOR`/`ADMIN` may read `/api/v1/audit-events`. Anything not
   listed is denied (`anyRequest().denyAll()`).
2. **Ownership** (`AccessPolicy`, the `access` bean), referenced from controllers:

   ```java
   @PostMapping
   @PreAuthorize("@access.ownsAccount(#request.sourceAccountId())")
   ResponseEntity<TransferResponse> create(...)
   ```

   Ownership is read from the database (`accounts.customer_id`, the payment's payer/merchant
   wallets) and compared to the token's `customer_id`. It is checked *before* the business
   transaction. That is safe because ownership never changes after creation, which is also why
   the account lookup can be cached in Redis ([redis.md](redis.md)).

Feature modules do not depend on the security module at compile time (the SpEL string names
the bean), so the module graph stays acyclic. `ArchitectureTest` checks that.

### 403, not 404, for other customers' resources

A customer asking for an account that is not theirs gets **403 whether or not it exists**.
Returning 404 for "doesn't exist" and 403 for "exists but isn't yours" would let anyone
enumerate valid account ids. Staff roles, who are allowed to know, get the usual 404.

### Error responses

401 and 403 use the same error shape as every other error (`UNAUTHENTICATED`,
`ACCESS_DENIED`, with a `traceId`). The 401 never explains *why* a token was rejected
(expired, bad signature, wrong issuer); that would help an attacker more than a client.

### Abuse controls

- **Rate limits**: 100 `POST /payments` per minute per account, after authorization, so nobody can
  use up someone else's allowance ([redis.md](redis.md)).
- **Risk screening** refuses or holds payments, and a refusal never says which rule fired
  ([risk-engine.md](risk-engine.md)).
- **Metrics**: `/actuator/prometheus` has its own HTTP Basic user, which can read nothing else, and
  is closed unless a password is configured ([observability.md](observability.md)).
- **Logs** hold identifiers, never tokens, secrets or personal data ([logging.md](logging.md)).
- **Webhooks** are outbound calls to customer-chosen URLs, so they have SSRF protection and
  encrypted signing secrets ([webhooks.md](webhooks.md)).

## Tested, not just configured

`AuthorizationApiTest` runs against the real filter chain with real signed tokens:

- Alice → `GET /accounts/{Bob's account}` → **403**, no balance leaked; Bob → 200
- Alice cannot read Bob's history, profile or wallet list; cannot transfer out of Bob's wallet
  (balance unchanged); cannot open a wallet for Bob
- unknown ids: 403 for customers, 404 for support
- transfers visible to both participants only; payment commands only by the right side
  (payer authorizes, merchant captures/refunds)
- customers cannot deposit, unfreeze, onboard, read audit/reconciliation/ledger
- SUPPORT reads but cannot transfer, deposit, freeze or read the audit trail
- AUDITOR reads ledger, reconciliation and audit, but no profiles and no writes
- ADMIN administers but **cannot move customer money**
- missing, forged (other key), expired, foreign-issuer and garbage tokens → 401; a valid token
  without roles → 403
