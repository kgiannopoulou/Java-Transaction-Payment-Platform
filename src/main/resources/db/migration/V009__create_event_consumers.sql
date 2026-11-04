-- Consumer side of the event pipeline.

-- Idempotent consumers: Kafka delivers at-least-once, so the same event can arrive twice
-- (redelivery after a crash or rebalance, a relay that sent but crashed before marking the row
-- published). Each consumer records the event ids it has handled IN THE SAME TRANSACTION as its
-- side effects; a second delivery finds the row and is skipped.
CREATE TABLE processed_events
(
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- Risk engine output.
CREATE TABLE risk_alerts
(
    id             UUID PRIMARY KEY,
    event_id       UUID           NOT NULL,
    rule           VARCHAR(50)    NOT NULL,
    account_id     UUID           NOT NULL,
    reference_type VARCHAR(30)    NOT NULL,
    reference_id   UUID           NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_risk_alerts_event_rule UNIQUE (event_id, rule)
);

CREATE INDEX ix_risk_alerts_account ON risk_alerts (account_id, created_at DESC);

-- Webhook notifications sent to merchants. UNIQUE (event_id) is a second line of defence
-- behind processed_events.
CREATE TABLE webhook_deliveries
(
    id                  UUID PRIMARY KEY,
    event_id            UUID         NOT NULL UNIQUE,
    event_type          VARCHAR(100) NOT NULL,
    merchant_account_id UUID         NOT NULL,
    payment_id          UUID         NOT NULL,
    payload             JSONB        NOT NULL,
    delivered_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_webhook_deliveries_payment ON webhook_deliveries (payment_id);
