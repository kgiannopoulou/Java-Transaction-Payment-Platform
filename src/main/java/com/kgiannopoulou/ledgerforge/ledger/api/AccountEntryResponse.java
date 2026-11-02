package com.kgiannopoulou.ledgerforge.ledger.api;

import com.kgiannopoulou.ledgerforge.ledger.domain.AccountEntryView;
import com.kgiannopoulou.ledgerforge.ledger.domain.EntryDirection;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;
import com.kgiannopoulou.ledgerforge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

public record AccountEntryResponse(
        UUID entryId,
        UUID transactionId,
        String reference,
        LedgerTransactionType type,
        EntryDirection direction,
        String amount,
        String currency,
        Instant createdAt) {

    static AccountEntryResponse from(AccountEntryView view) {
        return new AccountEntryResponse(view.entryId(), view.transactionId(), view.reference(), view.type(),
                view.direction(), Money.of(view.amount(), view.currency()).toPlainString(),
                view.currency().getCurrencyCode(), view.createdAt());
    }
}
