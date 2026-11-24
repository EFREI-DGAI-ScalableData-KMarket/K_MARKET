BEGIN 
-- KPI par minute d'événement (orders.events). Une ligne par minute, réécrite à chaque lot (upsert).
CREATE TABLE IF NOT EXISTS analytics.kpi_minute (
    window_start    TIMESTAMPTZ PRIMARY KEY,
    window_end      TIMESTAMPTZ NOT NULL,
    orders_placed   INTEGER     NOT NULL CHECK (orders_placed >= 0),
    orders_paid     INTEGER     NOT NULL CHECK (orders_paid >= 0),
    revenue_cents   BIGINT      NOT NULL CHECK (revenue_cents >= 0),
    payments_failed INTEGER     NOT NULL CHECK (payments_failed >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Demande nette par produit et par minute (inventory.stock-changed : réservations − libérations).
CREATE TABLE IF NOT EXISTS analytics.demand_minute (
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    product_id   INTEGER     NOT NULL,
    net_units    INTEGER     NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (window_start, product_id)
);

CREATE TABLE IF NOT EXISTS analytics.stock_alerts (
    product_id       INTEGER       PRIMARY KEY,
    remaining_units  INTEGER       NOT NULL,
    units_per_minute NUMERIC(10,2) NOT NULL,
    minutes_left     NUMERIC(10,1) NOT NULL,
    active           BOOLEAN       NOT NULL,
    raised_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),   -- début de l'alerte en cours
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_stock_alerts_active ON analytics.stock_alerts (minutes_left) WHERE active;
COMMIT;