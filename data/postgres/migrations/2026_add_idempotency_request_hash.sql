-- Migration : ajoute orders.idempotency_keys.request_hash à une base déjà initialisée.
--
-- Les scripts d'init (data/postgres/*.sql) ne s'exécutent QUE sur un volume PostgreSQL vide.
-- Sur une base existante, appliquer cette migration :
--
--   make migrate-idempotency-hash     (ou `make migrate` ; exécutée aussi par `make up`)
--
-- Équivaut à, sans Make :
--   docker compose exec -T postgres psql -U postgres -d kmarket < data/postgres/migrations/2026_add_idempotency_request_hash.sql
--
-- But : rejouer POST /orders avec la MÊME clé mais une requête DIFFÉRENTE renvoie 409.
-- Les droits de orders_svc couvrent déjà la colonne (GRANT ... ON la table existe).
-- Idempotente : peut être rejouée sans erreur.

BEGIN;

ALTER TABLE orders.idempotency_keys
    ADD COLUMN IF NOT EXISTS request_hash TEXT;

COMMIT;
