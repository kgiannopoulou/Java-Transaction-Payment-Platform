package com.kgiannopoulou.ledgerforge.customer.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain-facing persistence port. The Spring Data implementation lives in
 * {@code customer.infrastructure}; application code depends only on this interface.
 */
public interface CustomerRepository {

    Customer save(Customer customer);

    Optional<Customer> findById(UUID id);

    boolean existsByEmail(String email);
}
