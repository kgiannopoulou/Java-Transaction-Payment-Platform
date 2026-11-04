package com.kgiannopoulou.ledgerforge.risk.domain;

/** Declared from mildest to harshest: the engine takes the highest ordinal any rule returned. */
public enum RiskOutcome {
    ALLOW,
    REVIEW,
    BLOCK
}
