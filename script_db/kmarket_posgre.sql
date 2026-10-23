BEGIN;

-- Schémas

CREATE SCHEMA IF NOT EXISTS orders;     -- service Commandes
CREATE SCHEMA IF NOT EXISTS inventory;  -- service Stock
CREATE SCHEMA IF NOT EXISTS payments;   -- service Paiement
CREATE SCHEMA IF NOT EXISTS auth;       -- service Auth

-- Schéma orders

CREATE TABLE orders.customers (
    id     SERIAL PRIMARY KEY,
    name   TEXT NOT NULL,
    email  TEXT NOT NULL UNIQUE
);

CREATE TABLE orders.orders (
    id           SERIAL PRIMARY KEY,
    customer_id  INTEGER     NOT NULL REFERENCES orders.customers (id),
    status       TEXT        NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled')), -- we can change. 
    total_cents  INTEGER     NOT NULL CHECK (total_cents >= 0), --montant total de la commande
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders.order_items (
    id               SERIAL PRIMARY KEY,
    order_id         INTEGER NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    product_id       INTEGER NOT NULL,  -- référence logique -> MongoDB catalog.product_id
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_cents INTEGER NOT NULL CHECK (unit_price_cents >= 0) -- prix unitaire de l'items commandé 
);



-- Schéma payments

CREATE TABLE payments.payments (
    id            SERIAL PRIMARY KEY,
    order_id      INTEGER     NOT NULL,  -- référence logique -> orders.orders.id (pas de FK)
    amount_cents  INTEGER     NOT NULL CHECK (amount_cents >= 0),
    status        TEXT        NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'succeeded', 'failed', 'refunded')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);




-- Schéma inventory

CREATE TABLE inventory.warehouses (
    id              SERIAL PRIMARY KEY,
    name            TEXT    NOT NULL,
    capacity_units  INTEGER NOT NULL CHECK (capacity_units >= 0)
);

CREATE TABLE inventory.stock (
    product_id    INTEGER NOT NULL,  -- référence logique -> MongoDB catalog.product_id
    warehouse_id  INTEGER NOT NULL REFERENCES inventory.warehouses (id),
    quantity      INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    PRIMARY KEY (product_id, warehouse_id)
);



-- Schéma auth

CREATE TABLE auth.users (
    id             SERIAL PRIMARY KEY,
    email          TEXT        NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth.roles (
    id           SERIAL PRIMARY KEY,
    name         TEXT NOT NULL UNIQUE,
    description  TEXT
);

CREATE TABLE auth.user_roles (
    user_id  INTEGER NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    role_id  INTEGER NOT NULL REFERENCES auth.roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);



COMMIT;