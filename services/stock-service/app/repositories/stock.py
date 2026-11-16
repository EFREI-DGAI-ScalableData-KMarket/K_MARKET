"""Accès PostgreSQL (schéma inventory) du service Stock.

Chaque méthode ouvre une connexion ; `with psycopg.connect(...)` valide la transaction
en sortie de bloc, et l'annule si une exception est levée (tout ou rien).
"""
import psycopg
from psycopg import errors
from psycopg.rows import dict_row

from app.core.config import settings


class NotFound(Exception):
    """-> 404"""


class Conflict(Exception):
    """-> 409"""


class InsufficientStock(Exception):
    """-> 422, avec le détail par produit."""

    def __init__(self, details: list[dict]):
        super().__init__("Stock insuffisant")
        self.details = details


RESERVATION_COLS = "id, order_id, product_id, warehouse_id, quantity, status, created_at, updated_at"
SHIPMENT_COLS = "id, order_id, warehouse_id, status, created_at, shipped_at, delivered_at"
NEXT_SHIPMENT_STATUS = {"preparing": "shipped", "shipped": "delivered"}

STOCK_ITEM_SQL = """
    SELECT s.product_id, s.warehouse_id, w.name AS warehouse_name, s.quantity, s.updated_at
    FROM inventory.stock s
    JOIN inventory.warehouses w ON w.id = s.warehouse_id
    WHERE s.product_id = %s
"""

# Décrémente l'entrepôt le mieux pourvu, en une seule requête.
# La condition `s.quantity >= q` est réévaluée si une autre transaction a modifié la ligne
# entre-temps (READ COMMITTED) : pas de survente, et 0 ligne renvoyée si le stock a disparu.
RESERVE_SQL = """
    UPDATE inventory.stock s
    SET quantity = s.quantity - %(q)s, updated_at = now()
    WHERE s.product_id = %(p)s
      AND s.quantity >= %(q)s
      AND s.warehouse_id = (
          SELECT warehouse_id FROM inventory.stock
          WHERE product_id = %(p)s AND quantity >= %(q)s
          ORDER BY quantity DESC, warehouse_id
          LIMIT 1
      )
    RETURNING s.warehouse_id
"""


