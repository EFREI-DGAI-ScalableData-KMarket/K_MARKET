
CREATE INDEX idx_orders_customer_id     ON orders.orders (customer_id); 
-- SELECT * from orders.orders where customer_id = 10 ; AVANT et APRES 
CREATE INDEX idx_orders_status_created  ON orders.orders (status, created_at);


CREATE INDEX idx_order_items_order_id   ON orders.order_items (order_id);
CREATE INDEX idx_order_items_product_id ON orders.order_items (product_id);


CREATE INDEX idx_payments_order_id        ON payments.payments (order_id);
CREATE INDEX idx_payments_status_created  ON payments.payments (status, created_at);



CREATE INDEX idx_stock_warehouse_id ON inventory.stock (warehouse_id);

CREATE INDEX idx_user_roles_role_id ON auth.user_roles (role_id);