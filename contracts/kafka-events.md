# Événements Kafka — Market

## Topics

| Topic | Producteur | Consommateur | Clé de partition | Rétention |
|---|---|---|---|---|
| `order-events` | Service Commandes + seed | Spark Streaming  | `order_id` | 7 jours |
| `stock-events` | Service Stock + seed | Spark Streaming | `warehouse_id` | 7 jours |
| `payment-events` | Service Paiement + seed | Spark Streaming  | `order_id` | 7 jours |

## Enveloppe commune

Tous les événements partagent la même enveloppe JSON :

```json
{
  "event_id": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "order_created",
  "timestamp": "2026-11-10T14:30:00Z",
  "version": "1.0",
  "data": { }
}
```

- `event_id` : UUID v4, généré par le producteur (idempotence).
- `event_type` : nom de l'événement (voir ci-dessous).
- `timestamp` : ISO-8601 UTC (utilisé pour le watermark).
- `version` : `"1.0"` (évolution future).
- `data` : payload spécifique à l'événement.

## Cas A — Rupture de stock proche (`stock-events`)

### `stock_reserved`
Émis quand le service Stock réserve des unités pour une commande.

```json
{
  "event_id": "...",
  "event_type": "stock_reserved",
  "timestamp": "2026-11-10T14:30:00Z",
  "version": "1.0",
  "data": {
    "order_id": 1234,
    "product_id": 101,
    "warehouse_id": 1,
    "quantity": 2,
    "remaining_after": 8
  }
}
```

### `stock_low`
Émis quand le stock restant passe sous le seuil (calculé par Spark ou par le service Stock).

```json
{
  "event_id": "...",
  "event_type": "stock_low",
  "timestamp": "2026-11-10T14:31:00Z",
  "version": "1.0",
  "data": {
    "product_id": 101,
    "warehouse_id": 1,
    "remaining": 8,
    "threshold": 10
  }
}
```

**Règle Spark** : fenêtre de 1 minute, watermark 30 s. Alerte si `remaining < threshold`.

## Cas Échec de paiement (`payment-events`)

### `payment_succeeded`
```json
{
  "event_id": "...",
  "event_type": "payment_succeeded",
  "timestamp": "2026-11-10T14:30:00Z",
  "version": "1.0",
  "data": {
    "order_id": 1234,
    "amount_cents": 4990,
    "currency": "EUR"
  }
}
```

### `payment_failed`
```json
{
  "event_id": "...",
  "event_type": "payment_failed",
  "timestamp": "2026-11-10T14:31:00Z",
  "version": "1.0",
  "data": {
    "order_id": 1234,
    "amount_cents": 25000,
    "reason": "amount_exceeds_limit"
  }
}
```

**Règle Spark** : fenêtre glissante de 5 minutes. Alerte si `failed / total > 0.10` (10%).

## Cas Pic de commandes (`order-events`)

### `order_created`
```json
{
  "event_id": "...",
  "event_type": "order_created",
  "timestamp": "2026-11-10T14:30:00Z",
  "version": "1.0",
  "data": {
    "order_id": 1234,
    "customer_id": 42,
    "total_cents": 4990,
    "items_count": 2
  }
}
```

**Règle Spark** : fenêtre de 1 minute. Alerte si `count > 50` commandes/min (seuil à adapter).

## Garanties

| Garantie | Comment |
|---|---|
| **Exactly-once** | Producteur idempotent (`enable.idempotence=true`), clé `event_id` ; Spark avec checkpoint. |
| **Ordre par clé** | Garanti par partition (même `order_id` → même partition). |
| **Watermark** | 30 s (stock, orders), 5 min (payments). |
| **Checkpoint** | `/checkpoints/{topic}` dans MinIO. |
| **Rétention** | 7 jours . |
