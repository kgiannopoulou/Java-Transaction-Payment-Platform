-- Double-entry ledger. A ledger transaction groups two or more entries whose debits and
-- credits must cancel out per currency.
CREATE TABLE ledger_transactions
(
    id         UUID PRIMARY KEY,
    reference  VARCHAR(100) NOT NULL,
    type       VARCHAR(30)  NOT NULL CHECK (type IN ('DEPOSIT', 'TRANSFER', 'PAYMENT_AUTHORIZATION',
                                                     'PAYMENT_CAPTURE', 'PAYMENT_CANCELLATION', 'PAYMENT_REFUND')),
    created_at TIMESTAMPTZ  NOT NULL,
    -- A business event can be booked exactly once (e.g. "TRF-<transferId>").
    CONSTRAINT ux_ledger_transactions_reference UNIQUE (reference)
);

CREATE TABLE ledger_entries
(
    id             UUID PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID           NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL
);

CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC, id);

-- ---------------------------------------------------------------------------------------
-- Invariant 1: every ledger transaction balances (sum of debits = sum of credits, per currency).
-- Checked by a DEFERRED constraint trigger, i.e. at COMMIT time, after all entries of the
-- transaction have been inserted. The application validates this too; the database is the
-- last line of defence against a bug that slips past the domain model.
-- ---------------------------------------------------------------------------------------
CREATE FUNCTION ledger_assert_transaction_balanced() RETURNS TRIGGER AS
$$
DECLARE
    offending RECORD;
BEGIN
    SELECT currency,
           SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS net
    INTO offending
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id
    GROUP BY currency
    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced in % (net %)',
            NEW.transaction_id, offending.currency, offending.net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT
    ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION ledger_assert_transaction_balanced();

-- ---------------------------------------------------------------------------------------
-- Invariant 2: an entry's currency matches its account's currency.
-- ---------------------------------------------------------------------------------------
CREATE FUNCTION ledger_assert_entry_currency() RETURNS TRIGGER AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND currency = NEW.currency) THEN
        RAISE EXCEPTION 'ledger entry currency % does not match account %', NEW.currency, NEW.account_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_currency
    BEFORE INSERT
    ON ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION ledger_assert_entry_currency();

-- ---------------------------------------------------------------------------------------
-- Invariant 3: the ledger is append-only. Mistakes are corrected with compensating
-- transactions (refunds, reversals), never by editing history.
-- ---------------------------------------------------------------------------------------
CREATE FUNCTION ledger_reject_mutation() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'ledger tables are append-only (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_transactions
    FOR EACH ROW
EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION ledger_reject_mutation();
