BEGIN;

CREATE SCHEMA IF NOT EXISTS orders;     -- service Commandes
CREATE SCHEMA IF NOT EXISTS inventory;  -- service Stock
CREATE SCHEMA IF NOT EXISTS payments;   -- service Paiement
CREATE SCHEMA IF NOT EXISTS auth;       -- service Auth (bonus)

COMMIT;
