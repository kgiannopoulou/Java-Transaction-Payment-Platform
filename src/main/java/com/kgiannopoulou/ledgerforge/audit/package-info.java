/**
 * Audit trail: an append-only record of who did what to which entity, written in the same
 * database transaction as the change itself.
 *
 * <p>Features call {@link com.kgiannopoulou.ledgerforge.audit.application.AuditTrail}; auditors
 * and admins read events through {@code GET /api/v1/audit-events}. The table cannot be updated
 * or deleted from (database triggers), and the repository offers no way to try. See
 * {@code docs/audit-trail.md}.
 */
package com.kgiannopoulou.ledgerforge.audit;
