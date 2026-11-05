-- 05_auth.sql — schéma auth (service Auth, bonus)
-- auth.users = l'équipe Market. Aucun lien avec orders.customers (les clients du site).

BEGIN;

CREATE TABLE auth.users (
    id             SERIAL PRIMARY KEY,
    email          TEXT        NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,   -- bcrypt, jamais en clair
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- admin, catalog_manager, analyst, logistics, service_account (décision D3)
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

-- Jetons de renouvellement : le JWT dure 15 min, le refresh token permet d'en obtenir un nouveau.
-- On stocke une empreinte (hash), jamais le jeton lui-même.
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
    name        TEXT        NOT NULL UNIQUE,   -- ex. orders-api
    key_hash    TEXT        NOT NULL UNIQUE,
    role_id     INTEGER     NOT NULL REFERENCES auth.roles (id),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMIT;
