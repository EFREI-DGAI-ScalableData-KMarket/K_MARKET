# ADR-008 — Écarts au schéma de données de référence

- Statut : acceptée
- Date : 2026-11-05

## Contexte

Le sujet fournit un schéma de référence (quatre schémas PostgreSQL et la collection `catalog`) et autorise à l'adapter, à condition de le justifier dans un ADR. Or le texte du sujet impose des exigences que ce schéma ne permet pas de satisfaire tel quel : rendre le stock quand le paiement échoue (saga), ne traiter qu'une fois une demande reçue deux fois (idempotence), faire circuler des données de livraison, découper les commandes par date, gérer un jeton de renouvellement et des comptes techniques.

Contrainte que nous nous fixons : rester au plus près de la référence. Chaque ajout doit répondre à une exigence écrite du sujet.

## Options

1. **Schéma de référence strict.** Aucun écart à justifier. Mais l'idempotence de `/reserve`, `/release` et des paiements devient impossible, il n'y a aucune donnée de livraison, et le partitionnement est impossible avec une clé primaire sur `id` seul.
2. **Référence + ajouts minimaux, chacun lié à une exigence.** Les exigences sont satisfaites et garanties par la base elle-même (contraintes `UNIQUE`, `CHECK`). En contrepartie : cinq tables et quelques colonnes de plus à maintenir et à expliquer.

## Décision

**Option 2.** Les tables de référence sont conservées avec toutes leurs colonnes : rien n'est supprimé ni renommé. Pour le stock et les paiements, nous privilégions l'exactitude (le C de CAP) : une erreur y coûte une vente fantôme ou un double débit. Ces garanties sont donc portées par la base, pas seulement par le code.

| Ajout | Exigence du sujet | Limite du schéma de référence | Alternative écartée |
|---|---|---|---|
| `inventory.reservations` | Rendre le stock si le paiement échoue ; ne réserver qu'une fois | `stock` ne contient qu'un total : on ne sait pas quelle commande a réservé quoi. Un `/release` rejoué recrée du stock qui n'existe pas | Colonne `reserved_quantity` (pas de détail par commande) ; quantités fournies par l'appelant (double `/release` possible) |
| `inventory.shipments` | « Données de stock et de livraison » par Kafka (onglet Logistique) | Aucune donnée de livraison ; `orders.status` ne donne ni l'entrepôt ni les dates | Événements Kafka simulés seuls : aucune source de vérité pour vérifier l'ingestion |
| `orders.idempotency_keys` | Idempotence de la création de commande | Sur une table partitionnée, `UNIQUE` doit inclure `created_at` : deux envois ont des dates différentes, le doublon passe | `UNIQUE (idempotency_key, created_at)` : inopérant |
| `payments.idempotency_key`, `payments` non partitionnée | `POST /payments` idempotent (schéma d'architecture du sujet) | Rien n'empêche deux débits pour un même paiement | Partitionner `payments` : perd l'unicité de la clé |
| PK `(id, created_at)`, `order_items.order_created_at` | Découper les grosses tables, « par exemple les commandes par date » | Une PK sur `id` seul est incompatible avec une partition par date | Ne pas partitionner : exigence non couverte |
| `auth.refresh_tokens` | JWT de 15 minutes « plus un jeton de renouvellement » | Absent | Jeton non stocké : impossible de le révoquer |
| `auth.api_keys` | « Compte technique avec sa propre clé d'API » | Absent | Comptes techniques dans `users` avec un mot de passe : humains et services mélangés |
| 8 statuts de commande (`CHECK`) | Statuts de l'onglet Commandes et étapes de la saga | `status` sans liste de valeurs | — |
| `updated_at`, `customers.created_at`, `failure_reason` | Ingestion incrémentale du lake ; alerte « échec de paiement » | Pas de date de modification ni de motif de refus | Extraction complète à chaque fois ; refus sans motif |

## Conséquences

- **Positives** : les exigences tiennent même en cas de bug ou de requêtes simultanées, car ce sont des contraintes de la base. Les tables de référence restent intactes : le schéma du sujet se lit toujours dans le nôtre.
- **Négatives** : cinq tables de plus à documenter (`docs/data-model.md`) et à défendre à l'oral. Une recherche de commande par `id` seul interroge toutes les partitions.
- **Plan B** : si le temps manque, `inventory.shipments` peut être remplacée par des événements Kafka simulés, avec le compromis décrit ci-dessus. `inventory.reservations` est indispensable à la saga et ne peut pas être retirée.