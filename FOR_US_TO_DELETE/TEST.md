Après installation pour tester que tout est ok :

**Démarrage**

```bash
make reset
docker compose logs seed | tail -15
```
→ Le seed doit afficher « Données générées », 300 produits et environ 20 000 commandes. S'il échoue, envoie-moi `docker compose logs postgres mongo seed`.

**PostgreSQL** (`make psql`)

```sql
-- 1. Taux de remplissage → 91,0 / 61,0 / 78,0 / 45,x %
SELECT w.name, round(100.0 * sum(s.quantity) / w.capacity_units, 1) AS pct
FROM inventory.warehouses w JOIN inventory.stock s ON s.warehouse_id = w.id
GROUP BY w.id ORDER BY w.id;

-- 2. Rien dans la partition DEFAULT → 0
SELECT count(*) FROM orders.orders_default;

-- 3. Règle des 200 € → 0 commande payée au-dessus
SELECT count(*) FROM orders.orders
WHERE total_cents > 20000 AND status IN ('paid','preparing','shipped','delivered');

-- 4. Isolation → ERROR: permission denied for schema payments
SET ROLE orders_svc;
SELECT * FROM payments.payments LIMIT 1;
RESET ROLE;
```

**MongoDB** (`make mongo`)

```js
db.catalog.countDocuments()                       // → 300
db.catalog.getIndexes().map(i => i.name)          // → _id_, uq_product_id, txt_name_description, idx_category_status
db.catalog.find({ $text: { $search: "KARMA" } }, { name: 1, _id: 0 })   // → les versions de l'album

// Le validator doit REFUSER un Livre sans ISBN → MongoServerError: Document failed validation
db.catalog.insertOne({ product_id: 9999, name: "Test", category: "Livre",
  price_cents: 1000, status: "active", attributes: { langue: "fr" } })
```

**L'utilisateur du service Catalogue**

```bash
docker compose exec mongo mongosh -u catalog_svc -p "$(grep CATALOG_DB_PASSWORD .env | cut -d= -f2)" \
  --authenticationDatabase kmarket kmarket --eval 'db.catalog.countDocuments()'
```
→ 300. Le compte fonctionne donc, sans passer par root.

**Le seed ne recommence pas**

```bash
make seed
```
→ « Bases déjà peuplées : rien à faire ».





docker run --rm --network k_market_default \
  -e PG_DSN="postgresql://postgres:4a8a122e0daf8f3670cf3174813dcb44@postgres:5432/kmarket" \
  -e MONGO_URI="mongodb://root:d513aafa85ee1f31313a403bfce535f5@mongo:27017/?authSource=admin" \
  -e MONGO_DB="kmarket" \
  -e DEMO_PASSWORD="63f866d4ce1aa2eefdf5667ad68b4220" \
  -e ORDERS_API_KEY="78766f3d96614adcb8592c9a6fbd9c0f" \
  -e CATALOG_API_KEY="aec79dceffd40f577e2ec30459be4390" \
  -e STREAM_CONSUMER_API_KEY="5a9ab4d53d8cd8d3023503b075cda7a4" \
  k_market-seed --reset --orders 1000

TEST traffic 
make traffic 
et pour tester rate limit 
seq 600 | xargs -P 100 -I{} curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/v1/warehouses | sort | uniq -c
Elle envoie 600 requêtes à la gateway, 100 à la fois, puis compte les codes de retour obtenus. Morceau par morceau :

```bash
seq 600 | xargs -P 100 -I{} curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/v1/warehouses | sort | uniq -c
```

| Morceau | Rôle |
|---|---|
| `seq 600` | Écrit les nombres de 1 à 600, un par ligne. Ils servent juste de compteur : 600 lignes, donc 600 requêtes |
| `xargs -P 100 -I{} …` | Pour chaque ligne reçue, lance la commande qui suit. `-P 100` : jusqu'à **100 en parallèle**. `-I{}` : on ne se sert pas du nombre lui-même |
| `curl -s -o /dev/null -w "%{http_code}\n" URL` | Fait une requête GET. `-s` : pas de barre de progression. `-o /dev/null` : jette le corps de la réponse. `-w "%{http_code}\n"` : affiche seulement le code HTTP (401, 429…) |
| `sort` | Trie les 600 codes, pour regrouper les identiques |
| `uniq -c` | Compte combien de fois chaque code apparaît |

Résultat : une ligne par code, du type `263 401` et `37 429`.
- **401** : la requête a franchi la gateway et atteint le service, qui l'a refusée faute de jeton (la commande n'en envoie pas, exprès).
- **429** : la gateway l'a bloquée avant, parce qu'il y avait trop de requêtes en même temps.

Ça simule un afflux brutal de requêtes, par exemple un client qui s'emballe ou une attaque, pour montrer que la gateway protège les services. C'est la preuve que la limitation d'appels demandée par le sujet fonctionne.