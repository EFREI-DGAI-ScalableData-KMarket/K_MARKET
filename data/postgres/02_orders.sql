BEGIN;

CREATE TABLE orders.customers (
    id          SERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT        NOT NULL UNIQUE,   -- donnée personnelle : pseudonymisée dès la zone staging du lake
    user_id  INTEGER UNIQUE,  -- auth.users.id du compte client ; sans FK : autre service, comme product_id
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
                     'cancelled',        -- annulée par le client ou un admin
                     'rejected'          -- stock insuffisant à la réservation (aucun paiement tenté)
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
    product_name      TEXT,                   -- nom figé au moment de la commande (lu du Catalogue), évite un appel à la lecture
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
    request_hash      TEXT,                      -- empreinte de la requête : même clé + requête différente -> 409
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders.orders (id, created_at) ON DELETE CASCADE
);

-- Outbox du service Commandes : événements à publier dans Kafka (pattern transactional outbox).
-- Écrite dans la même transaction que la commande ; le relais orders-service la publie ensuite.
CREATE TABLE IF NOT EXISTS orders.outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,                 -- clé Kafka (order_id) : même commande -> même partition
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                           -- NULL = pas encore publié
);
CREATE INDEX IF NOT EXISTS idx_orders_outbox_pending ON orders.outbox (id) WHERE published_at IS NULL;

COMMIT;
