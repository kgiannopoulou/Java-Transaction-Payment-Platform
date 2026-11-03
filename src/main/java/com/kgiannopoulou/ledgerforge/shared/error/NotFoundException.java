package com.kgiannopoulou.ledgerforge.shared.error;

import java.util.Locale;

/** A referenced resource does not exist (HTTP 404). Type is e.g. {@code ACCOUNT_NOT_FOUND}. */
public non-sealed class NotFoundException extends DomainException {

    private final String resource;

    public NotFoundException(String resource, Object id) {
        super(resource.toUpperCase(Locale.ROOT).replace(' ', '_') + "_NOT_FOUND",
                resource + " " + id + " was not found");
        this.resource = resource;
    }

    @Override
    public String title() {
        return resource + " not found";
    }
}
