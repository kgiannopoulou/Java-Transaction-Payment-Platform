package com.kgiannopoulou.ledgerforge.ledger.api;

import com.kgiannopoulou.ledgerforge.ledger.application.DepositService.DepositResult;

import java.util.UUID;

public record DepositResponse(UUID ledgerTransactionId, UUID accountId, String amount, String currency,
                              String balanceAfter) {

    static DepositResponse from(DepositResult result) {
        var credit = result.transaction().getEntries().stream()
                .filter(entry -> entry.getAccountId().equals(result.account().getId()))
                .findFirst()
                .orElseThrow();
        return new DepositResponse(result.transaction().getId(), result.account().getId(),
                credit.getAmount().toPlainString(), credit.getAmount().currency().getCurrencyCode(),
                result.account().getBalance().toPlainString());
    }
}
