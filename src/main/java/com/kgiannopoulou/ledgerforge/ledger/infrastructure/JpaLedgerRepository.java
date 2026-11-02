package com.kgiannopoulou.ledgerforge.ledger.infrastructure;

import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerRepository;
import com.kgiannopoulou.ledgerforge.ledger.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface JpaLedgerRepository extends JpaRepository<LedgerTransaction, UUID>, LedgerRepository {
}
