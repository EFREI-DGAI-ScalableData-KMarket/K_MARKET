-- Test-only schema bootstrap for Testcontainers PostgreSQL.
-- Mirrors data/postgres/04_payments.sql but also creates the schema, since the
-- throwaway container starts empty (no docker-compose init scripts).

CREATE SCHEMA IF NOT EXISTS payments;

CREATE TABLE payments.payments (
    id               SERIAL PRIMARY KEY,
    order_id         INTEGER     NOT NULL,          -- lien logique -> orders.orders.id
    idempotency_key  TEXT        NOT NULL UNIQUE,
    amount_cents     INTEGER     NOT NULL CHECK (amount_cents >= 0),
    status           TEXT        NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending', 'succeeded', 'failed', 'refunded')),
    failure_reason   TEXT,                           -- ex. amount_over_limit (> 200 €), card_declined
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status = 'failed' OR failure_reason IS NULL)
);
