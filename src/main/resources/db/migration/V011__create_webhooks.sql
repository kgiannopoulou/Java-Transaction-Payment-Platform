-- Customer-registered webhooks. See docs/webhooks.md.
--
-- Replaces V009's webhook_deliveries, which only recorded that a notification had been sent.
-- Delivery is now a durable state machine with its own retry schedule, independent of Kafka.
DROP TABLE webhook_deliveries;

CREATE TABLE webhook_endpoints
(
    id                UUID PRIMARY KEY,
    account_id        UUID          NOT NULL REFERENCES accounts (id),
    url               VARCHAR(2048) NOT NULL CHECK (url ~ '^https?://'),
    description       VARCHAR(255),
    event_types       TEXT[]        NOT NULL CHECK (cardinality(event_types) > 0),
    -- The signing secret has to be recoverable to compute HMACs, so it cannot be hashed like a
    -- password. It is stored AES-256-GCM encrypted under a key that never enters the database.
    secret_ciphertext TEXT          NOT NULL,
    status            VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at        TIMESTAMPTZ   NOT NULL,
    disabled_at       TIMESTAMPTZ,

    CONSTRAINT ck_webhook_endpoints_disabled_at CHECK ((status = 'DISABLED') = (disabled_at IS NOT NULL))
);

CREATE INDEX ix_webhook_endpoints_account ON webhook_endpoints (account_id, created_at DESC);

-- One row per (endpoint, event): the unit of retry.
CREATE TABLE webhook_deliveries
(
    id              UUID PRIMARY KEY,
    endpoint_id     UUID         NOT NULL REFERENCES webhook_endpoints (id),
    event_id        UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    -- TEXT, not JSONB: the signature covers these exact bytes, and JSONB would normalise them.
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts        INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    delivered_at    TIMESTAMPTZ,

    -- A redelivered Kafka record cannot fan out twice.
    CONSTRAINT ux_webhook_deliveries_endpoint_event UNIQUE (endpoint_id, event_id),
    CONSTRAINT ck_webhook_deliveries_delivered_at CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

-- The dispatcher's query. Partial, so it holds only the work still to do.
CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id, created_at DESC);

-- Delivery history: one row per HTTP attempt, kept forever and never changed.
CREATE TABLE webhook_delivery_attempts
(
    id              UUID PRIMARY KEY,
    delivery_id     UUID        NOT NULL REFERENCES webhook_deliveries (id),
    attempt_number  INTEGER     NOT NULL CHECK (attempt_number >= 1),
    started_at      TIMESTAMPTZ NOT NULL,
    duration_ms     BIGINT      NOT NULL CHECK (duration_ms >= 0),
    outcome         VARCHAR(20) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    response_status INTEGER CHECK (response_status BETWEEN 100 AND 599),
    error           TEXT,

    CONSTRAINT ux_webhook_attempts_number UNIQUE (delivery_id, attempt_number)
);

CREATE FUNCTION webhook_attempts_reject_mutation() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'webhook_delivery_attempts is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_webhook_attempts_append_only
    BEFORE UPDATE OR DELETE
    ON webhook_delivery_attempts
    FOR EACH ROW
EXECUTE FUNCTION webhook_attempts_reject_mutation();
