## 1. Préparer les tests

Actualise d’abord les statistiques :

```sql
ANALYZE orders.orders;
ANALYZE orders.order_items;
ANALYZE payments.payments;
ANALYZE inventory.stock;
ANALYZE auth.user_roles;
```

Les statistiques permettent au planificateur d’estimer le nombre de lignes à récupérer et de choisir son plan. [postgresql]

Les identifiants et les dates ci-dessous sont des exemples : prends des valeurs présentes dans ta base. Pour connaître la période disponible :

```sql
SELECT MIN(created_at), MAX(created_at)
FROM orders.orders;

SELECT MIN(created_at), MAX(created_at)
FROM payments.payments;
```

## 2. Les requêtes à tester

### A. Commandes d’un statut sur une période

Index ciblé :

```sql
CREATE INDEX idx_orders_status_created
ON orders.orders (status, created_at);
```

Requête :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_id, status, total_cents, created_at
FROM orders.orders
WHERE status = 'paid'
  AND created_at >= TIMESTAMPTZ '2026-01-01 00:00:00+00'
  AND created_at <  TIMESTAMPTZ '2026-02-01 00:00:00+00'
ORDER BY created_at;
```

L’index commence par le statut, puis trie les dates pour ce statut. Si la table est partitionnée, le filtre de date peut également éliminer des partitions : c’est un bénéfice distinct de celui de l’index. [docs.postgresql](https://docs.postgresql.fr/16/indexes-multicolumn.html)

### B. Articles d’une commande

Index ciblé :

```sql
CREATE INDEX idx_order_items_order
ON orders.order_items (order_id, order_created_at);
```

Pour récupérer une paire réellement présente :

```sql
SELECT order_id, order_created_at
FROM orders.order_items
LIMIT 1;
```

Puis remplace les valeurs :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, product_id, quantity, unit_price_cents
FROM orders.order_items
WHERE order_id = 42
  AND order_created_at =
      TIMESTAMPTZ '2026-01-15 10:00:00+00';
```

