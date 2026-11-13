-- reset.sql — À NE PAS mettre dans docker-entrypoint-initdb.d.
-- Supprime les schémas et les rôles des services. Usage manuel :
--   psql -U postgres -d kmarket -f db/reset.sql

BEGIN;

DROP SCHEMA IF EXISTS orders    CASCADE;
DROP SCHEMA IF EXISTS inventory CASCADE;
DROP SCHEMA IF EXISTS payments  CASCADE;
DROP SCHEMA IF EXISTS auth      CASCADE;

DROP ROLE IF EXISTS orders_svc;
DROP ROLE IF EXISTS inventory_svc;
DROP ROLE IF EXISTS payments_svc;
DROP ROLE IF EXISTS auth_svc;

COMMIT;
