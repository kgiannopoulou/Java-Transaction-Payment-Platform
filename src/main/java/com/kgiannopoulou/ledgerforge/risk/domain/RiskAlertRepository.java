package com.kgiannopoulou.ledgerforge.risk.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface RiskAlertRepository {

    void save(RiskAlert alert);

    /** Newest first; all alerts if {@code accountId} is null. */
    List<RiskAlert> findRecent(@Nullable UUID accountId, int limit);
}
