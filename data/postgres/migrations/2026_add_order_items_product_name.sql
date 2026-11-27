-- Migration : ajoute orders.order_items.product_name à une base déjà initialisée.
--
-- Les scripts d'init (data/postgres/*.sql) ne s'exécutent QUE sur un volume PostgreSQL vide.
-- Sur une base existante, appliquer cette migration :
--
--   make migrate-order-items-product-name   (ou `make migrate` ; exécutée aussi par `make up`)
--
-- Équivaut à, sans Make :
--   docker compose exec -T postgres psql -U postgres -d kmarket < data/postgres/migrations/2026_add_order_items_product_name.sql
--
-- But : figer le nom du produit au moment de la commande pour que GET /orders/{id}
-- n'ait plus besoin d'appeler le Catalogue. Les droits de orders_svc couvrent déjà la colonne.
-- Idempotente : peut être rejouée sans erreur.

BEGIN;

ALTER TABLE orders.order_items
    ADD COLUMN IF NOT EXISTS product_name TEXT;

COMMIT;
