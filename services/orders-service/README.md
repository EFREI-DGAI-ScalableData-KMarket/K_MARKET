# Orders Service (`orders-service`)

Service **Commandes** de K-Market. Expose l'API REST des commandes (liste, et à
terme création via Saga et suivi de statut). Les lectures sont protégées par un
JWT émis par `auth-service`.

- **Stack** : Java 25, Spring Boot 4, Spring Security (resource server JWT), Spring Data JPA, springdoc OpenAPI
- **Port** : `8001` — toutes les routes sont préfixées par `/v1`
- **Base** : PostgreSQL `kmarket`, schéma `orders`, rôle de connexion `orders_svc`
- **Auth** : JWT HS256 vérifié avec le secret partagé `JWT_SECRET` (claim `roles`)

---

## 1. Prérequis

| Outil | Version | Pourquoi |
|---|---|---|
| **JDK** | **25** | Le projet cible `java.version=25` (voir `pom.xml`) |
| **Docker + Docker Compose** | récent | Pour lancer PostgreSQL (et le seed des données) |
| Maven | *(optionnel)* | Inutile : le wrapper `./mvnw` est fourni dans le repo |

> Pas besoin d'installer Maven : utilise `./mvnw` (Linux/macOS) ou `mvnw.cmd` (Windows).

---

## 2. Démarrer la base de données

```bash
# Depuis la racine du repo : démarre PostgreSQL + MongoDB + le seed
make up
```

Le seed remplit le schéma `orders` (clients, commandes, lignes de commande).
Les commandes sont partitionnées par mois sur `created_at`.

---

## 3. Configuration du service

Le service lit `src/main/resources/application.properties`. Toutes les valeurs ont un
**défaut de développement local** mais peuvent être surchargées par variables
d'environnement :

| Variable | Défaut (dev) | Rôle |
|---|---|---|
| `ORDERS_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/kmarket` | URL JDBC de PostgreSQL |
| `ORDERS_DB_USER` | `orders_svc` | Rôle de connexion |
| `ORDERS_DB_PASSWORD` | `orders` | Mot de passe du rôle (= `ORDERS_DB_PASSWORD` du `.env`) |
| `ORDERS_SERVICE_PORT` | `8001` | Port HTTP |

> ⚠️ Lancé hors Docker, le process **ne lit pas** le `.env` du repo. Soit les défauts
> ci-dessus suffisent, soit exporte la variable (ex. `ORDERS_DB_PASSWORD=orders ./mvnw spring-boot:run`).

---

## 4. Lancer le service

Depuis **ce dossier** (`services/orders-service/`) :

```bash
# Mode développement (recompile + lance)
./mvnw spring-boot:run

# — ou — construire un jar puis l'exécuter
./mvnw clean package
java -jar target/orders-service-0.0.1-SNAPSHOT.jar
```

Au démarrage, le service teste la connexion à PostgreSQL et l'écrit dans les logs :
`Database connection OK — PostgreSQL ... as user 'orders_svc' ...`.

Windows : remplace `./mvnw` par `mvnw.cmd`.

---

## 5. Vérifier que ça marche

```bash
# Santé (public)
curl http://localhost:8001/v1/health
# → {"status":"ok","version":"v1"}

# Documentation interactive (Swagger UI)
open http://localhost:8001/v1/swagger-ui.html
```

- **Swagger UI** : http://localhost:8001/v1/swagger-ui.html
- **Spec OpenAPI (JSON)** : http://localhost:8001/v1/v3/api-docs

---

## 6. Tester `GET /orders` depuis Swagger UI

`GET /orders` est protégé (`bearerAuth`) : il faut un JWT valide.

### a. Obtenir un token depuis `auth-service`

Lance `auth-service` (port `8005`) puis connecte-toi avec un compte de démo —
par exemple `analyst@market.test` (mot de passe = `DEMO_PASSWORD` du `.env`) :

```bash
curl -X POST http://localhost:8005/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"analyst@market.test","password":"<DEMO_PASSWORD>"}'
```

Copie le champ `access_token` de la réponse.

> Astuce : tu peux aussi faire ce login directement depuis le Swagger UI d'auth-service
> (http://localhost:8005/v1/swagger-ui.html).

### b. S'authentifier dans Swagger UI (orders-service)

1. Ouvre http://localhost:8001/v1/swagger-ui.html
2. Clique sur **Authorize** (en haut à droite).
3. Colle le `access_token` (sans le préfixe `Bearer `, Swagger l'ajoute) puis **Authorize**.
4. Déplie **`GET /orders`** → **Try it out** → renseigne éventuellement les filtres
   (`status`, `customer_id`, `limit`, `offset`) → **Execute**.

Tu obtiens une réponse `200` paginée :

```json
{
  "items": [
    { "id": 20044, "customer_id": 667, "status": "paid",
      "total_cents": 990, "created_at": "…", "updated_at": "…" }
  ],
  "total": 20044
}
```

Sans token (ou token invalide/expiré) → `401` avec un corps au schéma `Error`.

### c. — ou — tester en ligne de commande

```bash
TOKEN="<access_token>"

# Liste récente (50 par défaut, tri created_at décroissant)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8001/v1/orders

# Filtres + pagination
curl -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8001/v1/orders?status=paid&customer_id=667&limit=10&offset=0'
```

### Paramètres de `GET /orders`

| Paramètre | Type | Défaut | Notes |
|---|---|---|---|
| `status` | `OrderStatus` | – | `pending`, `stock_reserved`, `paid`, `preparing`, `shipped`, `delivered`, `payment_failed`, `cancelled` |
| `customer_id` | `integer` | – | Filtre par client |
| `limit` | `integer` | `50` | Plafonné à **200** |
| `offset` | `integer` | `0` | Pagination |

---

## 7. Tests

```bash
# Tests web (slice MockMvc, sans base) + test de contexte (Testcontainers PostgreSQL)
./mvnw test
```

- `OrderControllerTest` — exerce la sécurité (`401`/`200`), le mapping JSON,
  le plafonnement de `limit` et la validation des paramètres.
- `OrdersServiceApplicationTests` — démarre le contexte complet contre un PostgreSQL
  jetable (Testcontainers) initialisé avec `src/test/resources/db/orders-schema.sql`.

> Les tests Testcontainers nécessitent un Docker fonctionnel.

---

## 8. Structure du projet

```
orders-service/
├── pom.xml                 # dépendances Maven (Spring Boot 4, resource-server JWT, springdoc…)
├── mvnw / mvnw.cmd         # Maven Wrapper (pas besoin d'installer Maven)
└── src/main/java/com/kmarket/ordersservice/
    ├── OrdersServiceApplication.java
    ├── config/     # SecurityConfig (resource server JWT), OpenApiConfig (Swagger)
    ├── controller/ # OrderController (GET /orders), HealthController
    ├── dto/        # OrderResponse, OrderListResponse, ErrorResponse, HealthResponse
    ├── entity/     # Order, OrderItem, Customer, IdempotencyKey (JPA → schéma orders)
    ├── repository/ # OrderRepository (Spring Data JPA + Specifications)
    ├── service/    # OrderService (filtres status/customer_id, tri, pagination)
    ├── exception/  # ApiException + GlobalExceptionHandler (schéma Error)
    └── health/     # DatabaseConnectionTester (sonde au démarrage)
```

Le contrat OpenAPI de référence est `contracts/orders.openapi.yaml` (à la racine du projet).
