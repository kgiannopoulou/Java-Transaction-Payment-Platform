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
