-- Synchronous risk engine. See docs/risk-engine.md.

-- REVIEW parks a payment until a person decides: it cannot be authorized before then.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_status_check CHECK (status IN ('CREATED', 'UNDER_REVIEW', 'AUTHORIZED', 'CAPTURED',
                                                           'PARTIALLY_REFUNDED', 'REFUNDED', 'CANCELLED'));

-- Why each payment attempt was allowed, held or refused. Unlike Redis signals, this is a permanent
-- record: "why was my payment declined?" must still have an answer next year.
CREATE TABLE risk_decisions
(
    id                  UUID PRIMARY KEY,
    payment_id          UUID REFERENCES payments (id),
    payer_account_id    UUID           NOT NULL REFERENCES accounts (id),
    merchant_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount              NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    outcome             VARCHAR(10)    NOT NULL CHECK (outcome IN ('ALLOW', 'REVIEW', 'BLOCK')),
    reasons             JSONB          NOT NULL DEFAULT '[]'::jsonb,
    -- TRUE if Redis was unavailable, so the velocity and failure rules could not run.
    degraded            BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ    NOT NULL,

    -- A blocked attempt never becomes a payment; everything else always does.
    CONSTRAINT ck_risk_decisions_blocked_has_no_payment CHECK ((outcome = 'BLOCK') = (payment_id IS NULL))
);

CREATE INDEX ix_risk_decisions_payer ON risk_decisions (payer_account_id, created_at DESC);
CREATE INDEX ix_risk_decisions_payment ON risk_decisions (payment_id) WHERE payment_id IS NOT NULL;
-- ALLOW is the overwhelming majority and nobody browses it; this serves the review queue.
CREATE INDEX ix_risk_decisions_flagged ON risk_decisions (outcome, created_at DESC) WHERE outcome <> 'ALLOW';
