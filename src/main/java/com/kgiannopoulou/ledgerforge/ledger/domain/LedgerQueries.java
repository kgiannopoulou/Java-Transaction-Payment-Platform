package com.kgiannopoulou.ledgerforge.ledger.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface LedgerQueries {

    /** Entries of one account, newest first. */
    Page<AccountEntryView> findEntriesByAccount(UUID accountId, Pageable pageable);
}
