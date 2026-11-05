# Microservices

Un dossier par service. Chaque service se connecte avec son propre rôle et ne lit que ses données.

| Dossier | Port | Données | Rôle de connexion |
|---|---|---|---|
| `orders-api/` | 8001 | PostgreSQL, schéma `orders` | `orders_svc` |
| `inventory-api/` | 8002 | PostgreSQL, schéma `inventory` | `inventory_svc` |
| `payments-api/` | 8003 | PostgreSQL, schéma `payments` | `payments_svc` |
| `catalog-api/` | 8004 | MongoDB, collection `catalog` | `catalog_svc` |
| `auth-api/` | 8005 | PostgreSQL, schéma `auth` | `auth_svc` |

Les contrats (OpenAPI, schéma GraphQL) sont écrits d'abord, dans `contracts/`.
