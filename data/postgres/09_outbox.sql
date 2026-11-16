-- Outbox du service Stock : événements à publier dans Kafka (pattern transactional outbox).
CREATE TABLE IF NOT EXISTS inventory.outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,                 -- clé Kafka (warehouse_id) : même entrepôt -> même partition
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                           -- NULL = pas encore publié
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON inventory.outbox (id) WHERE published_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON inventory.outbox TO inventory_svc;
GRANT USAGE ON SEQUENCE inventory.outbox_id_seq TO inventory_svc;