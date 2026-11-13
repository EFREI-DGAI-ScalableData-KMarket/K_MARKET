# Décisions d'équipe

Décisions validées en binôme. Chacune est reliée à une exigence du sujet et deviendra un ADR (docs/adr/).

| # | Décision | Exigence du sujet | Où c'est appliqué |
|---|---|---|---|
| D1 | Catégories K-culture : `Album`, `Lightstick`, `Merch`, `Drama`, `Livre`, `Beaute` | MongoDB car « les produits n'ont pas tous les mêmes caractéristiques » | `db/mongo/01_catalog.js`, `seed/seed.py` |
| D2 | Statuts de commande : `pending`, `stock_reserved`, `paid`, `preparing`, `shipped`, `delivered`, `payment_failed`, `cancelled` | Onglet Commandes (payée, en préparation, paiement en échec, livrée) et saga | `db/postgres/02_orders.sql` |
| D3 | Rôles applicatifs : `admin`, `catalog_manager`, `analyst`, `logistics`, `service_account` | Modèle RBAC et matrice des droits du sujet | `db/postgres/05_auth.sql` (création des rôles) ; `seed/seed.py` (comptes de démo) |
| D4 | `orders` et `order_items` partitionnées par mois ; `payments` non partitionnée ; idempotence des commandes dans `orders.idempotency_keys` | Partitionnement « commandes par date » + idempotence | `db/postgres/02_orders.sql`, `04_payments.sql`, `06_partitions.sql` |
| D5 | Livraisons dans `inventory.shipments` (service Stock) | Onglet Logistique : « données de stock et de livraison » | `db/postgres/03_inventory.sql` |
| D6 | Clé d'API par service, échangée contre un JWT court de rôle `service_account` | « compte technique avec sa propre clé d'API » (.docx) + « jeton court » (slides) | `db/postgres/05_auth.sql` (auth.api_keys) |

## Précisions

- **D1** : codes sans accent (`Beaute`, pas `Beauté`) car ils servent aussi d'enum GraphQL, qui n'accepte que `[_A-Za-z0-9]`. Le front affiche « Beauté ».
- **D1** : chaque catégorie a ses attributs obligatoires (`oneOf` dans le validator). Schéma souple, mais contrôlé.
- **D4** : une contrainte `UNIQUE` sur une table partitionnée doit inclure la colonne de partition. D'où une table d'idempotence non partitionnée pour les commandes, et `payments` non partitionnée pour garder `UNIQUE (idempotency_key)`.
- **D4** : `GET /v1/orders/{id}` sans date sonde toutes les partitions (index par partition). Acceptable à notre volume ; à expliquer au jury.
- **Ajout au schéma de référence** : `inventory.reservations` (non dessinée dans le sujet) est nécessaire pour que `/release` sache exactement quoi rendre et pour l'idempotence de `/reserve`.
- **Ajouts auth** : `auth.refresh_tokens` (jeton de renouvellement exigé par le .docx) et `auth.api_keys` (D6).

## Décisions encore ouvertes

| Sujet | Options | Remarque |
|---|---|---|
| Gateway | NGINX + `auth_request` vers auth-api, ou Kong | NGINX open source ne valide pas un JWT seul (`auth_jwt` = NGINX Plus) |
| Langage des services | Tout en TypeScript, ou Stock en Java (Spring Boot) | Critère : chacune doit pouvoir expliquer le code de l'autre |
| Stockage objet du lake | ~~RustFS, fork `pgsty/minio`, `cgr.dev/chainguard/minio`~~ → **`cgr.dev/chainguard/minio`** retenu | MinIO ne publie plus d'image Docker communautaire depuis octobre 2025. Image Chainguard testée (serveur + client `minio-client`), configurée dans `docker-compose.yml`. |
