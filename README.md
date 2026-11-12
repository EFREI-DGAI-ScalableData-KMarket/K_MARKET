# KMarket

Plateforme data d'un e-commerçant K-culture (K-pop, K-drama, K-beauty).
Projet final du Mastère Spécialisé Data & Generative AI Engineering — Bloc 1.

## Démarrer

Prérequis : Docker avec Docker Compose, et `make`.

```bash
cp .env.example .env      # puis remplacer chaque « change-me »
make up                   # bases + scripts d'initialisation + seed
```

Au premier démarrage :

1. PostgreSQL exécute `db/postgres/*` : schémas, tables, partitions, index et rôles par service.
2. MongoDB exécute `db/mongo/*` : collection `catalog`, validator, index et utilisateur `catalog_svc`.
3. Le service `seed` remplit les deux bases. Il ne fait rien si elles contiennent déjà des données.

Les scripts d'initialisation ne s'exécutent que si les volumes sont vides.
Après une modification dans `db/`, lancer `make reset`.

## Commandes

| Commande | Effet |
|---|---|
| `make up` | Démarre tout |
| `make down` | Arrête les conteneurs, garde les données |
| `make reset` | Supprime les volumes et réinitialise tout |
| `make reseed ORDERS=500000` | Regénère les données (500 000 commandes pour la démo EXPLAIN) |
| `make psql` / `make mongo` | Ouvre une console sur la base |
| `make mc-ls` | Liste le contenu du bucket MinIO du Data Lake |
| `make mc-up FILE=data.csv DEST=raw/` | Envoie un fichier de l'hôte vers le lake |
| `make share-minio` | Partage MinIO sur Internet via un tunnel SSH (affiche l'URL ; garder le terminal ouvert) |

Pour qu'une coéquipière lance sa propre stack et compare son Data Lake avec le mien :
voir [docs/connexion-minio-distant.md](docs/connexion-minio-distant.md).

## Comptes de démo

`admin@market.test`, `catalog@market.test`, `analyst@market.test`, `logistics@market.test`.
Mot de passe : la valeur de `DEMO_PASSWORD` dans `.env`.

## Organisation du dépôt

```
db/
  postgres/       scripts d'initialisation PostgreSQL (ordre alphabétique)
    01_schemas.sql      un schéma par service
    02_orders.sql       clients, commandes (partitionnées), lignes, idempotence
    03_inventory.sql    entrepôts, stock, réservations, livraisons
    04_payments.sql     paiements (idempotents)
    05_auth.sql         utilisateurs, rôles, refresh tokens, clés d'API
    06_partitions.sql   partitions mensuelles des commandes
    07_indexes.sql      index
    08_roles.sh         un rôle PostgreSQL par service (mots de passe lus dans .env)
  mongo/
    01_catalog.js       collection catalog, validator par catégorie, index
  reset.sql       suppression manuelle des schémas et rôles (hors init)
seed/             historique réaliste (Python) + vitrine d'albums réels
services/         microservices (un dossier par service)
contracts/        contrats OpenAPI et GraphQL, écrits avant le code
spark/            streaming (Kafka) et transformations du Data Lake
infra/            gateway, Kafka, observabilité
docs/
  decisions.md    décisions d'équipe (D1 à D6)
  adr/            fiches de décision (ADR)
```

## Données

Un seul serveur PostgreSQL, un schéma et un rôle par service : un service ne lit jamais les tables d'un autre.

| Service | Données | Rôle |
|---|---|---|
| Commandes | `orders` : customers, orders, order_items, idempotency_keys | `orders_svc` |
| Stock | `inventory` : warehouses, stock, reservations, shipments | `inventory_svc` |
| Paiement | `payments` : payments | `payments_svc` |
| Auth | `auth` : users, roles, user_roles, refresh_tokens, api_keys | `auth_svc` |
| Catalogue | MongoDB `kmarket.catalog` | `catalog_svc` |

Les liens entre services (`product_id`, `order_id`) ne sont pas des clés étrangères : ils sont vérifiés par le code, via l'API du service concerné. Les prix sont en centimes dans les deux bases.

Vérifier l'isolation :

```sql
SET ROLE orders_svc;
SELECT * FROM payments.payments;   -- ERROR: permission denied for schema payments
```

## Ce que génère le seed

- 300 produits sur 6 catégories, dont une vitrine réelle (Stray Kids, BTS, ATEEZ, CORTIS) ; le reste est fictif.
- 4 entrepôts remplis à 91 %, 61 %, 78 % et 45 % (le premier déclenche l'alerte au-dessus de 90 %).
- 20 000 commandes sur 90 jours par défaut, avec un pic de « comeback » et des rafales de paiements refusés.
- Des statuts cohérents avec l'âge de la commande, et la règle du service Paiement : refus au-dessus de 200 €.
- Paiements, réservations et livraisons cohérents avec chaque statut.
- Les comptes de démo (mots de passe hachés avec bcrypt) et les clés d'API des services (empreinte SHA-256).
