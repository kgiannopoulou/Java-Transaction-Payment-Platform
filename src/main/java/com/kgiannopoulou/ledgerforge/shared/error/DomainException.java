package com.kgiannopoulou.ledgerforge.shared.error;

/**
 * Base type for expected business failures.
 *
 * <p>The hierarchy is sealed into four categories, and each category maps to exactly one HTTP
 * status in {@code ApiExceptionHandler}. Concrete exceptions ({@code InsufficientFundsException},
 * {@code AccountFrozenException}, ...) extend a category and supply:
 * <ul>
 *   <li>{@link #type()} - a stable, machine-readable SCREAMING_SNAKE_CASE identifier that clients
 *       can branch on (returned as the {@code type} member of the error response);</li>
 *   <li>{@link #title()} - a short human-readable summary of the problem type;</li>
 *   <li>the exception message - the occurrence-specific {@code detail}.</li>
 * </ul>
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException, BusinessRuleViolationException, ConflictException, InvalidRequestException {

    private final String type;

    protected DomainException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() {
        return type;
    }

    public abstract String title();

    /** Domain exceptions are expected outcomes, not bugs: skip the (expensive) stack trace. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
