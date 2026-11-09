#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_DB:?}"
: "${POSTGRES_USER:?}"
: "${ORDERS_DB_PASSWORD:?ORDERS_DB_PASSWORD manquant dans .env}"
: "${INVENTORY_DB_PASSWORD:?INVENTORY_DB_PASSWORD manquant dans .env}"
: "${PAYMENTS_DB_PASSWORD:?PAYMENTS_DB_PASSWORD manquant dans .env}"
: "${AUTH_DB_PASSWORD:?AUTH_DB_PASSWORD manquant dans .env}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v db="$POSTGRES_DB" \
     -v orders_pwd="$ORDERS_DB_PASSWORD" \
     -v inventory_pwd="$INVENTORY_DB_PASSWORD" \
     -v payments_pwd="$PAYMENTS_DB_PASSWORD" \
     -v auth_pwd="$AUTH_DB_PASSWORD" <<'SQL'
BEGIN;

-- Personne ne se connecte par défaut : chaque rôle reçoit CONNECT explicitement.
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE ROLE orders_svc    LOGIN PASSWORD :'orders_pwd';
CREATE ROLE inventory_svc LOGIN PASSWORD :'inventory_pwd';
CREATE ROLE payments_svc  LOGIN PASSWORD :'payments_pwd';
CREATE ROLE auth_svc      LOGIN PASSWORD :'auth_pwd';

-- Chaque rôle ne voit que son schéma. Pas de DELETE : un service archive, il n'efface pas
-- (sauf auth pour révoquer des jetons, et inventory pour le stock d'un produit supprimé).
GRANT CONNECT ON DATABASE :"db" TO orders_svc, inventory_svc, payments_svc, auth_svc;

GRANT USAGE ON SCHEMA orders TO orders_svc;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA orders TO orders_svc;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA orders TO orders_svc;

GRANT USAGE ON SCHEMA inventory TO inventory_svc;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA inventory TO inventory_svc;
GRANT DELETE ON inventory.stock TO inventory_svc;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA inventory TO inventory_svc;

GRANT USAGE ON SCHEMA payments TO payments_svc;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA payments TO payments_svc;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA payments TO payments_svc;

GRANT USAGE ON SCHEMA auth TO auth_svc;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA auth TO auth_svc;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA auth TO auth_svc;

-- La fonction de partitions crée des tables : réservée au propriétaire (postgres / un job d'admin).
REVOKE EXECUTE ON FUNCTION orders.ensure_monthly_partitions(date, date) FROM PUBLIC;

COMMIT;
SQL

echo "Rôles créés : orders_svc, inventory_svc, payments_svc, auth_svc"
