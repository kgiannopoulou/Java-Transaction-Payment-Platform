package com.kgiannopoulou.ledgerforge.transfer.api;

import com.kgiannopoulou.ledgerforge.transfer.domain.Transfer;
import com.kgiannopoulou.ledgerforge.transfer.domain.TransferStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        TransferStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String amount,
        String currency,
        UUID ledgerTransactionId,
        Instant createdAt,
        Instant completedAt) {

    static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getStatus(), transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(), transfer.getAmount().toPlainString(),
                transfer.getAmount().currency().getCurrencyCode(), transfer.getLedgerTransactionId(),
                transfer.getCreatedAt(), transfer.getCompletedAt());
    }
}
