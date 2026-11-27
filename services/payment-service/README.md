# Payment Service (`payment-service`)

Service **Paiement** de K-Market. Encaisse une commande de façon **idempotente**
(étape 3 de la Saga), permet de **relire** le paiement d'une commande et de la
**rembourser** (compensation de la Saga). Toutes les routes sont protégées par un
JWT émis par `auth-service`.

- **Stack** : Java 25, Spring Boot 4, Spring Security (resource server JWT), Spring Data JPA, springdoc OpenAPI
- **Port** : `8003` — toutes les routes sont préfixées par `/v1`
- **Base** : PostgreSQL `kmarket`, schéma `payments`, rôle de connexion `payments_svc`
- **Auth** : JWT HS256 vérifié avec le secret partagé `JWT_SECRET` (claim `roles`)
- **Contrat de référence** : [`contracts/payments.openapi.yaml`](../../contracts/payments.openapi.yaml)

---

## 1. Prérequis

| Outil | Version | Pourquoi |
|---|---|---|
| **JDK** | **25** | Le projet cible `java.version=25` (voir `pom.xml`) |
| **Docker + Docker Compose** | récent | PostgreSQL (runtime) et PostgreSQL jetable des tests (Testcontainers) |
| Maven | *(optionnel)* | Inutile : le wrapper `./mvnw` est fourni dans le repo |

> Pas besoin d'installer Maven : utilise `./mvnw` (Linux/macOS) ou `mvnw.cmd` (Windows).

---

## 2. Démarrer la base de données

```bash
# Depuis la racine du repo : démarre PostgreSQL (+ MongoDB + seed)
make up
```

Le schéma `payments` et la table `payments.payments` sont créés **une seule fois**
par `data/postgres/04_payments.sql` au premier démarrage du conteneur PostgreSQL.
Le rôle `payments_svc` n'a **aucun privilège DDL** : le service n'altère jamais le
schéma (voir §9).

---

## 3. Configuration du service

Le service lit `src/main/resources/application.properties`. Toutes les valeurs ont
un **défaut de développement local** mais peuvent être surchargées par variables
d'environnement.

| Variable | Défaut (dev) | Rôle |
|---|---|---|
| `PAYMENTS_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/kmarket` | URL JDBC de PostgreSQL |
| `PAYMENTS_DB_USER` | `payments_svc` | Rôle de connexion (moindre privilège) |
| `PAYMENTS_DB_PASSWORD` | `payments` | Mot de passe du rôle (= `PAYMENTS_DB_PASSWORD` du `.env`) |
| `PAYMENTS_SERVICE_PORT` | `8003` | Port HTTP |
| `JWT_SECRET` | `kmarket-local-development-jwt-secret-32chars` | Secret HS256 partagé pour valider les JWT |
| `PAYMENTS_LIMIT_CENTS` | `20000` | Plafond métier : un montant **strictement** supérieur est refusé (200 €) |

> ⚠️ Lancé hors Docker, le process **ne lit pas** le `.env` du repo. Soit les défauts
> ci-dessus suffisent, soit exporte la variable
> (ex. `PAYMENTS_DB_PASSWORD=payments ./mvnw spring-boot:run`).

Paramètres fixes (voir `application.properties`) :

- `spring.jpa.hibernate.ddl-auto=none` — Hibernate ne génère aucun DDL.
- `spring.jpa.properties.hibernate.default_schema=payments` — schéma par défaut.
- `server.servlet.context-path=/v1` — préfixe de toutes les routes.

---

## 4. Lancer le service

Depuis **ce dossier** (`services/payment-service/`) :

```bash
# Mode développement (recompile + lance)
./mvnw spring-boot:run

# — ou — construire un jar puis l'exécuter
./mvnw clean package
java -jar target/payment-service-0.0.1-SNAPSHOT.jar

# — ou — via Docker Compose, depuis la racine du repo
docker compose up -d --build payment-service
```

Au démarrage, le service sonde la connexion PostgreSQL et l'écrit dans les logs :
`Database connection OK — PostgreSQL ... as user 'payments_svc' ...`.

Windows : remplace `./mvnw` par `mvnw.cmd`.

---

## 5. Vérifier que ça marche

```bash
# Santé (public, pas de JWT)
curl http://localhost:8003/v1/health
# → {"status":"ok","version":"v1"}
```

- **Swagger UI** : http://localhost:8003/v1/swagger-ui.html
- **Spec OpenAPI (JSON)** : http://localhost:8003/v1/v3/api-docs

Dans Swagger UI, clique sur **Authorize** et colle un `access_token` obtenu auprès
d'`auth-service` pour appeler les routes protégées.

---

## 6. API — exemples de requêtes / réponses

