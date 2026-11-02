package com.kgiannopoulou.ledgerforge.transfer.domain;

/**
 * Transfers are booked synchronously, so a committed transfer is always COMPLETED; PENDING only
 * exists inside the booking transaction. (Asynchronous, cross-system transfers in a later phase
 * will add FAILED and make PENDING observable.)
 */
public enum TransferStatus {
    PENDING,
    COMPLETED
}
