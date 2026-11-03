package com.kgiannopoulou.ledgerforge.shared.error;

/**
 * Syntactically valid but semantically malformed input, e.g. bad monetary precision (HTTP 400).
 */
public non-sealed class InvalidRequestException extends DomainException {

    public InvalidRequestException(String type, String message) {
        super(type, message);
    }

    @Override
    public String title() {
        return "Invalid request";
    }
}
