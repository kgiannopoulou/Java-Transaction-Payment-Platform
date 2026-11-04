package com.kgiannopoulou.ledgerforge.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface RiskDecisionRepository {

    void save(StoredRiskDecision decision);

    /** Newest first; filters are optional. */
    List<StoredRiskDecision> findRecent(@Nullable RiskOutcome outcome, @Nullable UUID payerAccountId, int limit);
}
