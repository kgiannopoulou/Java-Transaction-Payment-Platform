package com.kgiannopoulou.ledgerforge.shared.error;

/** A referenced resource does not exist (HTTP 404). */
public non-sealed class NotFoundException extends DomainException {

    public NotFoundException(String resource, Object id) {
        super(resource.toLowerCase().replace(' ', '_') + "_not_found", resource + " " + id + " was not found");
    }
}
