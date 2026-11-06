-- explain.sql — effet d'un index et du partitionnement 
-- Usage : make reseed ORDERS=500000   puis   make explain

\set ON_ERROR_STOP on
\pset pager off

\echo '=== Volume ==='
SELECT count(*) AS commandes, count(DISTINCT customer_id) AS clients,
       (SELECT count(*) FROM pg_inherits WHERE inhparent = 'orders.orders'::regclass) AS partitions
FROM orders.orders;

-- Le client qui a le plus de commandes : un cas réel, pas un id au hasard
SELECT customer_id AS cid FROM orders.orders GROUP BY 1 ORDER BY count(*) DESC LIMIT 1 \gset
\echo
\echo 'Requête testée : historique du client' :cid

-- Lecture préalable pour que les deux mesures partent avec les mêmes données en cache
SELECT count(*) AS lecture_prealable FROM orders.orders WHERE customer_id = :cid;

\echo
\echo '=== 1. SANS index sur customer_id ==='
BEGIN;
DROP INDEX orders.idx_orders_customer_id;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, total_cents, created_at
FROM orders.orders
WHERE customer_id = :cid
ORDER BY created_at DESC;
ROLLBACK;   -- l'index est rétabli

\echo
\echo '=== 2. AVEC index sur customer_id ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id, status, total_cents, created_at
FROM orders.orders
WHERE customer_id = :cid
ORDER BY created_at DESC;

\echo
\echo '=== 3. Partitionnement : commandes du mois en cours ==='
\echo 'Seule la partition du mois est lue (partition pruning), les autres sont ignorées.'
SELECT date_trunc('month', now())::date AS debut,
       (date_trunc('month', now()) + interval '1 month')::date AS fin \gset
EXPLAIN (ANALYZE, COSTS OFF)
SELECT status, count(*)
FROM orders.orders
WHERE created_at >= :'debut' AND created_at < :'fin'
GROUP BY status;
