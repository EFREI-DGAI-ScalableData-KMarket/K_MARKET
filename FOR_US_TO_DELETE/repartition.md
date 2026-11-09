# Répartition du travail — proposition


## Principe

Découpage **par onglet, de bout en bout** : chacune touche une base, des API et Kafka.
La note est individualisée et la grille couvre 8 compétences (C1.1 à C2.4) : chacune doit pouvoir défendre l'ensemble, pas seulement sa partie.

## Langages

- **TypeScript** (Node, Express) : services Commandes, Paiement, Catalogue, Auth.
- **Java** : service Stock (Spring Boot), producteurs et consommateurs Kafka, jobs Spark (streaming et lake). Kafka et Spark ont des API Java de référence.
- Les services communiquent uniquement par leurs **contrats** (OpenAPI, GraphQL), écrits ensemble avant le code. Le langage de chaque service est donc indifférent pour les autres.
- Coût assumé : la vérification du JWT, les métriques et OpenTelemetry existent en deux versions (middleware Node, Spring Security + Micrometer + agent Java).

## Les onglets

L'application a 5 onglets. « Prendre un onglet », c'est construire tout ce qu'il y a derrière l'écran, de la base jusqu'à l'API, et préparer sa démo.

| Onglet | L'écran | Ce qu'il faut construire derrière | Démo attendue par le jury | Volets |
|---|---|---|---|---|
| **1 – Vue d'ensemble** | Chiffres du jour, commandes par heure, produits en tension, alertes en direct | Événements dans Kafka ; chiffres calculés par Spark Streaming par fenêtre de temps ; détection d'une alerte (rupture, fraude ou panier abandonné) ; stock lu via l'API Stock ; une API pour l'écran | Une alerte qui apparaît en direct, puis une reprise sans perte après une panne volontaire | 4, 3, 1 |
| **2 – Commandes** | Commandes récentes et leur statut | Tables `orders` ; API REST pour créer et lire une commande ; appels Catalogue (prix), Stock (réservation), Paiement ; saga (stock rendu si le paiement échoue) ; timeout, retry, circuit breaker | Une commande payée, une commande refusée dont le stock est rendu, le circuit breaker qui s'ouvre quand Stock est arrêté | 1, 3 |
| **3 – Catalogue** | Fiches produits, formulaire de création et de modification | Collection MongoDB (index texte et catégorie) ; API GraphQL du CRUD ; formulaire ; création du stock et archivage d'un produit déjà commandé ; ADR MongoDB | Créer un produit avec le formulaire, le modifier, le retrouver en GraphQL, et la fiche ADR | 1, 3 |
| **4 – Logistique** | Entrepôts et taux de remplissage | Données de stock et de livraison par Kafka ; Data Lake en 4 zones (raw, staging, curated, consumption) ; Delta ou Iceberg avec Spark ; taux de remplissage ; alerte au-dessus de 90 % ; contrôles qualité | Les 4 zones du lake, le catalogue de données et un retour à une version précédente (time-travel) | 2, 4 |
| **5 – Observabilité** | Santé de chaque service (OK ou dégradé) | Métriques Prometheus dans chaque service ; tableau Grafana (temps de réponse, erreurs, débit Kafka) ; traces OpenTelemetry et logs lisibles ; alerte quand un service se dégrade | Un service arrêté volontairement, qui passe en « dégradé » dans Grafana | 3, 4 |

En plus des onglets, l'**authentification** (bonus) : connexion, JWT de 15 minutes, 5 rôles. Démo attendue : un même appel accepté pour un rôle et refusé (403) pour un autre.

## Qui fait quoi

| | Hosniath (TypeScript) | Emmy (Java) |
|---|---|---|
| Onglets | 2 Commandes, 3 Catalogue | 4 Logistique, 1 Vue d'ensemble |
| Services | orders-api (saga, idempotence, résilience), payments-api, catalog-api (GraphQL + formulaire), auth-api (JWT, RBAC) | inventory-api (stock, réservations, livraisons), API du tableau de bord |
| Kafka | Publication des événements de commande | Producteur de clics, consommateurs, topics |
| Spark / lake | Lake : ingestion Kafka → raw, puis staging, curated, consumption ; Delta, time-travel, qualité, catalogue de données, pseudonymisation, taux de remplissage | Streaming : fenêtres, watermark, checkpoint, exactly-once, alertes en direct |
| **Ensemble** | Onglet 5 – Observabilité, gateway, contrats, docker-compose, CI, démo | |

## Couverture de la grille

| Compétence | Hosniath | Emmy |
|---|---|---|
| C1.1 Base relationnelle | orders, payments, auth | inventory |
| C1.2 Base NoSQL | catalog (MongoDB) | — |
| C1.3 Data Lake | — | zones, sécurité, pseudonymisation |
| C1.4 Scalabilité, résilience | saga, circuit breaker, retry | reprise Kafka/Spark après panne |
| C2.1 API | REST + GraphQL, auth | REST Stock et tableau de bord |
| C2.2 Streaming | producteur d'événements | Spark Structured Streaming |
| C2.3 Transformations | pipelines du lake | agrégats en streaming |
| C2.4 Optimisation des pipelines | compaction Delta, qualité, taille des fichiers | partitions Kafka, lag, compaction |

Il ne reste qu’un trou chacune (C1.2 pour toi, C1.3 pour Emmy), que les ADR croisés comblent : tu écris l’ADR MongoDB, elle écrit l’ADR du lake.

## Ordre de travail

1. Fusion de `feat/db-init` (en cours).
2. Volet 1 terminé : EXPLAIN avant/après, sauvegarde et restauration testées, ADR 001 à 003.
3. Contrats ensemble : `orders`, `inventory`, `payments`, `auth` (OpenAPI), `catalog` (GraphQL).
4. Tranche verticale : `POST /v1/orders`, saga Stock/Paiement, événement Kafka, compteur Spark, affichage dans l'onglet 1.
5. Chacune complète ses onglets, puis observabilité et démo ensemble.

## Règles de travail

- Une branche par sujet (`feat/...`), une PR relue par l'autre.
- `.env` jamais commité ; chacune a le sien.
- Tout démarre avec `make up`.

## Encore à décider

| Sujet | Options |
|---|---|
| Date de soutenance | à confirmer, détermine ce qu'on simplifie |
| Gateway | NGINX + `auth_request` vers auth-api, ou Kong |
| Stockage objet du lake | RustFS, `pgsty/minio`, `cgr.dev/chainguard/minio` |