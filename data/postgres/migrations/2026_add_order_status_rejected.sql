-- Migration : ajoute le statut 'rejected' à orders.orders sur une base déjà initialisée.
--
-- 'rejected' = commande refusée à la réservation du stock (stock insuffisant), avant tout
-- paiement. Distinct de 'payment_failed' (paiement tenté puis échoué).
--
--   make migrate-order-status-rejected   (ou `make migrate` ; exécutée aussi par `make up`)
--
-- Équivaut à, sans Make :
--   docker compose exec -T postgres psql -U postgres -d kmarket < data/postgres/migrations/2026_add_order_status_rejected.sql
--
-- Idempotente : recrée la contrainte CHECK avec la liste complète des statuts.

BEGIN;

ALTER TABLE orders.orders DROP CONSTRAINT IF EXISTS orders_status_check;

ALTER TABLE orders.orders ADD CONSTRAINT orders_status_check CHECK (status IN (
    'pending',
    'stock_reserved',
    'paid',
    'preparing',
    'shipped',
    'delivered',
    'payment_failed',
    'cancelled',
    'rejected'
));

COMMIT;
