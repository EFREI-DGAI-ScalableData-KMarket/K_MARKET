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

-- admin, catalog_manager, analyst, logistics, service_account 
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

-- Comptes techniques des services : la clé d'API s'échange contre un JWT court
-- de rôle service_account. Clé aléatoire à forte entropie -> une empreinte SHA-256 suffit.
CREATE TABLE auth.api_keys (
    id          SERIAL PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,   -- ex. orders-api
    key_hash    TEXT        NOT NULL UNIQUE,
    role_id     INTEGER     NOT NULL REFERENCES auth.roles (id),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Les 5 rôles du modèle RBAC du sujet .
-- Données de référence : le code des services dépend de ces noms, ils sont donc créés avec
-- la structure, pas par le seed. Les utilisateurs et l'attribution des rôles, eux, se font
-- par l'API (POST /v1/admin/users, réservé à admin) ou par le seed pour les comptes de démo.
INSERT INTO auth.roles (name, description) VALUES
    ('admin',           'Responsable de la plateforme : tout lire et tout écrire, créer des utilisateurs et attribuer des rôles, voir l''observabilité'),
    ('catalog_manager', 'Équipe catalogue : créer et modifier les produits et les prix, lire les stocks'),
    ('analyst',         'Équipe BI et marketing : lire les commandes, le catalogue, les stocks et les chiffres du jour'),
    ('logistics',       'Équipe logistique : lire les commandes, lire et mettre à jour les stocks et les entrepôts'),
    ('service_account', 'Compte technique d''un service : seulement les appels dont le service a besoin');

COMMIT;
