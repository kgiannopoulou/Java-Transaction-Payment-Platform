package com.kgiannopoulou.ledgerforge.payment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(@NotNull Decision decision, @Size(max = 500) String note) {

    public enum Decision {
        APPROVE,
        REJECT
    }
}
