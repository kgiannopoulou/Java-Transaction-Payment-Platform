package com.kgiannopoulou.ledgerforge.customer.application;

import java.util.Map;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditedEntity;
import com.kgiannopoulou.ledgerforge.audit.domain.AuditAction;
import com.kgiannopoulou.ledgerforge.audit.application.AuditTrail;
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
    private final AuditTrail audit;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, AuditTrail audit, Clock clock) {
        this.customers = customers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Customer register(String email, String fullName) {
        // Fast, friendly check. The unique index on lower(email) is what actually guarantees
        // uniqueness under concurrency; a lost race surfaces as a 409 from the exception handler.
        if (customers.existsByEmail(Customer.normalizeEmail(email))) {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "A customer with this email already exists");
        }
        Customer customer = customers.save(Customer.register(email, fullName, clock.instant()));
        // No PII in audit metadata: the customer record itself holds the email.
        audit.record(AuditAction.CUSTOMER_REGISTERED, AuditedEntity.CUSTOMER, customer.getId(), Map.of());
        return customer;
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }
}