class StockRepository:
    def __init__(self):
        self.dsn = (
            f"postgresql://{settings.postgres_user}:{settings.postgres_password}"
            f"@{settings.postgres_host}:{settings.postgres_port}/{settings.postgres_db}"
        )

    def _connect(self):
        return psycopg.connect(self.dsn, row_factory=dict_row)

    #  STOCK
    def get_stock_summary(self, product_id: int) -> dict:
        with self._connect() as conn:
            rows = conn.execute(STOCK_ITEM_SQL + " ORDER BY s.warehouse_id", (product_id,)).fetchall()
        if not rows:
            raise NotFound(f"Aucun stock pour le produit {product_id}")
        return {"product_id": product_id,
                "total_quantity": sum(r["quantity"] for r in rows),
                "by_warehouse": rows}

    def get_stock_item(self, product_id: int, warehouse_id: int) -> dict:
        with self._connect() as conn:
            row = conn.execute(STOCK_ITEM_SQL + " AND s.warehouse_id = %s",
                               (product_id, warehouse_id)).fetchone()
        if not row:
            raise NotFound("Aucun stock pour ce produit dans cet entrepôt")
        return row

    def create_initial_stock(self, product_id: int, warehouse_id: int, quantity: int) -> dict:
        with self._connect() as conn:
            if not conn.execute("SELECT 1 FROM inventory.warehouses WHERE id = %s", (warehouse_id,)).fetchone():
                raise NotFound(f"Entrepôt {warehouse_id} introuvable")
            if conn.execute("SELECT 1 FROM inventory.stock WHERE product_id = %s AND warehouse_id = %s",
                            (product_id, warehouse_id)).fetchone():
                raise Conflict("Stock déjà existant pour ce produit dans cet entrepôt")
            try:
                conn.execute("INSERT INTO inventory.stock (product_id, warehouse_id, quantity, updated_at) "
                             "VALUES (%s, %s, %s, now())", (product_id, warehouse_id, quantity))
            except errors.UniqueViolation:   # création simultanée
                raise Conflict("Stock déjà existant pour ce produit dans cet entrepôt")
        return self.get_stock_item(product_id, warehouse_id)

    #  RÉSERVATIONS (Saga)
    def _reservations(self, conn, order_id: int, product_id: int | None = None, lock: bool = False) -> list[dict]:
        sql = f"SELECT {RESERVATION_COLS} FROM inventory.reservations WHERE order_id = %s"
        params = [order_id]
        if product_id is not None:
            sql += " AND product_id = %s"
            params.append(product_id)
        sql += " ORDER BY id" + (" FOR UPDATE" if lock else "")
        return conn.execute(sql, params).fetchall()

    def reserve_stock(self, order_id: int, items: list[tuple[int, int]]) -> list[dict]:
        """Tout ou rien. Rejeu pour une commande déjà réservée : renvoie l'existant sans rien décrémenter."""
        wanted: dict[int, int] = {}
        for product_id, quantity in items:                 # même produit sur deux lignes -> une seule réservation
            wanted[product_id] = wanted.get(product_id, 0) + quantity

        try:
            with self._connect() as conn:
                existing = self._reservations(conn, order_id)
                if existing:
                    return existing

                missing = []
                for product_id, quantity in wanted.items():
                    row, available = None, 0
                    for _ in range(3):   # une réservation concurrente peut « voler » l'entrepôt choisi : on réessaie
                        row = conn.execute(RESERVE_SQL, {"p": product_id, "q": quantity}).fetchone()
                        if row:
                            break
                        available = conn.execute(
                            "SELECT COALESCE(MAX(quantity), 0) AS a FROM inventory.stock WHERE product_id = %s",
                            (product_id,)).fetchone()["a"]
                        if available < quantity:   # plus aucun entrepôt ne peut servir la ligne
                            break
                    if row is None:
                        missing.append({"product_id": product_id, "requested": quantity, "available": available})
                        continue
                    conn.execute(
                        "INSERT INTO inventory.reservations "
                        "(order_id, product_id, warehouse_id, quantity, status, created_at, updated_at) "
                        "VALUES (%s, %s, %s, %s, 'reserved', now(), now())",
                        (order_id, product_id, row["warehouse_id"], quantity))

                if missing:
                    raise InsufficientStock(missing)       # annule toute la transaction
                return self._reservations(conn, order_id)
        except errors.UniqueViolation:
            # Deux /reserve simultanés pour la même commande : l'autre a gagné, on renvoie son résultat.
            with self._connect() as conn:
                return self._reservations(conn, order_id)

    def commit_reservations(self, order_id: int) -> list[dict]:
        with self._connect() as conn:
            rows = self._reservations(conn, order_id, lock=True)
            if not rows:
                raise NotFound(f"Aucune réservation pour la commande {order_id}")
            if any(r["status"] == "released" for r in rows):
                raise Conflict("Réservations déjà libérées : impossible de confirmer")
            conn.execute("UPDATE inventory.reservations SET status = 'committed', updated_at = now() "
                         "WHERE order_id = %s AND status = 'reserved'", (order_id,))
            return self._reservations(conn, order_id)

    def release_stock(self, order_id: int, product_id: int | None = None) -> list[dict]:
        with self._connect() as conn:
            rows = self._reservations(conn, order_id, product_id, lock=True)
            if not rows:
                raise NotFound(f"Aucune réservation pour la commande {order_id}")
            to_release = [r for r in rows if r["status"] != "released"]   # idempotent
            for r in to_release:
                conn.execute("UPDATE inventory.stock SET quantity = quantity + %s, updated_at = now() "
                             "WHERE product_id = %s AND warehouse_id = %s",
                             (r["quantity"], r["product_id"], r["warehouse_id"]))
            if to_release:
                conn.execute("UPDATE inventory.reservations SET status = 'released', updated_at = now() "
                             "WHERE id = ANY(%s)", ([r["id"] for r in to_release],))
            return self._reservations(conn, order_id, product_id)

    #  ENTREPÔTS
    def get_warehouses(self) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute("""
                SELECT w.id, w.name, w.capacity_units, COALESCE(SUM(s.quantity), 0)::int AS used_units
                FROM inventory.warehouses w
                LEFT JOIN inventory.stock s ON s.warehouse_id = w.id
                GROUP BY w.id
                ORDER BY w.id
            """).fetchall()
        for r in rows:
            fill = r["used_units"] / r["capacity_units"] if r["capacity_units"] else 0.0
            r["fill_rate"] = round(fill, 4)
            r["status"] = "near_full" if fill > 0.90 else "normal"
        return rows

    #  EXPÉDITIONS
    def get_shipments(self, warehouse_id: int | None, status: str | None, limit: int, offset: int) -> dict:
        where, params = [], []
        if warehouse_id is not None:
            where.append("warehouse_id = %s")
            params.append(warehouse_id)
        if status is not None:
            where.append("status = %s")
            params.append(status)
        clause = (" WHERE " + " AND ".join(where)) if where else ""
        with self._connect() as conn:
            total = conn.execute(f"SELECT count(*) AS n FROM inventory.shipments{clause}", params).fetchone()["n"]
            items = conn.execute(
                f"SELECT {SHIPMENT_COLS} FROM inventory.shipments{clause} "
                "ORDER BY created_at DESC, id DESC LIMIT %s OFFSET %s", [*params, limit, offset]).fetchall()
        return {"items": items, "total": total}

    def create_shipment(self, order_id: int, warehouse_id: int) -> dict:
        with self._connect() as conn:
            if not conn.execute("SELECT 1 FROM inventory.warehouses WHERE id = %s", (warehouse_id,)).fetchone():
                raise NotFound(f"Entrepôt {warehouse_id} introuvable")
            if conn.execute("SELECT 1 FROM inventory.shipments WHERE order_id = %s", (order_id,)).fetchone():
                raise Conflict("Une expédition existe déjà pour cette commande")
            try:
                return conn.execute(
                    f"INSERT INTO inventory.shipments (order_id, warehouse_id, status, created_at) "
                    f"VALUES (%s, %s, 'preparing', now()) RETURNING {SHIPMENT_COLS}",
                    (order_id, warehouse_id)).fetchone()
            except errors.UniqueViolation:
                raise Conflict("Une expédition existe déjà pour cette commande")

    def update_shipment_status(self, shipment_id: int, new_status: str) -> dict:
        with self._connect() as conn:
            current = conn.execute(f"SELECT {SHIPMENT_COLS} FROM inventory.shipments WHERE id = %s FOR UPDATE",
                                   (shipment_id,)).fetchone()
            if not current:
                raise NotFound(f"Expédition {shipment_id} introuvable")
            if current["status"] == new_status:          # rejeu : rien à faire
                return current
            if NEXT_SHIPMENT_STATUS.get(current["status"]) != new_status:
                raise Conflict(f"Transition interdite : {current['status']} -> {new_status}")
            ts_column = "shipped_at" if new_status == "shipped" else "delivered_at"
            return conn.execute(
                f"UPDATE inventory.shipments SET status = %s, {ts_column} = now() "
                f"WHERE id = %s RETURNING {SHIPMENT_COLS}", (new_status, shipment_id)).fetchone()