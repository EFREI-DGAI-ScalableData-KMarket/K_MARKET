
## 1. Le Makefile

Un Makefile, c'est un **répertoire de raccourcis** : `make backup` lance une ou plusieurs commandes à ta place. Chaque bloc a la même forme :

```make
nom: prérequis   ## description affichée par make help
	commande          ← obligatoirement indentée par une TABULATION, pas des espaces
```

Ce qui mérite une explication :

| Élément | Ce qu'il fait |
|---|---|
| `.PHONY: help up …` | Dit à make que ces noms sont des actions, pas des fichiers. Sans ça, un fichier nommé `backup` dans le dossier bloquerait `make backup` (« déjà à jour »). |
| `.env:` + `up: .env` | `.env` est un **prérequis** : avant `up`, make vérifie que le fichier existe. S'il manque, la règle `.env` affiche le message et s'arrête. C'est ce que tu as vu au début. |
| `help:` | Cherche toutes les lignes `nom: … ## texte` du Makefile (`grep`) et les met en forme (`awk`). Ajouter `## …` à une nouvelle commande suffit pour qu'elle apparaisse dans l'aide. |
| `$$` | make utilise `$` pour ses propres variables. `$$` produit un `$` littéral pour le shell. Dans `make mongo`, `$$MONGO_INITDB_ROOT_PASSWORD` est donc lu **dans le conteneur**, pas par make. |
| `ORDERS ?= 20000` | Valeur par défaut, remplaçable en ligne de commande : `make reseed ORDERS=500000`. |
| `docker compose exec -T postgres …` | Lance la commande dans le conteneur `postgres`. `-T` coupe le mode terminal interactif : obligatoire quand on lui envoie un fichier avec `<`. |
| `< db/demo/explain.sql` | Envoie le contenu du fichier à psql, comme si tu le tapais. Le fichier reste sur ton disque, et pas besoin de le copier dans le conteneur. |

## 2. `db/demo/explain.sql`

L'objectif est de prouver au jury qu'un index et le partitionnement accélèrent vraiment les requêtes.

**Choisir un vrai client**
```sql
SELECT customer_id AS cid FROM orders.orders GROUP BY 1 ORDER BY count(*) DESC LIMIT 1 \gset
```
`\gset` est une commande de psql : elle range le résultat dans une variable `:cid`, réutilisée ensuite. On prend le client qui a le plus de commandes, pour mesurer un cas réel plutôt qu'un id au hasard qui n'existerait peut-être pas.

**La lecture préalable**
Le premier passage d'une requête lit le disque, les suivants profitent du cache. On fait donc une lecture à vide d'abord, pour que les deux mesures partent à égalité. Sinon, l'index aurait l'air plus rapide simplement parce qu'il passe en second.

**Mesurer sans index, sans rien casser**
```sql
BEGIN;
DROP INDEX orders.idx_orders_customer_id;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT …;
ROLLBACK;
```
Dans PostgreSQL, même un `DROP INDEX` peut être annulé s'il est dans une transaction. On supprime l'index, on mesure, puis `ROLLBACK` le remet comme avant. Pas besoin de le reconstruire (ce qui prendrait du temps), et aucun risque d'oublier de le recréer.

**Les options de l'EXPLAIN**
- `ANALYZE` : **exécute** vraiment la requête et donne les temps réels, pas une estimation.
- `BUFFERS` : nombre de pages de 8 Ko lues. C'est le chiffre le plus parlant (4 275 contre 359), parce qu'il ne dépend pas de la vitesse de la machine.
- `COSTS OFF` : masque les coûts estimés, pour un affichage plus lisible.

**Le partitionnement**
```sql
WHERE created_at >= :'debut' AND created_at < :'fin'
```
Les dates sont calculées avant la requête et insérées comme des valeurs fixes (`:'debut'` met des guillemets autour). PostgreSQL voit donc dès la préparation du plan quelles partitions correspondent, et n'en garde qu'une. Avec `now()` directement dans la requête, l'élagage aurait lieu plus tard et serait moins visible dans le plan.

## 3. `scripts/backup.sh`

```bash
set -euo pipefail
```
Le « mode strict » du shell, à mettre en tête de tout script :
- `-e` : s'arrêter à la première erreur, au lieu de continuer comme si de rien n'était ;
- `-u` : une variable non définie est une erreur, ce qui attrape les fautes de frappe ;
- `-o pipefail` : dans `a | b`, si `a` échoue, toute la ligne échoue.

Pour une sauvegarde, c'est essentiel : sans ces options, un `pg_dump` en erreur pourrait laisser un fichier vide qu'on croirait valide.

