package com.kgiannopoulou.ledgerforge.ledger.domain;

import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {

    LedgerTransaction save(LedgerTransaction transaction);

    Optional<LedgerTransaction> findById(UUID id);
}
