import psycopg
from app.core.config import settings
from app.schemas.stock import StockByWarehouseResponse, StockTotalResponse

class StockRepository:
    def __init__(self):
        self.dsn = f"postgresql://{settings.postgres_user}:{settings.postgres_password}@{settings.postgres_host}:{settings.postgres_port}/{settings.postgres_db}"

    def get_stock_by_warehouse(self, product_id: int, warehouse_id: int) -> StockByWarehouseResponse | None:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT product_id, warehouse_id, quantity FROM inventory.stock WHERE product_id = %s AND warehouse_id = %s",
                    (product_id, warehouse_id)
                )
                row = cur.fetchone()
                if row:
                    return StockByWarehouseResponse(product_id=row[0], warehouse_id=row[1], quantity=row[2])
                return None

    def get_total_stock(self, product_id: int) -> StockTotalResponse:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock WHERE product_id = %s",
                    (product_id,)
                )
                total = cur.fetchone()[0]
                return StockTotalResponse(product_id=product_id, total_quantity=total)

    def find_best_warehouse(self, product_id: int, quantity: int) -> int | None:
        """Trouve l'entrepôt qui a le plus de stock pour ce produit."""
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT warehouse_id FROM inventory.stock "
                    "WHERE product_id = %s AND quantity >= %s "
                    "ORDER BY quantity DESC LIMIT 1",
                    (product_id, quantity)
                )
                row = cur.fetchone()
                return row[0] if row else None

    def reserve_stock(self, order_id: int, items: list[dict]) -> list[dict]:
        results = []
        merged_items = {}
        for item in items:
            pid = item["product_id"]
            merged_items[pid] = merged_items.get(pid, 0) + item["quantity"]

        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                for pid, qty in merged_items.items():
                    # pid, qty = item["product_id"], item["quantity"]
                    
                    cur.execute("SELECT id, status, warehouse_id FROM inventory.reservations WHERE order_id=%s AND product_id=%s", (order_id, pid))
                    existing = cur.fetchone()
                    if existing:
                        results.append({"reservation_id": existing[0], "status": existing[1], "warehouse_id": existing[2], "message": "Déjà réservé"})
                        continue
                    
                    cur.execute("SELECT warehouse_id FROM inventory.stock WHERE product_id=%s AND quantity >= %s ORDER BY quantity DESC LIMIT 1", (pid, qty))
                    wh_row = cur.fetchone()
                    if not wh_row:
                        raise ValueError(f"Stock insuffisant pour le produit {pid}")
                    wid = wh_row[0]
                    
                    cur.execute("UPDATE inventory.stock SET quantity = quantity - %s WHERE product_id=%s AND warehouse_id=%s", (qty, pid, wid))
                    cur.execute("INSERT INTO inventory.reservations (order_id, product_id, warehouse_id, quantity, status) VALUES (%s, %s, %s, %s, 'reserved') RETURNING id", (order_id, pid, wid, qty))
                    res_id = cur.fetchone()[0]
                    
                    results.append({"reservation_id": res_id, "status": "reserved", "warehouse_id": wid, "message": "Réservé"})
        return results
            
    def release_stock(self, order_id: int, product_id: int) -> dict:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, warehouse_id, quantity, status FROM inventory.reservations "
                    "WHERE order_id = %s AND product_id = %s FOR UPDATE",
                    (order_id, product_id)
                )
                res = cur.fetchone()
                
                if not res:
                    return {"status": "not_found", "message": "Aucune réservation trouvée"}
                
                res_id, warehouse_id, quantity, status = res
                
                if status == "released":
                    return {"status": "released", "message": "Déjà libéré (idempotent)"}
                

                cur.execute(
                    "UPDATE inventory.stock SET quantity = quantity + %s, updated_at = NOW() "
                    "WHERE product_id = %s AND warehouse_id = %s",
                    (quantity, product_id, warehouse_id)
                )
                
                cur.execute(
                    "UPDATE inventory.reservations SET status = 'released', updated_at = NOW() "
                    "WHERE id = %s",
                    (res_id,)
                )
                
                return {"status": "released", "message": "Stock libéré avec succès"}


    def get_warehouses(self) -> list[dict]:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute("""
                    SELECT w.id, w.name, w.capacity_units, COALESCE(SUM(s.quantity), 0) as used
                    FROM inventory.warehouses w
                    LEFT JOIN inventory.stock s ON w.id = s.warehouse_id
                    GROUP BY w.id, w.name, w.capacity_units
                    ORDER BY w.id
                """)
                rows = cur.fetchall()
                results = []
                for row in rows:
                    wid, name, capacity, used = row
                    fill_rate = used / capacity if capacity > 0 else 0.0
                    status = "near_full" if fill_rate > 0.90 else "normal"
                    results.append({
                        "id": wid, "name": name, "capacity_units": capacity,
                        "used_units": used, "fill_rate": round(fill_rate, 2), "status": status
                    })
                return results

    def get_shipments(self, warehouse_id: int | None = None, status: str | None = None) -> list[dict]:
        query = "SELECT id, order_id, warehouse_id, status, created_at, shipped_at, delivered_at FROM inventory.shipments WHERE 1=1"
        params = []
        if warehouse_id:
            query += " AND warehouse_id = %s"
            params.append(warehouse_id)
        if status:
            query += " AND status = %s"
            params.append(status)
        query += " ORDER BY created_at DESC"

        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(query, tuple(params))
                return [{"id": r[0], "order_id": r[1], "warehouse_id": r[2], "status": r[3], 
                         "created_at": r[4], "shipped_at": r[5], "delivered_at": r[6]} for r in cur.fetchall()]

    def create_shipment(self, order_id: int, warehouse_id: int) -> dict:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                # On vérifie d'abord si une expédition existe déjà pour cette commande
                cur.execute("SELECT id FROM inventory.shipments WHERE order_id = %s", (order_id,))
                if cur.fetchone():
                    raise ValueError("Une expédition existe déjà pour cette commande")

                cur.execute(
                    "INSERT INTO inventory.shipments (order_id, warehouse_id, status) VALUES (%s, %s, 'preparing') RETURNING id, created_at",
                    (order_id, warehouse_id)
                )
                row = cur.fetchone()
                return {"id": row[0], "order_id": order_id, "warehouse_id": warehouse_id, "status": "preparing", "created_at": row[1]}
    
    def update_shipment_status(self, shipment_id: int, status: str) -> bool:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                if status == "shipped":
                    # On ne met la date que si elle n'existe pas déjà
                    cur.execute("UPDATE inventory.shipments SET status=%s, shipped_at=COALESCE(shipped_at, NOW()) WHERE id=%s", (status, shipment_id))
                elif status == "delivered":
                    cur.execute("UPDATE inventory.shipments SET status=%s, delivered_at=COALESCE(delivered_at, NOW()) WHERE id=%s", (status, shipment_id))
                else:
                    cur.execute("UPDATE inventory.shipments SET status=%s WHERE id=%s", (status, shipment_id))
                return cur.rowcount > 0

    def get_stock_detail(self, product_id: int) -> dict:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT warehouse_id, quantity FROM inventory.stock WHERE product_id = %s", (product_id,))
                rows = cur.fetchall()
                total = sum(r[1] for r in rows)
                details = [{"product_id": product_id, "warehouse_id": r[0], "quantity": r[1]} for r in rows]
                return {"product_id": product_id, "total_quantity": total, "by_warehouse": details}

    def create_initial_stock(self, product_id: int, warehouse_id: int, quantity: int) -> dict:
        with psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                # Vérifier si existe déjà
                cur.execute("SELECT id FROM inventory.stock WHERE product_id = %s AND warehouse_id = %s", (product_id, warehouse_id))
                if cur.fetchone():
                    raise ValueError("Stock déjà existant pour ce produit dans cet entrepôt")
                
                cur.execute(
                    "INSERT INTO inventory.stock (product_id, warehouse_id, quantity) VALUES (%s, %s, %s)",
                    (product_id, warehouse_id, quantity)
                )
                return {"product_id": product_id, "warehouse_id": warehouse_id, "quantity": quantity}