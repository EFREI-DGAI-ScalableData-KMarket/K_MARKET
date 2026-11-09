# Contrats d'API (contract-first)

Écrits avant le code, versionnés en `/v1`.

- `orders.openapi.yaml`
- `inventory.openapi.yaml`
- `payments.openapi.yaml`
- `auth.openapi.yaml`
- `catalog.graphql`

Chaque contrat déclare la sécurité (`securitySchemes` en OpenAPI, erreurs 401/403 en GraphQL).
