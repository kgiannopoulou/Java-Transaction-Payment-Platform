package com.kgiannopoulou.ledgerforge.customer.application;

import com.kgiannopoulou.ledgerforge.customer.domain.Customer;
import com.kgiannopoulou.ledgerforge.customer.domain.CustomerRepository;
import com.kgiannopoulou.ledgerforge.shared.error.ConflictException;
import com.kgiannopoulou.ledgerforge.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public Customer register(String email, String fullName) {
        // Fast, friendly check. The unique index on lower(email) is what actually guarantees
        // uniqueness under concurrency; a lost race surfaces as a 409 from the exception handler.
        if (customers.existsByEmail(Customer.normalizeEmail(email))) {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "A customer with this email already exists");
        }
        return customers.save(Customer.register(email, fullName, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }
}
