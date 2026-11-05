BEGIN;

CREATE TABLE orders.customers (
    id          SERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT        NOT NULL UNIQUE,   -- donnée personnelle : pseudonymisée dès la zone staging du lake
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partitionnée par mois sur created_at.
CREATE TABLE orders.orders (
    id           SERIAL,
    customer_id  INTEGER     NOT NULL REFERENCES orders.customers (id),
    status       TEXT        NOT NULL DEFAULT 'pending'
                 CHECK (status IN (
                     'pending',          -- commande créée
                     'stock_reserved',   -- stock réservé
                     'paid',             -- paiement accepté
                     'preparing',        -- logistique
                     'shipped',
                     'delivered',
                     'payment_failed',   --  réservation libérée
                     'cancelled'         -- annulée par le client ou un admin
                 )),
    total_cents  INTEGER     NOT NULL CHECK (total_cents >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE orders.order_items (
    id                SERIAL,
    order_id          INTEGER     NOT NULL,
    order_created_at  TIMESTAMPTZ NOT NULL,   -- recopiée pour la FK composite et le partitionnement
    product_id        INTEGER     NOT NULL,   -- lien logique -> MongoDB catalog.product_id
    quantity          INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price_cents  INTEGER     NOT NULL CHECK (unit_price_cents >= 0),  -- prix figé au moment de la commande
    PRIMARY KEY (id, order_created_at),
    FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders.orders (id, created_at) ON DELETE CASCADE
) PARTITION BY RANGE (order_created_at);


CREATE TABLE orders.idempotency_keys (
    idempotency_key   TEXT        PRIMARY KEY,   -- en-tête Idempotency-Key envoyé par le client
    order_id          INTEGER     NOT NULL,
    order_created_at  TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders.orders (id, created_at) ON DELETE CASCADE
);

COMMIT;