Cette recherche utilise les deux colonnes de l’index. Si `order_items` est partitionnée sur `order_created_at`, la date permet aussi de cibler la partition concernée. [docs.postgresql](https://docs.postgresql.fr/16/indexes-multicolumn.html)

Cette requête correspond à ta v2 : dans ta v1, la colonne `order_created_at` n’existe pas.

### C. Ventes d’un produit

Index ciblé :

```sql
CREATE INDEX idx_order_items_product_id
ON orders.order_items (product_id);
```

Requête :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    product_id,
    SUM(quantity) AS quantite_vendue,
    SUM(quantity::bigint * unit_price_cents) AS montant_total_cents
FROM orders.order_items
WHERE product_id = 100
GROUP BY product_id;
```

Le plan te montrera comment PostgreSQL récupère les lignes du produit avant de calculer les sommes. Cherche notamment un `Index Scan` ou un `Bitmap Index Scan`. [postgresql](https://www.postgresql.org/docs/17//sql-explain.html)

### D. Paiements d’une commande

Index ciblé :

```sql
CREATE INDEX idx_payments_order_id
ON payments.payments (order_id);
```

Requête :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, order_id, amount_cents, status, created_at
FROM payments.payments
WHERE order_id = 42;
```

Si les paiements sont partitionnés par date, cette requête ne fournit pas de date : elle peut donc consulter plusieurs partitions, même avec des index locaux sur `order_id`. [postgresql](https://www.postgresql.org/docs/16//ddl-partitioning.html)

### E. Paiements échoués sur une période

Index ciblé :

```sql
CREATE INDEX idx_payments_status_created
ON payments.payments (status, created_at);
```

Requête :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, order_id, amount_cents, status, created_at
FROM payments.payments
WHERE status = 'failed'
  AND created_at >= TIMESTAMPTZ '2026-01-01 00:00:00+00'
  AND created_at <  TIMESTAMPTZ '2026-02-01 00:00:00+00'
ORDER BY created_at;
```

Tu peux remplacer `'failed'` par `'succeeded'`. L’utilité observée dépendra notamment du nombre de paiements correspondant au filtre : l’index n’est pas automatiquement le meilleur choix pour tous les statuts. [postgresql](https://www.postgresql.org/docs/16//sql-explain.html)

### F. Stock d’un entrepôt

Index ciblé :

```sql
CREATE INDEX idx_stock_warehouse_id
ON inventory.stock (warehouse_id);
```

Requête :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT product_id, warehouse_id, quantity
FROM inventory.stock
WHERE warehouse_id = 2;
```

Ton index de clé primaire `(product_id, warehouse_id)` ne remplace pas forcément cet index : les recherches sur la deuxième colonne seule sont généralement moins efficaces que celles portant sur les premières colonnes d’un index B-tree. [docs.postgresql](https://docs.postgresql.fr/16/indexes-multicolumn.html)

### G. Utilisateurs ayant un rôle

Index ciblé :

```sql
CREATE INDEX idx_user_roles_role_id
ON auth.user_roles (role_id);
```

Commence par isoler la recherche dans la table de liaison :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id
FROM auth.user_roles
WHERE role_id = 2;
```

Puis teste la requête applicative avec jointure :

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT u.id, u.email, u.is_active
FROM auth.user_roles AS ur
JOIN auth.users AS u
  ON u.id = ur.user_id
WHERE ur.role_id = 2;
```

Le premier test facilite l’observation de l’accès à `user_roles`. Le second montre le plan de la requête complète, y compris la stratégie de jointure. [postgresql](https://www.postgresql.org/docs/17//sql-explain.html)

## 3. Comment faire un vrai avant/après ?

Exécuter les requêtes ci-dessus avec les index existants montre leur utilisation éventuelle, mais pas le gain par rapport à leur absence.

Sur une base de test, pour chaque index :

1. Exécute la requête avant de créer l’index.
2. Crée l’index.
3. Réexécute exactement la même requête.
4. Compare les plans et les mesures.

Exemple complet pour les paiements par commande :

```sql
-- À faire sur une base de test :
-- suppression de l’index explicite, pas des données.
DROP INDEX IF EXISTS payments.idx_payments_order_id;

ANALYZE payments.payments;

-- AVANT
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, order_id, amount_cents, status, created_at
FROM payments.payments
WHERE order_id = 42;

-- Création de l’index
CREATE INDEX idx_payments_order_id
ON payments.payments (order_id);

-- APRÈS
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, order_id, amount_cents, status, created_at
FROM payments.payments
WHERE order_id = 42;
```

Garde les mêmes données et les mêmes filtres. Répète les mesures plusieurs fois : une exécution peut profiter de données déjà en cache, ce qui influence le temps. Les valeurs `shared hit` indiquent justement des blocs trouvés en cache. [postgresql](https://www.postgresql.org/docs/17//sql-explain.html)

Ne force pas `enable_seqscan = off` pour prouver le gain : tu veux observer le choix normal de PostgreSQL, pas lui imposer un plan.

## 4. Lire les résultats et choisir quoi présenter

| Dans le plan | Signification |
|---|---|
| `Seq Scan` | Parcours séquentiel d’une table ou partition |
| `Index Scan` | Accès aux lignes via un index |
| `Bitmap Index Scan` puis `Bitmap Heap Scan` | Repérage via l’index, puis lecture des blocs concernés |
| `Index Cond` | Conditions exploitées par l’index |
| `Filter` | Conditions vérifiées par un filtre à ce nœud |
| `Execution Time` | Temps réel total d’exécution |
| `Buffers: shared hit/read` | Blocs trouvés en cache ou lus |

Ces éléments permettent de comparer le travail réellement effectué, au-delà du seul temps d’exécution. [postgresql](https://www.postgresql.org/docs/17//sql-explain.html)

Dans tes logs, tu as environ 30 000 lignes de commande, 9 800 paiements, mais seulement 861 lignes de stock et 20 utilisateurs. Je choisirais donc les articles d’un produit ou les paiements d’une commande comme première démonstration.

Pour `auth.user_roles`, un `Seq Scan` peut être parfaitement raisonnable : sur une toute petite table, parcourir les lignes peut coûter moins cher qu’utiliser un index. Un index non utilisé ne signifie donc pas nécessairement une mauvaise configuration. [postgresql](https://www.postgresql.org/docs/16//sql-explain.html)