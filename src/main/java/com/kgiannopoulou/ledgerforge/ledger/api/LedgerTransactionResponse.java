package com.kgiannopoulou.ledgerforge.ledger.api;

import com.kgiannopoulou.ledgerforge.ledger.domain.EntryDirection;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransaction;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransactionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionResponse(
        UUID id,
        String reference,
        LedgerTransactionType type,
        Instant createdAt,
        List<Entry> entries) {

    public record Entry(UUID id, UUID accountId, EntryDirection direction, String amount, String currency) {
    }

    static LedgerTransactionResponse from(LedgerTransaction transaction) {
        return new LedgerTransactionResponse(transaction.getId(), transaction.getReference(), transaction.getType(),
                transaction.getCreatedAt(),
                transaction.getEntries().stream()
                        .map(e -> new Entry(e.getId(), e.getAccountId(), e.getDirection(),
                                e.getAmount().toPlainString(), e.getAmount().currency().getCurrencyCode()))
                        .toList());
    }
}
