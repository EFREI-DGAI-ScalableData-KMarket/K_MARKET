
BEGIN;

DROP SCHEMA IF EXISTS orders    CASCADE;
DROP SCHEMA IF EXISTS inventory CASCADE;
DROP SCHEMA IF EXISTS payments  CASCADE;
DROP SCHEMA IF EXISTS auth      CASCADE;

CREATE SCHEMA orders;      -- service Commandes
CREATE SCHEMA inventory;   -- service Stock
CREATE SCHEMA payments;    -- service Paiement
CREATE SCHEMA auth;        -- service Auth


-- Schéma orders

CREATE TABLE orders.customers (
    id     SERIAL PRIMARY KEY,
    name   TEXT NOT NULL,
    email  TEXT NOT NULL UNIQUE
);


CREATE TABLE orders.orders (
    id           SERIAL,
    customer_id  INTEGER     NOT NULL REFERENCES orders.customers (id),
    status       TEXT        NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled')),
    total_cents  INTEGER     NOT NULL CHECK (total_cents >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);


CREATE TABLE orders.order_items (
    id                SERIAL,
    order_id          INTEGER     NOT NULL,
    order_created_at  TIMESTAMPTZ NOT NULL,
    product_id        INTEGER     NOT NULL,   -- référence logique -> MongoDB catalog.product_id
    quantity          INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price_cents  INTEGER     NOT NULL CHECK (unit_price_cents >= 0),
    PRIMARY KEY (id, order_created_at),
    FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders.orders (id, created_at) ON DELETE CASCADE
) PARTITION BY RANGE (order_created_at);



-- Schéma payments (partitionnée par mois)

CREATE TABLE payments.payments (
    id            SERIAL,
    order_id      INTEGER     NOT NULL,   -- référence logique -> orders.orders.id (pas de FK)
    amount_cents  INTEGER     NOT NULL CHECK (amount_cents >= 0),
    status        TEXT        NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'succeeded', 'failed', 'refunded')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

DO $$
DECLARE
    m date;
BEGIN
    FOR m IN SELECT generate_series(date '2026-10-01', date '2027-10-01', interval '1 month')::date
    LOOP
        EXECUTE format('CREATE TABLE orders.orders_%s PARTITION OF orders.orders FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        EXECUTE format('CREATE TABLE orders.order_items_%s PARTITION OF orders.order_items FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        EXECUTE format('CREATE TABLE payments.payments_%s PARTITION OF payments.payments FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
    END LOOP;
END $$;

CREATE TABLE orders.orders_default      PARTITION OF orders.orders      DEFAULT;
CREATE TABLE orders.order_items_default PARTITION OF orders.order_items DEFAULT;
CREATE TABLE payments.payments_default  PARTITION OF payments.payments  DEFAULT;


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