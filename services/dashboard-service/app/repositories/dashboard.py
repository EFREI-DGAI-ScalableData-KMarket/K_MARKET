"""Lecture du schéma analytics, écrit par le job Spark temps réel (spark/streaming/realtime.py)."""
from app.db import connect

# Minuit, heure de Paris, exprimé en timestamptz : « aujourd'hui » pour l'équipe Market.
DAY_START = "(date_trunc('day', now() AT TIME ZONE 'Europe/Paris') AT TIME ZONE 'Europe/Paris')"

TODAY = f"""
SELECT COALESCE(SUM(orders_placed), 0)::bigint   AS orders,
       COALESCE(SUM(orders_paid), 0)::bigint     AS orders_paid,
       COALESCE(SUM(revenue_cents), 0)::bigint   AS revenue_cents,
       COALESCE(SUM(payments_failed), 0)::bigint AS payments_failed
FROM analytics.kpi_minute
WHERE window_start >= {DAY_START}
"""

HOURLY = f"""
SELECT date_trunc('hour', window_start)  AS hour,
       SUM(orders_placed)::bigint        AS orders,
       SUM(revenue_cents)::bigint        AS revenue_cents
FROM analytics.kpi_minute
WHERE window_start >= {DAY_START}
GROUP BY 1
ORDER BY 1
"""

ALERTS = """
SELECT product_id, remaining_units, units_per_minute, minutes_left, raised_at
FROM analytics.stock_alerts
WHERE active
ORDER BY minutes_left
"""

WAREHOUSES = """
SELECT warehouse_id, used_units, capacity_units, fill_rate, near_full, near_full_since, measured_at
FROM analytics.warehouse_fill
ORDER BY warehouse_id
"""

# Dernière écriture du flux, toutes tables confondues (GREATEST ignore les NULL).
DATA_AS_OF = """
SELECT GREATEST((SELECT max(updated_at) FROM analytics.kpi_minute),
                (SELECT max(updated_at) FROM analytics.demand_minute),
                (SELECT max(updated_at) FROM analytics.warehouse_fill)) AS data_as_of
"""


def summary() -> dict:
    with connect() as conn:
        today = conn.execute(TODAY).fetchone()
        hourly = conn.execute(HOURLY).fetchall()
        alerts = conn.execute(ALERTS).fetchall()
        warehouses = conn.execute(WAREHOUSES).fetchall()
        data_as_of = conn.execute(DATA_AS_OF).fetchone()["data_as_of"]

    paid = today["orders_paid"]
    today["average_basket_cents"] = round(today["revenue_cents"] / paid) if paid else None
    return {"data_as_of": data_as_of, "today": today, "hourly": hourly,
            "stock_alerts": alerts, "warehouses": warehouses}