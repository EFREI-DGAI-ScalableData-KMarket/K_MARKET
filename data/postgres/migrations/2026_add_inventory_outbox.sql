-- Migration : ajoute inventory.outbox à une base déjà initialisée (pattern transactional outbox).
--
-- Les scripts d'init (data/postgres/*.sql, dont 09_outbox.sql) ne s'exécutent QUE sur un volume
-- PostgreSQL vide. Sur une base existante, inventory.outbox peut manquer : POST /stock/reserve
-- (et /release) échoue alors en 500 (`relation "inventory.outbox" does not exist`), ce que le
-- service Commandes interprète en UNCERTAIN (la commande reste `pending`). Appliquer :
--
--   make migrate-inventory-outbox     (ou `make migrate` ; exécutée aussi par `make up`)
--
-- Équivaut à, sans Make :
--   docker compose exec -T postgres psql -U postgres -d kmarket < data/postgres/migrations/2026_add_inventory_outbox.sql
--
-- Idempotente : peut être rejouée sans erreur. Miroir de data/postgres/09_outbox.sql.

BEGIN;

CREATE TABLE IF NOT EXISTS inventory.outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,                 -- clé Kafka (warehouse_id) : même entrepôt -> même partition
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                           -- NULL = pas encore publié
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON inventory.outbox (id) WHERE published_at IS NULL;

-- 08_roles.sh a déjà tourné : accorder explicitement les droits au rôle du service.
GRANT SELECT, INSERT, UPDATE ON inventory.outbox TO inventory_svc;
GRANT USAGE, SELECT ON SEQUENCE inventory.outbox_id_seq TO inventory_svc;

COMMIT;
