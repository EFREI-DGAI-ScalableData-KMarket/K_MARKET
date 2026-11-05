BEGIN;

CREATE TABLE inventory.warehouses (
    id              SERIAL PRIMARY KEY,
    name            TEXT    NOT NULL UNIQUE,
    capacity_units  INTEGER NOT NULL CHECK (capacity_units > 0)  
);

CREATE TABLE inventory.stock (
    product_id    INTEGER     NOT NULL,   -- lien logique -> MongoDB catalog.product_id
    warehouse_id  INTEGER     NOT NULL REFERENCES inventory.warehouses (id),
    quantity      INTEGER     NOT NULL DEFAULT 0 CHECK (quantity >= 0),   
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, warehouse_id)
);

CREATE TABLE inventory.reservations (
    id            SERIAL PRIMARY KEY,
    order_id      INTEGER     NOT NULL,   -- lien logique -> orders.orders.id
    product_id    INTEGER     NOT NULL,
    warehouse_id  INTEGER     NOT NULL REFERENCES inventory.warehouses (id),
    quantity      INTEGER     NOT NULL CHECK (quantity > 0),
    status        TEXT        NOT NULL DEFAULT 'reserved'
                  CHECK (status IN ('reserved', 'committed', 'released')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, product_id, warehouse_id)
);

CREATE TABLE inventory.shipments (
    id            SERIAL PRIMARY KEY,
    order_id      INTEGER     NOT NULL,   -- lien logique -> orders.orders.id
    warehouse_id  INTEGER     NOT NULL REFERENCES inventory.warehouses (id),
    status        TEXT        NOT NULL DEFAULT 'preparing'
                  CHECK (status IN ('preparing', 'shipped', 'delivered')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    shipped_at    TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ,
    CHECK (shipped_at IS NULL OR shipped_at >= created_at),
    CHECK (delivered_at IS NULL OR (shipped_at IS NOT NULL AND delivered_at >= shipped_at))
);

COMMIT;
