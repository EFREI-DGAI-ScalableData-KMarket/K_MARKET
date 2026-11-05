BEGIN;

-- orders
CREATE INDEX idx_orders_customer_id      ON orders.orders (customer_id);           -- historique d'un client
CREATE INDEX idx_orders_status_created   ON orders.orders (status, created_at);    -- liste filtrée par statut, triée par date
CREATE INDEX idx_order_items_order       ON orders.order_items (order_id, order_created_at);  -- lignes d'une commande (= FK composite)
CREATE INDEX idx_order_items_product_id  ON orders.order_items (product_id);       -- « ce produit a-t-il déjà été commandé ? »

-- payments
CREATE INDEX idx_payments_order_id       ON payments.payments (order_id);
CREATE INDEX idx_payments_status_created ON payments.payments (status, created_at);

-- inventory
-- stock : la PK (product_id, warehouse_id) couvre déjà la recherche par produit.
CREATE INDEX idx_stock_warehouse_id      ON inventory.stock (warehouse_id);        -- taux de remplissage par entrepôt
-- reservations : UNIQUE (order_id, product_id, warehouse_id) couvre la recherche par commande.
CREATE INDEX idx_reservations_open       ON inventory.reservations (created_at)
    WHERE status = 'reserved';                                                    -- réservations en attente (index partiel)
CREATE INDEX idx_shipments_order_id      ON inventory.shipments (order_id);
CREATE INDEX idx_shipments_wh_status     ON inventory.shipments (warehouse_id, status);

-- auth
CREATE INDEX idx_user_roles_role_id      ON auth.user_roles (role_id);
CREATE INDEX idx_refresh_tokens_user_id  ON auth.refresh_tokens (user_id);

COMMIT;
