-- An account is anything that can hold ledger entries: customer wallets and the platform's
-- own system accounts (external funding source, payment authorization holds).
--
-- `balance` is a denormalized running balance that is updated in the SAME database
-- transaction as the ledger entries. The ledger stays the source of truth; see
-- docs/ledger-invariants.md for the reconciliation query that proves they agree.
CREATE TABLE accounts
(
    id          UUID PRIMARY KEY,
    customer_id UUID REFERENCES customers (id),
    type        VARCHAR(30)    NOT NULL CHECK (type IN ('CUSTOMER_WALLET', 'EXTERNAL_FUNDING', 'PAYMENT_HOLDS')),
    currency    VARCHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status      VARCHAR(20)    NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL,

    -- Only the external funding account represents money outside the platform and may go negative.
    CONSTRAINT ck_accounts_non_negative CHECK (balance >= 0 OR type = 'EXTERNAL_FUNDING'),
    -- Wallets belong to a customer; system accounts never do.
    CONSTRAINT ck_accounts_owner CHECK ((type = 'CUSTOMER_WALLET') = (customer_id IS NOT NULL))
);

-- One wallet per customer per currency.
CREATE UNIQUE INDEX ux_accounts_customer_currency ON accounts (customer_id, currency) WHERE type = 'CUSTOMER_WALLET';
-- One system account of each type per currency.
CREATE UNIQUE INDEX ux_accounts_system_type_currency ON accounts (type, currency) WHERE type <> 'CUSTOMER_WALLET';

-- Platform-owned system accounts, one per supported currency.
--
--  EXTERNAL_FUNDING  money that entered from outside (card top-ups, bank transfers). Deposits
--                    debit this account and credit a wallet, so it carries a negative balance
--                    equal to all money ever deposited.
--  PAYMENT_HOLDS     funds reserved by authorized-but-not-yet-captured payments.
--
-- With these accounts in place, SUM(balance) over all accounts of a currency is always 0.
INSERT INTO accounts (id, customer_id, type, currency, status, balance, version, created_at)
VALUES ('00000000-0000-0000-0000-00000000e001', NULL, 'EXTERNAL_FUNDING', 'EUR', 'ACTIVE', 0, 0, now()),
       ('00000000-0000-0000-0000-00000000e002', NULL, 'EXTERNAL_FUNDING', 'USD', 'ACTIVE', 0, 0, now()),
       ('00000000-0000-0000-0000-00000000e003', NULL, 'EXTERNAL_FUNDING', 'GBP', 'ACTIVE', 0, 0, now()),
       ('00000000-0000-0000-0000-00000000a001', NULL, 'PAYMENT_HOLDS', 'EUR', 'ACTIVE', 0, 0, now()),
       ('00000000-0000-0000-0000-00000000a002', NULL, 'PAYMENT_HOLDS', 'USD', 'ACTIVE', 0, 0, now()),
       ('00000000-0000-0000-0000-00000000a003', NULL, 'PAYMENT_HOLDS', 'GBP', 'ACTIVE', 0, 0, now());
