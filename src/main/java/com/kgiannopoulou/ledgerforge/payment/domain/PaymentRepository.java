package com.kgiannopoulou.ledgerforge.payment.domain;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    /**
     * Row lock on the payment. Every state transition takes it first, so two concurrent
     * "capture" calls are serialised and the second sees CAPTURED and is rejected.
     */
    Optional<Payment> findByIdForUpdate(UUID id);
}
