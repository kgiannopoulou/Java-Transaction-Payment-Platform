package com.kgiannopoulou.ledgerforge.customer.domain;

import com.kgiannopoulou.ledgerforge.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {
        // for JPA
    }

    private Customer(UUID id, String email, String fullName, Instant createdAt) {
        super(id);
        this.email = normalizeEmail(email);
        this.fullName = Objects.requireNonNull(fullName).strip();
        this.status = CustomerStatus.ACTIVE;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Customer register(String email, String fullName, Instant now) {
        return new Customer(UUID.randomUUID(), email, fullName, now);
    }

    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
