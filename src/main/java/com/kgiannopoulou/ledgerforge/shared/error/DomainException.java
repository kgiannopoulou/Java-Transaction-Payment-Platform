package com.kgiannopoulou.ledgerforge.shared.error;

/**
 * Base type for expected business failures. Each subclass maps to one HTTP status in
 * {@code ApiExceptionHandler}; the {@link #code()} is a stable, machine-readable identifier that
 * clients can branch on (it is returned as the {@code code} member of the problem response).
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException, BusinessRuleViolationException, ConflictException, InvalidRequestException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
