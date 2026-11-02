package com.kgiannopoulou.ledgerforge.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** Read model: one ledger entry of an account joined with its transaction header. */
public record AccountEntryView(
        UUID entryId,
        UUID transactionId,
        String reference,
        LedgerTransactionType type,
        EntryDirection direction,
        BigDecimal amount,
        Currency currency,
        Instant createdAt) {
}
