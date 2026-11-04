CREATE TABLE transfers
(
    id                     UUID PRIMARY KEY,
    source_account_id      UUID           NOT NULL REFERENCES accounts (id),
    destination_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount                 NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency               VARCHAR(3)     NOT NULL,
    status                 VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'COMPLETED')),
    ledger_transaction_id  UUID REFERENCES ledger_transactions (id),
    created_at             TIMESTAMPTZ    NOT NULL,
    completed_at           TIMESTAMPTZ,

    CONSTRAINT ck_transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_transfers_completed CHECK (status <> 'COMPLETED' OR (ledger_transaction_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_transfers_source ON transfers (source_account_id, created_at DESC);
CREATE INDEX ix_transfers_destination ON transfers (destination_account_id, created_at DESC);
