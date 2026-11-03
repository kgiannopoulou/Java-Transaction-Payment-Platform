-- Audit trail: who did what to which entity, when, and under which request (trace id).
--
-- Rows are written in the SAME database transaction as the business change they describe, so
-- an audited action can never commit without its audit event (or vice versa).
--
-- The table is append-only. A correction is a new event; history is never edited. Enforced
-- here (triggers) and in the application (the repository has no update/delete methods and
-- there is no JPA entity that dirty checking could flush). In production the application's
-- database role should additionally be granted only INSERT and SELECT on this table; the
-- triggers also stop the table owner. See docs/audit-trail.md.
CREATE TABLE audit_events
(
    id          UUID PRIMARY KEY,
    actor       VARCHAR(255) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(30)  NOT NULL,
    entity_id   UUID         NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    trace_id    VARCHAR(64),
    metadata    JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_events_actor ON audit_events (actor, occurred_at DESC);
CREATE INDEX ix_audit_events_occurred_at ON audit_events (occurred_at DESC);

CREATE FUNCTION audit_reject_mutation() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE
    ON audit_events
    FOR EACH ROW
EXECUTE FUNCTION audit_reject_mutation();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE
    ON audit_events
    FOR EACH STATEMENT
EXECUTE FUNCTION audit_reject_mutation();
