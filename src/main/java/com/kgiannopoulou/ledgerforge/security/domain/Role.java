package com.kgiannopoulou.ledgerforge.security.domain;

/**
 * <pre>
 *   CUSTOMER  own accounts, own transfers, own payments (as payer or merchant)
 *   SUPPORT   inspect customers, accounts, transactions; cannot move money or alter the ledger
 *   AUDITOR   read ledger, reconciliation and audit events; cannot modify anything
 *   ADMIN     administrative operations: onboarding, deposits, freeze/unfreeze/close
 * </pre>
 * Roles are not hierarchical: an ADMIN cannot move a customer's money, and an AUDITOR cannot
 * see customer profiles. Separation of duties is the point.
 */
public enum Role {
    CUSTOMER,
    SUPPORT,
    AUDITOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
