## Service Stock (inventory)

Publication par **outbox transactionnelle** : chaque modification écrit son événement dans
`inventory.outbox` dans la même transaction ; le relais `stock-outbox-relay` le publie ensuite dans Kafka.
Garantie **au moins une fois** : un événement peut arriver deux fois -> dédupliquer sur `event_id`.

| Topic | Partitions | Clé | Émis par |
|---|---|---|---|
| `inventory.stock-changed` | 4 | `warehouse_id` | `POST /stock`, `POST /stock/reserve`, `POST /stock/release`, `PUT /stock/{id}/warehouses/{wid} `|
| `inventory.shipment-status-changed` | 4 | `warehouse_id` | `POST /shipments`, `PATCH /shipments/{id}/status` |

La clé `warehouse_id` garantit l'ordre des événements d'un même entrepôt (même partition).

### `inventory.stock-changed`

```json
{
  "event_id": "56f23ffe-d358-42c8-997f-9792ae7fea57",
  "event_type": "stock_changed",
  "occurred_at": "2026-11-16T11:33:36.929701+00:00",
  "warehouse_id": 1,
  "product_id": 1,
  "delta": -3,
  "quantity": 318,
  "reason": "reserve",
  "order_id": 1234,
  "warehouse_used_units": 18150,
  "warehouse_capacity_units": 20000
}
```

| Champ | Description |
|---|---|
| `delta` | Variation (négative pour une réservation, positive pour une libération ou une création) |
| `quantity` | Quantité disponible après la modification, pour ce produit dans cet entrepôt |
| `reason` | `initial` (création du stock), `reserve`, `release`, `adjustment` |
| `order_id` | Commande concernée ; `null` pour `initial` |
| `warehouse_used_units`, `warehouse_capacity_units` | Remplissage de l'entrepôt après la modification : taux = used / capacity, alerte au-dessus de 0,90, sans état côté consommateur |

### `inventory.shipment-status-changed`

```json
{
  "event_id": "…",
  "event_type": "shipment_status_changed",
  "occurred_at": "…",
  "warehouse_id": 1,
  "shipment_id": 42,
  "order_id": 1234,
  "status": "shipped",
  "previous_status": "preparing"
}
```

`previous_status` vaut `null` à la création (`status: preparing`).