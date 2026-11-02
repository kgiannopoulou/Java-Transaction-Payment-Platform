/**
 * Audit trail (planned).
 *
 * <p>The ledger itself is already append-only and enforced by database triggers. This module
 * will add an operator/actor audit log fed by the transactional outbox once Kafka is introduced.
 */
package com.kgiannopoulou.ledgerforge.audit;
