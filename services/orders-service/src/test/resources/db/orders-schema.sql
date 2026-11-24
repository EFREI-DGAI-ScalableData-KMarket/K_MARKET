-- Test-only schema bootstrap for Testcontainers PostgreSQL.
-- Mirrors data/postgres/02_orders.sql but also creates the schema and DEFAULT
-- partitions, since the throwaway container starts empty (no docker-compose
-- init scripts and no partition-maintenance job).

CREATE SCHEMA IF NOT EXISTS orders;

CREATE TABLE orders.customers (
    id          SERIAL PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partitioned by month on created_at.
CREATE TABLE orders.orders (
    id           SERIAL,
    customer_id  INTEGER     NOT NULL REFERENCES orders.customers (id),
    status       TEXT        NOT NULL DEFAULT 'pending'
                 CHECK (status IN (
                     'pending',
                     'stock_reserved',
                     'paid',
                     'preparing',
                     'shipped',
                     'delivered',
                     'payment_failed',
                     'cancelled'
                 )),
    total_cents  INTEGER     NOT NULL CHECK (total_cents >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE orders.order_items (
    id                SERIAL,
    order_id          INTEGER     NOT NULL,
    order_created_at  TIMESTAMPTZ NOT NULL,
    product_id        INTEGER     NOT NULL,
    product_name      TEXT,
    quantity          INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price_cents  INTEGER     NOT NULL CHECK (unit_price_cents >= 0),
    PRIMARY KEY (id, order_created_at),
    FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders.orders (id, created_at) ON DELETE CASCADE
) PARTITION BY RANGE (order_created_at);

CREATE TABLE orders.idempotency_keys (
    idempotency_key   TEXT        PRIMARY KEY,
    order_id          INTEGER     NOT NULL,
    order_created_at  TIMESTAMPTZ NOT NULL,
    request_hash      TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders.orders (id, created_at) ON DELETE CASCADE
);

-- Transactional outbox: events published to Kafka by the service relay.
CREATE TABLE orders.outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_orders_outbox_pending ON orders.outbox (id) WHERE published_at IS NULL;

-- Catch-all partitions so inserts work without the monthly-partition job.
CREATE TABLE orders.orders_default      PARTITION OF orders.orders      DEFAULT;
CREATE TABLE orders.order_items_default PARTITION OF orders.order_items DEFAULT;
