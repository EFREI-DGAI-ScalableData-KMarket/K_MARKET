-- Migration : ajoute orders.outbox à une base déjà initialisée (pattern transactional outbox).
--
-- Les scripts d'init (data/postgres/*.sql) ne s'exécutent QUE sur un volume PostgreSQL vide.
-- Sur une base existante, appliquer cette migration :
--
--   make migrate-orders-outbox        (ou `make migrate` ; exécutée aussi par `make up`)
--
-- Équivaut à, sans Make :
--   docker compose exec -T postgres psql -U postgres -d kmarket < data/postgres/migrations/2026_add_orders_outbox.sql
--
-- Idempotente : peut être rejouée sans erreur.

BEGIN;

CREATE TABLE IF NOT EXISTS orders.outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_orders_outbox_pending
    ON orders.outbox (id) WHERE published_at IS NULL;

-- 08_roles.sh a déjà tourné : accorder explicitement les droits au rôle du service.
GRANT SELECT, INSERT, UPDATE ON orders.outbox TO orders_svc;
GRANT USAGE, SELECT ON SEQUENCE orders.outbox_id_seq TO orders_svc;

COMMIT;
