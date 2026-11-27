-- Migration : ajoute orders.customers.user_id à une base déjà initialisée.
--
-- Les scripts d'init (data/postgres/*.sql) ne s'exécutent QUE sur un volume PostgreSQL vide.
-- Sur une base existante, orders.customers peut manquer user_id : le seed
-- (INSERT INTO orders.customers (..., user_id) ...) et le lien client <-> compte auth (D7)
-- échouent alors. Appliquer :
--
--   make migrate-customers-user-id    (ou `make migrate` ; exécutée aussi par `make up`)
--
-- Équivaut à, sans Make :
--   docker compose exec -T postgres psql -U postgres -d kmarket < data/postgres/migrations/2026_add_customers_user_id.sql
--
-- But : relier une commande au compte auth.users.id du client (sans FK : autre service,
-- comme product_id). Colonne UNIQUE (un compte = un client). Les droits table de orders_svc
-- couvrent déjà la nouvelle colonne.
-- Idempotente : peut être rejouée sans erreur.

BEGIN;

ALTER TABLE orders.customers
    ADD COLUMN IF NOT EXISTS user_id INTEGER;

-- PostgreSQL n'a pas d'ADD CONSTRAINT IF NOT EXISTS : on vérifie l'existence d'abord.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'orders.customers'::regclass
          AND conname = 'customers_user_id_key'
    ) THEN
        ALTER TABLE orders.customers
            ADD CONSTRAINT customers_user_id_key UNIQUE (user_id);
    END IF;
END$$;

COMMIT;
