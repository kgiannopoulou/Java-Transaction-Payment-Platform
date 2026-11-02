package com.kgiannopoulou.ledgerforge.ledger.infrastructure;

import com.kgiannopoulou.ledgerforge.ledger.domain.AccountEntryView;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerEntry;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerQueries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.UUID;

interface JpaLedgerEntryQueries extends Repository<LedgerEntry, UUID>, LedgerQueries {

    @Override
    @Query(value = """
            select new com.kgiannopoulou.ledgerforge.ledger.domain.AccountEntryView(
                e.id, t.id, t.reference, t.type, e.direction, e.amount, e.currency, e.createdAt)
            from LedgerEntry e join e.transaction t
            where e.accountId = :accountId
            order by e.createdAt desc, e.id desc
            """,
            countQuery = "select count(e) from LedgerEntry e where e.accountId = :accountId")
    Page<AccountEntryView> findEntriesByAccount(UUID accountId, Pageable pageable);
}
