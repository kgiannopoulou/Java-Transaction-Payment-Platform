package com.kgiannopoulou.ledgerforge.account.api;

import com.kgiannopoulou.ledgerforge.account.domain.Account;
import com.kgiannopoulou.ledgerforge.account.domain.AccountStatus;
import com.kgiannopoulou.ledgerforge.account.domain.AccountType;

import java.time.Instant;
import java.util.UUID;

/** Amounts are serialised as strings ("25.00") so no client parses money into a float. */
public record AccountResponse(
        UUID id,
        UUID customerId,
        AccountType type,
        String currency,
        AccountStatus status,
        String balance,
        long version,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getCustomerId(), account.getType(),
                account.getCurrency().getCurrencyCode(), account.getStatus(),
                account.getBalance().toPlainString(), account.getVersion(), account.getCreatedAt());
    }
}
