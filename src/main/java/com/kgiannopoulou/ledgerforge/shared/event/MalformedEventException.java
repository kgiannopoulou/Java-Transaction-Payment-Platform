package com.kgiannopoulou.ledgerforge.shared.event;

/**
 * A consumed record cannot be turned into an {@link IntegrationEvent} (unknown type, invalid
 * JSON, missing fields). Retrying cannot fix it, so consumers send it straight to the DLT.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