Les exemples ci-dessous sont conformes à `contracts/payments.openapi.yaml`.
`POST`/refund exigent un rôle `service_account` ou `admin` ; `GET` accepte en plus
`analyst` / `logistics`. On suppose un jeton en variable d'environnement :

```bash
TOKEN="<access_token service_account émis par auth-service>"
```

### 6.1 `POST /payments` — encaisser (idempotent)

Requête — un montant ≤ 200 € réussit. L'en-tête `Idempotency-Key` (UUID) est
**obligatoire** :

```bash
curl -i -X POST http://localhost:8003/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: 3f1e9c7a-1b2c-4d5e-8f90-0a1b2c3d4e5f" \
  -H "Content-Type: application/json" \
  -d '{"order_id":1234,"amount_cents":4990,"currency":"EUR"}'
```

Réponse `201 Created` (schéma `Payment`) :

```json
{
  "id": 1,
  "order_id": 1234,
  "amount_cents": 4990,
  "currency": "EUR",
  "status": "succeeded",
  "created_at": "2026-11-26T21:54:07.123456Z"
}
```

> `failure_reason` et `updated_at` sont omis quand ils sont `null` (le schéma ne
> les marque pas comme requis ; la table n'a pas de colonne `updated_at`).

**Rejouer** le même `Idempotency-Key` avec **le même corps** renvoie le paiement
existant (même `id`), **sans** second débit — toujours `201`.

### 6.2 `POST /payments` — refusé (montant > 200 €)

```bash
curl -i -X POST http://localhost:8003/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: 9a8b7c6d-5e4f-4321-9abc-def012345678" \
  -H "Content-Type: application/json" \
  -d '{"order_id":1235,"amount_cents":25000,"currency":"EUR"}'
```

Réponse `422 Unprocessable Content` (schéma `PaymentFailedError`) :

```json
{
  "code": "PAYMENT_FAILED",
  "message": "Payment declined: amount exceeds the 20000 cents limit",
  "request_id": "0f6d2b64-9a1c-4f7e-8b33-2e5a1c9d4b77",
  "reason": "amount_exceeds_limit",
  "amount_cents": 25000,
  "limit_cents": 20000
}
```

La tentative est persistée en `failed` : rejouer la clé **rejoue le même refus**.

### 6.3 `POST /payments` — conflit (`409`)

Réutiliser un `Idempotency-Key` déjà employé avec **un corps différent**
(`order_id`/`amount_cents` différents) renvoie `409 Conflict`.

### 6.4 `GET /payments/{order_id}` — relire le paiement

Utilisé par Orders Service pour **récupérer** le résultat après une réponse perdue.

```bash
curl -i http://localhost:8003/v1/payments/1234 \
  -H "Authorization: Bearer $TOKEN"
```

Réponse `200 OK` (schéma `Payment`) — même corps qu'en 6.1. `404` si la commande
n'a pas de paiement.

### 6.5 `POST /payments/{order_id}/refund` — rembourser (compensation Saga)

```bash
curl -i -X POST http://localhost:8003/v1/payments/1234/refund \
  -H "Authorization: Bearer $TOKEN"
```

Réponse `200 OK` (schéma `Payment`) avec `status: "refunded"`. `404` si inconnu,
`409` si le paiement n'est pas remboursable (déjà remboursé, ou jamais `succeeded`).

### 6.6 Erreurs d'authentification

| Cas | Statut | Corps (schéma `Error`) |
|---|---|---|
| Jeton absent/invalide/expiré | `401` | `{"code":"UNAUTHORIZED", "message":"…", "request_id":"…"}` |
| Rôle insuffisant | `403` | `{"code":"FORBIDDEN", "message":"…", "request_id":"…"}` |
| Corps invalide / `Idempotency-Key` manquant ou non-UUID | `400` | `{"code":"BAD_REQUEST", "message":"…", "request_id":"…"}` |


---

## 7. Simulation de paiement & idempotence (explication)

### 7.1 Simulation

Il n'y a **aucun fournisseur de paiement réel**. L'issue est **déterministe** et
calculée à partir du seul montant :

- `amount_cents` **≤** `PAYMENTS_LIMIT_CENTS` (20000 = 200 €) → `succeeded` ;
- `amount_cents` **>** la limite → `failed`, avec `failure_reason = amount_over_limit`
  (la valeur documentée dans le schéma SQL), surfacé en `422` business
  (`reason = amount_exceeds_limit` côté contrat), **jamais** en `500`.

Un refus est donc un **résultat métier** attendu, pas une erreur technique.

### 7.2 Idempotence

Orders Service peut réémettre une requête après une panne réseau. L'encaissement
est idempotent sur l'en-tête `Idempotency-Key` (un UUID) :

- **même clé + même requête** → l'issue déjà enregistrée est renvoyée, rien n'est
  rejoué (ni débit ni refus) ;
- **même clé + requête différente** → `409` (conflit de contrat) ;
- **requêtes concurrentes avec la même clé** → la contrainte
  `UNIQUE (idempotency_key)` de PostgreSQL laisse **exactement un** `INSERT`
  gagner ; le perdant attrape la `DataIntegrityViolationException` et rejoue
  l'issue du gagnant (ou renvoie `409`).

La prévention du double-débit est donc **garantie par la base**, pas seulement en
Java. Le couple `(order_id, amount_cents)` stocké sert d'empreinte de la requête
(la table n'a pas de colonne `request_hash`), ce qui permet de détecter une clé
réutilisée pour une autre commande/montant.

### 7.3 Transactions

Issue et information d'idempotence sont **la même ligne** : une seule transaction
locale courte les persiste atomiquement. Aucun appel externe, aucune transaction
distribuée. Un refus est écrit dans sa **propre** transaction (`REQUIRES_NEW`) pour
survivre au `422` renvoyé, de sorte qu'un retry rejoue le même refus.

---

## 8. Tests

```bash
# Tests web (slice MockMvc, sans base) + tests d'intégration (Testcontainers PostgreSQL)
./mvnw test
```

| Classe | Type | Ce qu'elle couvre |
|---|---|---|
| `PaymentControllerTest` | Web (`@WebMvcTest`) | Sécurité `401`/`403`, mapping JSON du contrat, `Idempotency-Key` manquant/non-UUID, codes `201`/`422`/`404` |
| `PaymentCaptureIntegrationTest` | Intégration (Testcontainers) | Capture réussie, refus > 200 € persisté `failed`, idempotence (rejeu / conflit / rejeu d'un refus), lecture + `404`, récupération après réponse perdue |
| `PaymentConcurrencyIntegrationTest` | Intégration (Testcontainers) | 8 requêtes concurrentes, même clé → **une** seule ligne (contrainte `UNIQUE`) |
| `PaymentRollbackIntegrationTest` | Intégration (Testcontainers) | Échec de persistance → rollback, aucune ligne partielle |
| `PaymentServiceApplicationTests` | Contexte | Le contexte Spring démarre contre un PostgreSQL jetable |

> Les tests Testcontainers nécessitent un **Docker fonctionnel**. Le PostgreSQL
> jetable est initialisé avec `src/test/resources/db/payments-schema.sql`
> (miroir de `data/postgres/04_payments.sql`).

Dernière exécution locale : **27 tests, 0 échec, 0 erreur** (`BUILD SUCCESS`).

---

## 9. Hibernate ne modifie pas le schéma

- `spring.jpa.hibernate.ddl-auto=none` : Hibernate n'émet **aucun** `CREATE`/`ALTER`/`DROP`.
- Le rôle `payments_svc` n'a de toute façon **pas** le privilège DDL.
- L'entité `Payment` mappe uniquement les **vraies** colonnes de la table ; les
  champs du contrat absents de la table (`currency`, `updated_at`) sont gérés
  applicativement (`currency` validé/renvoyé `EUR`, `updated_at` omis).

---

## 10. Structure du projet

```
payment-service/
├── pom.xml                 # dépendances Maven (Spring Boot 4, resource-server JWT, springdoc, Testcontainers)
├── mvnw / mvnw.cmd         # Maven Wrapper (pas besoin d'installer Maven)
├── Dockerfile              # build multi-stage (JDK 25 → JRE 25), expose 8003
└── src/main/java/com/kmarket/paymentservice/
    ├── PaymentServiceApplication.java
    ├── config/     # SecurityConfig (resource server JWT + RBAC), OpenApiConfig, JacksonConfig
    ├── controller/ # PaymentController (POST /payments, GET + refund), HealthController
    ├── dto/        # PaymentCreateRequest, PaymentResponse, PaymentFailedError, ErrorResponse, HealthResponse
    ├── domain/     # PaymentStatus (pending/succeeded/failed/refunded)
    ├── entity/     # Payment (JPA → payments.payments)
    ├── repository/ # PaymentRepository (Spring Data JPA)
    ├── service/    # PaymentService (capture idempotente, lecture, remboursement)
    ├── exception/  # ApiException, PaymentDeclinedException, GlobalExceptionHandler (schémas Error/PaymentFailedError)
    └── health/     # DatabaseConnectionTester (sonde au démarrage)
```

---

## 11. Intégration avec Orders Service

Voir le guide dédié : [`INTEGRATION_WITH_ORDERS.md`](./INTEGRATION_WITH_ORDERS.md).