```bash
cd "$(dirname "$0")/.."
```
Se place à la racine du dépôt, d'où que le script soit lancé. `$0` est le chemin du script lui-même.

**Les trois sauvegardes**

| Commande | Ce qu'elle sauvegarde | Pourquoi |
|---|---|---|
| `pg_dump -Fc` | Toute la base `kmarket` : schémas, tables, données, index, fonction des partitions | Le format `-Fc` (custom) est compressé, et `pg_restore` peut ensuite restaurer une seule table si besoin |
| `pg_dumpall --roles-only` | Les rôles `orders_svc`, `inventory_svc`, etc. | Les rôles appartiennent au **serveur**, pas à une base : `pg_dump` ne les inclut pas. Sans eux, une restauration sur un serveur neuf donnerait des services incapables de se connecter |
| `mongodump --archive --gzip` | La base Mongo `kmarket` : documents, index, et options de la collection (dont le validator) | `--archive` produit un seul fichier, au lieu d'un dossier par collection |

La sortie de chaque commande, dans le conteneur, est redirigée (`>`) vers un fichier **sur ton disque**, dans `backups/<date-heure>/`. Une sauvegarde laissée dans le conteneur disparaîtrait avec lui.

`roles.sql` contient les empreintes des mots de passe : c'est pour ça que `backups/` est dans le `.gitignore`.

## 4. `scripts/restore-test.sh`

Le principe : **une sauvegarde qu'on n'a jamais restaurée n'est pas une sauvegarde**. Le script la restaure dans des bases de test, compare avec les originales, puis nettoie.

**Choisir la sauvegarde**
```bash
SRC="${1:-$(ls -d backups/*/ | sort | tail -1)}"
```
`${1:-défaut}` veut dire « le premier argument, ou à défaut la valeur suivante ». Sans argument, on prend la dernière sauvegarde : les noms en date-heure se trient dans l'ordre chronologique.

**Le nettoyage garanti**
```bash
trap cleanup EXIT
```
La fonction `cleanup` (qui supprime `kmarket_restore`) s'exécute **quoi qu'il arrive** à la sortie du script, même s'il s'arrête sur une erreur. Il ne reste jamais de base de test qui traîne.

**La restauration**
- PostgreSQL : on crée une base vierge `kmarket_restore`, puis `pg_restore --exit-on-error` la remplit. L'option `--exit-on-error` fait échouer le test à la première erreur, au lieu de terminer avec une base incomplète.
- MongoDB : `--nsFrom "kmarket.*" --nsTo "kmarket_restore.*"` renomme la base pendant la restauration. Ta vraie base `kmarket` n'est jamais touchée.

**Compter les lignes de toutes les tables**
```sql
SELECT format('SELECT %L, count(*) FROM %s', c.oid::regclass, c.oid::regclass)
FROM pg_class c … WHERE … c.relkind IN ('r','p') AND NOT c.relispartition
\gexec
```
C'est du SQL qui **écrit du SQL** :
- la requête lit le catalogue de PostgreSQL (`pg_class`), qui liste toutes les tables, et fabrique pour chacune une ligne `SELECT 'orders.orders', count(*) FROM orders.orders` ;
- `\gexec` exécute ensuite chacune de ces lignes ;
- les filtres : `relkind IN ('r','p')` garde les tables normales et partitionnées, et `NOT relispartition` exclut les partitions elles-mêmes, sinon chaque commande serait comptée deux fois (une fois dans `orders`, une fois dans `orders_2026_10`).

L'intérêt : une table ajoutée demain sera vérifiée automatiquement, sans modifier le script.

**Côté MongoDB**, le JavaScript compte les documents, les index, et vérifie la présence du validator. Il est passé au conteneur par une variable d'environnement (`-e JS=…`) plutôt que dans la commande, pour éviter un enfer de guillemets imbriqués.

**La comparaison**
```bash
join … <(sort fichier_origine) <(sort fichier_restauré) | awk '…'
```
- `<( … )` transforme la sortie d'une commande en pseudo-fichier.
- `join` aligne les deux listes sur le nom de la table : origine et restaurée côte à côte. Une table présente d'un seul côté apparaît avec `-`, donc comme un écart.
- `awk` affiche OK ou ÉCART ligne par ligne, et retient s'il y a eu au moins un écart.

**Le code de sortie**
```bash
exit "$status"
```
`0` si tout est identique, `1` sinon. C'est ce qui permettra de brancher ce test dans la CI plus tard : un code non nul fait échouer la CI.

