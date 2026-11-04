-- Defence in depth: rules the application already enforces, repeated where a bug, a manual
-- fix or a second writer cannot get around them. See docs/database-design.md.
--
--   Java validation    rejects bad input at the edge with a helpful error
--   domain invariants  keep the model correct while it is being changed
--   these constraints  are the last word: invalid rows cannot be stored at all

-- ---- customers ---------------------------------------------------------------------------
ALTER TABLE customers
    ADD CONSTRAINT ck_customers_email_format CHECK (email LIKE '_%@_%'),
    ADD CONSTRAINT ck_customers_full_name_not_blank CHECK (btrim(full_name) <> '');

-- ---- accounts ----------------------------------------------------------------------------
-- A closed account holds no money, however it got there.
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_closed_is_empty CHECK (status <> 'CLOSED' OR balance = 0),
    ADD CONSTRAINT ck_accounts_version_non_negative CHECK (version >= 0);

-- Target of the composite foreign keys below: "this account, in this currency".
ALTER TABLE accounts
    ADD CONSTRAINT ux_accounts_id_currency UNIQUE (id, currency);

-- ---- ledger ------------------------------------------------------------------------------
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_reference_not_blank CHECK (btrim(reference) <> '');

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_currency_format CHECK (currency ~ '^[A-Z]{3}$');

-- ---- transfers ---------------------------------------------------------------------------
-- A composite foreign key makes "the transfer's currency is both accounts' currency" a plain
-- declarative constraint: no trigger, and it cannot be skipped.
ALTER TABLE transfers
    ADD CONSTRAINT ck_transfers_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT fk_transfers_source_currency FOREIGN KEY (source_account_id, currency)
        REFERENCES accounts (id, currency),
    ADD CONSTRAINT fk_transfers_destination_currency FOREIGN KEY (destination_account_id, currency)
        REFERENCES accounts (id, currency);

-- ---- payments ----------------------------------------------------------------------------
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT ck_payments_updated_after_created CHECK (updated_at >= created_at),
    ADD CONSTRAINT fk_payments_payer_currency FOREIGN KEY (payer_account_id, currency)
        REFERENCES accounts (id, currency),
    ADD CONSTRAINT fk_payments_merchant_currency FOREIGN KEY (merchant_account_id, currency)
        REFERENCES accounts (id, currency),
    -- The refunded amount has to agree with the status.
    ADD CONSTRAINT ck_payments_refund_matches_status CHECK (
        CASE status
            WHEN 'REFUNDED' THEN refunded_amount = amount
            WHEN 'PARTIALLY_REFUNDED' THEN refunded_amount > 0 AND refunded_amount < amount
            ELSE refunded_amount = 0
            END);

-- ---- idempotency -------------------------------------------------------------------------
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_expiry_after_creation CHECK (expires_at > created_at),
    -- A completed key always has a response to replay; one in progress never has.
    ADD CONSTRAINT ck_idempotency_completed_has_response CHECK (
        (status = 'COMPLETED') = (response_code IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL));

-- ---- outbox ------------------------------------------------------------------------------
ALTER TABLE outbox_events
    ADD CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0);

-- ---- risk alerts -------------------------------------------------------------------------
ALTER TABLE risk_alerts
    ADD CONSTRAINT ck_risk_alerts_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT ck_risk_alerts_currency_format CHECK (currency ~ '^[A-Z]{3}$');
