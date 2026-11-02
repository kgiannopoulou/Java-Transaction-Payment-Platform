package com.kgiannopoulou.ledgerforge.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/** The stored outcome of one idempotent request, keyed by (scope, client-supplied key). */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @EmbeddedId
    private IdempotencyKey id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    public boolean matches(String hash) {
        return requestHash.equals(hash);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** Re-arms an expired key for a new request (keys are reusable after their TTL). */
    public void restart(String hash, Instant now, Instant newExpiry) {
        this.requestHash = Objects.requireNonNull(hash);
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.responseCode = null;
        this.responseBody = null;
        this.createdAt = now;
        this.completedAt = null;
        this.expiresAt = newExpiry;
    }

    public void complete(int code, String body, Instant now) {
        if (status == IdempotencyStatus.COMPLETED) {
            throw new IllegalStateException("Idempotency key " + id + " is already completed");
        }
        this.status = IdempotencyStatus.COMPLETED;
        this.responseCode = code;
        this.responseBody = body;
        this.completedAt = now;
    }

    public IdempotencyKey getId() {
        return id;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
