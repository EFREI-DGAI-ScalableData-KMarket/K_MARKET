# Auth Service (`auth-service`)

Service d'authentification de **K-Market**. Il émet des JWT, gère les utilisateurs,
les rôles (RBAC) et les clés d'API des services techniques.

- **Stack** : Java 25, Spring Boot 4, Spring Security, Spring Data JPA, JJWT (HS256), springdoc OpenAPI
- **Port** : `8005` — toutes les routes sont préfixées par `/v1`
- **Base** : PostgreSQL `kmarket`, schéma `auth`, rôle de connexion `auth_svc`
- **Mots de passe** : bcrypt — **Clés d'API** : SHA-256 (seul le hash est stocké)

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
# Démarre PostgreSQL + MongoDB + le seed (tables créées + données générées)
make up          
```

Le seed crée 4 comptes humains de démo (mot de passe = `DEMO_PASSWORD` de ton `.env`) :

| Email | Rôle |
|---|---|
| `admin@market.test` | `admin` |
| `catalog@market.test` | `catalog_manager` |
| `analyst@market.test` | `analyst` |
| `logistics@market.test` | `logistics` |

Et 3 clés d'API de services techniques (rôle `service_account`), dont la valeur en clair
est celle de `ORDERS_API_KEY`, `CATALOG_API_KEY`, `STREAM_CONSUMER_API_KEY`.

---

## 3. Configuration du service

Le service lit `src/main/resources/application.properties`. Toutes les valeurs ont un
**défaut de développement local** mais peuvent être surchargées par variables
d'environnement :

| Variable | Défaut (dev) | Rôle |
|---|---|---|
| `AUTH_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/kmarket` | URL JDBC de PostgreSQL |
| `AUTH_DB_USER` | `auth_svc` | Rôle de connexion |
| `AUTH_DB_PASSWORD` | `admin` | Mot de passe du rôle (= `AUTH_DB_PASSWORD` du `.env`) |
| `AUTH_SERVICE_PORT` | `8005` | Port HTTP |
| `JWT_SECRET` | `kmarket-local-development-jwt-secret-32chars` | Secret de signature HS256 (≥ 32 octets) |

---

## 4. Lancer le service

Depuis **ce dossier** (`services/auth-service/`) :

```bash
# Mode développement (recompile + lance)
./mvnw spring-boot:run

# — ou — construire un jar puis l'exécuter
./mvnw clean package
java -jar target/auth-service-0.0.1-SNAPSHOT.jar
```

Au démarrage, le service teste la connexion à PostgreSQL et l'écrit dans les logs :
`Database connection OK — PostgreSQL ... as user 'auth_svc' ...`.

Windows : remplace `./mvnw` par `mvnw.cmd`.

---

## 5. Vérifier que ça marche

```bash
# Santé (public)
curl http://localhost:8005/v1/health

# Documentation interactive (Swagger UI)
open http://localhost:8005/v1/swagger-ui.html
```

- **Swagger UI** : http://localhost:8005/v1/swagger-ui.html
- **Spec OpenAPI (JSON)** : http://localhost:8005/v1/v3/api-docs

Pour appeler les routes protégées (`/auth/me`, `/admin/**`) depuis Swagger UI :
1. `POST /auth/login` depuis l'UI → copie le champ `access_token` de la réponse.
2. Clique sur le bouton **Authorize** (en haut à droite) et colle le token.
3. Les routes `Bearer` sont maintenant appelables directement depuis l'UI.

---

## 6. Structure du projet

```
auth-service/
├── pom.xml                 # dépendances Maven (Spring Boot 4, JJWT, springdoc…)
├── mvnw / mvnw.cmd         # Maven Wrapper (pas besoin d'installer Maven)
└── src/main/java/com/kmarket/authservice/
    ├── AuthServiceApplication.java
    ├── config/     # SecurityConfig (RBAC), JwtProperties, OpenApiConfig
    ├── entity/     # User, Role, ApiKey, RefreshToken (JPA → schéma auth)
    ├── repository/ # Spring Data JPA
    ├── security/   # JwtService (HS256), JwtAuthenticationFilter, TokenHasher (SHA-256)
    ├── service/    # AuthService (login, refresh, logout, users, roles, api-keys)
    ├── health/     # DatabaseConnectionTester (sonde au démarrage)
    └── web/        # Contrôleurs REST + DTO + gestion d'erreurs
```

Le contrat OpenAPI de référence est `contracts/auth.openapi.yaml` (à la racine du projet).
