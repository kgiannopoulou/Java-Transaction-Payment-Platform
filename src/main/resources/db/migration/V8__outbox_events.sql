-- Transactional outbox (solves the dual-write problem).
--
-- A service that changes state inserts the integration event into this table in the SAME
-- transaction as the change. A separate relay later reads unpublished rows, sends them to Kafka
-- and marks them published. "State changed" and "event will be published" therefore commit or
-- roll back together; a crash between the commit and the Kafka send only delays the event.
-- See docs/outbox.md.
CREATE TABLE outbox_events
(
    id              UUID PRIMARY KEY,                       -- = the event's eventId
    sequence_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE, -- publish order
    aggregate_type  VARCHAR(30)  NOT NULL,
    aggregate_id    UUID         NOT NULL,                  -- Kafka key
    event_type      VARCHAR(100) NOT NULL,                  -- Kafka header "eventType"
    topic           VARCHAR(200) NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      TEXT
);

-- The relay's query: oldest due, unpublished rows. Partial, so it stays small however many
-- published rows accumulate before the retention job deletes them.
CREATE INDEX ix_outbox_events_pending ON outbox_events (sequence_number) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX ix_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);
-- Supports the relay's "no earlier unpublished event for this aggregate" check.
CREATE INDEX ix_outbox_events_pending_aggregate ON outbox_events (aggregate_id, sequence_number)
    WHERE published_at IS NULL;
