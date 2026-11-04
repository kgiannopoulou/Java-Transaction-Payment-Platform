package com.kgiannopoulou.ledgerforge.shared.ephemeral;

/** The store could not be reached. Callers degrade; see {@link EphemeralStore}. */
public class EphemeralStoreUnavailableException extends RuntimeException {

    public EphemeralStoreUnavailableException(Throwable cause) {
        super("Ephemeral store unavailable", cause);
    }
}
