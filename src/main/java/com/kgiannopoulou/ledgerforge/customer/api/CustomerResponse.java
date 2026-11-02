package com.kgiannopoulou.ledgerforge.customer.api;

import com.kgiannopoulou.ledgerforge.customer.domain.Customer;
import com.kgiannopoulou.ledgerforge.customer.domain.CustomerStatus;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(UUID id, String email, String fullName, CustomerStatus status, Instant createdAt) {

    static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getEmail(), customer.getFullName(),
                customer.getStatus(), customer.getCreatedAt());
    }
}
