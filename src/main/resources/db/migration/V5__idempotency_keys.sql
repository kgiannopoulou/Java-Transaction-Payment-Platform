-- Stored results of idempotent POST requests. The row is inserted and locked in the same
-- database transaction as the business operation, so a key can never be "completed"
-- without its side effects (or vice versa). See docs/idempotency.md.
CREATE TABLE idempotency_keys
(
    scope           VARCHAR(50)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_code   INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX ix_idempotency_keys_expires_at ON idempotency_keys (expires_at);
