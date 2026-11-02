-- Card-style payment from a payer wallet to a merchant wallet.
--   authorize  -> payer funds move to the PAYMENT_HOLDS system account
--   capture    -> held funds move to the merchant
--   cancel     -> held funds return to the payer
--   refund     -> merchant funds return to the payer (partial or full)
CREATE TABLE payments
(
    id                  UUID PRIMARY KEY,
    payer_account_id    UUID           NOT NULL REFERENCES accounts (id),
    merchant_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount              NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    refunded_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(30)    NOT NULL CHECK (status IN ('CREATED', 'AUTHORIZED', 'CAPTURED',
                                                                  'PARTIALLY_REFUNDED', 'REFUNDED', 'CANCELLED')),
    description         VARCHAR(255),
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_payments_distinct_accounts CHECK (payer_account_id <> merchant_account_id),
    CONSTRAINT ck_payments_refund_limit CHECK (refunded_amount <= amount)
);

CREATE INDEX ix_payments_payer ON payments (payer_account_id, created_at DESC);
CREATE INDEX ix_payments_merchant ON payments (merchant_account_id, created_at DESC);
