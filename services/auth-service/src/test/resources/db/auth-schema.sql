-- Test-only schema bootstrap for Testcontainers PostgreSQL.
-- Mirrors data/postgres/05_auth.sql but also creates the schema, since the
-- throwaway container starts empty (no docker-compose init scripts).

CREATE SCHEMA IF NOT EXISTS auth;

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

CREATE TABLE auth.refresh_tokens (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER     NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth.api_keys (
    id          SERIAL PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    key_hash    TEXT        NOT NULL UNIQUE,
    role_id     INTEGER     NOT NULL REFERENCES auth.roles (id),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO auth.roles (name, description) VALUES
    ('admin',           'Platform admin'),
    ('catalog_manager', 'Catalog team'),
    ('analyst',         'BI and marketing'),
    ('logistics',       'Logistics team'),
    ('service_account', 'Technical service account');
