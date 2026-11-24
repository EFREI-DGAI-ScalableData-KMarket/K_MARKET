-- Résultats du job Spark temps réel (volet 4), lus par l'API de la Vue d'ensemble.
-- Rejouable : make migrate-analytics (ou make migrate). Mot de passe passé par psql : -v analytics_password=…
\set ON_ERROR_STOP on
BEGIN;

DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'analytics_svc') THEN
    CREATE ROLE analytics_svc LOGIN;
  END IF;
END $$;
ALTER ROLE analytics_svc PASSWORD :'analytics_password';
GRANT CONNECT ON DATABASE kmarket TO analytics_svc;
CREATE SCHEMA IF NOT EXISTS analytics;

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

-- Remplissage de chaque entrepôt : dernier état connu (inventory.stock-changed), alerte au-dessus de 90 %.
CREATE TABLE IF NOT EXISTS analytics.warehouse_fill (
    warehouse_id   INTEGER      PRIMARY KEY,
    used_units     INTEGER      NOT NULL,
    capacity_units INTEGER      NOT NULL CHECK (capacity_units > 0),
    fill_rate      NUMERIC(5,4) NOT NULL,          -- de 0 à 1
    near_full      BOOLEAN      NOT NULL,          -- fill_rate > 0,90
    measured_at    TIMESTAMPTZ  NOT NULL,          -- heure de l'événement le plus récent pris en compte
    near_full_since TIMESTAMPTZ,                   -- début de la saturation en cours ; NULL si normal
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Moindre privilège : analytics_svc ne voit que son schéma, sans pouvoir supprimer ni créer de table.
GRANT USAGE ON SCHEMA analytics TO analytics_svc;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA analytics TO analytics_svc;

-- API de la Vue d'ensemble : lecture seule (moindre privilège ; seul le job Spark écrit, avec analytics_svc).
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dashboard_svc') THEN
    CREATE ROLE dashboard_svc LOGIN;
  END IF;
END $$;
ALTER ROLE dashboard_svc PASSWORD :'dashboard_password';
GRANT CONNECT ON DATABASE kmarket TO dashboard_svc;
GRANT USAGE ON SCHEMA analytics TO dashboard_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO dashboard_svc;

COMMIT;