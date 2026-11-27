"""Volet 4 : job temps réel.

Lit orders.events et inventory.stock-changed (Kafka), calcule par fenêtre d'une minute et écrit dans PostgreSQL
(schéma analytics, par upsert) :
  - kpi_minute      : commandes, CA, échecs de paiement (Vue d'ensemble)
  - demand_minute   : demande nette par produit, puis stock_alerts (rupture proche, stock lu par l'API Stock)
  - warehouse_fill  : dernier remplissage de chaque entrepôt, saturation au-dessus de 90 %

Lancement : make realtime. Le dossier spark/ est monté en lecture seule dans /opt/spark-apps.
"""
import logging
import os
import time

import httpx
import psycopg
from pyspark.sql import SparkSession
from pyspark.sql.functions import col, from_json, max as max_, max_by, sum as sum_, when, window
from pyspark.sql.types import IntegerType, StringType, StructField, StructType

BOOTSTRAP = "kafka:19092"  # listener INTERNAL

spark = SparkSession.builder.appName("kmarket-realtime").getOrCreate()
spark.sparkContext.setLogLevel("WARN")
# Heures en UTC, comme occurred_at. 4 partitions de calcul au lieu de 200 par défaut :
# chaque lot ferait sinon 200 petites tâches pour quelques dizaines d'événements.
spark.conf.set("spark.sql.session.timeZone", "UTC")
spark.conf.set("spark.sql.shuffle.partitions", "4")

WATERMARK = "2 minutes"   # retard maximum accepté pour un événement (à justifier par le retard mesuré)
WINDOW = "1 minute"       # taille des fenêtres

# occurred_at est lu en texte puis converti par cast : il gère l'ISO 8601 avec microsecondes et fuseau
# (« …36.929701+00:00 » côté Python, « …30.065645Z » côté Java).
ORDER_EVENT = StructType([
    StructField("event_id", StringType()), StructField("event_type", StringType()),
    StructField("occurred_at", StringType()), StructField("order_id", IntegerType()),
    StructField("customer_id", IntegerType()), StructField("status", StringType()),
    StructField("previous_status", StringType()), StructField("total_cents", IntegerType()),
])

STOCK_EVENT = StructType([
    StructField("event_id", StringType()), StructField("event_type", StringType()),
    StructField("occurred_at", StringType()), StructField("warehouse_id", IntegerType()),
    StructField("product_id", IntegerType()), StructField("delta", IntegerType()),
    StructField("quantity", IntegerType()), StructField("reason", StringType()),
    StructField("order_id", IntegerType()), StructField("warehouse_used_units", IntegerType()),
    StructField("warehouse_capacity_units", IntegerType()),
])

CHECKPOINTS = "/opt/spark-checkpoints"     # volume partagé entre spark-master et spark-worker
DSN = os.environ["ANALYTICS_DSN"]

log = logging.getLogger("realtime")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

STOCK_URL = os.environ["STOCK_URL"]
AUTH_URL = os.environ["AUTH_URL"]
API_KEY = os.environ["STREAM_CONSUMER_API_KEY"]
HORIZON_MINUTES = 5      # rythme de vente calculé sur les 5 dernières minutes
ALERT_MINUTES = 30       # alerte si le stock tient moins de 30 minutes à ce rythme
NEAR_FULL = 0.90          # saturation proche au-dessus de 90 % (sujet, onglet Logistique)



def read(topic: str, schema: StructType):
    raw = (spark.readStream.format("kafka")
           .option("kafka.bootstrap.servers", BOOTSTRAP)
           .option("subscribe", topic)
           .option("startingOffsets", "earliest")
           .load())
    return (raw.select(from_json(col("value").cast("string"), schema).alias("e"), "partition", "offset")
               .select("e.*", "partition", "offset")
               .withColumn("occurred_at", col("occurred_at").cast("timestamp")))

def orders_per_minute(orders):
    """KPI par minute d'événement. Le CA n'est compté qu'au passage à « paid »."""
    becomes_paid = (col("status") == "paid") & (col("previous_status").isNull() | (col("previous_status") != "paid"))
    becomes_failed = (col("status") == "payment_failed") & (col("previous_status").isNull() | (col("previous_status") != "payment_failed"))
    return (orders
            .withWatermark("occurred_at", WATERMARK)
            .dropDuplicatesWithinWatermark(["event_id"])          # « au moins une fois » → une seule fois
            .groupBy(window("occurred_at", WINDOW))
            .agg(sum_(when(col("event_type") == "order_placed", 1).otherwise(0)).alias("orders_placed"),
                 sum_(when(becomes_paid, 1).otherwise(0)).alias("orders_paid"),
                 sum_(when(becomes_paid, col("total_cents")).otherwise(0)).alias("revenue_cents"),
                 sum_(when(becomes_failed, 1).otherwise(0)).alias("payments_failed")))


def demand_per_minute(stock):
    """Demande nette par produit et par minute : réservations moins libérations."""
    return (stock
            .where(col("reason").isin("reserve", "release"))
            .withWatermark("occurred_at", WATERMARK)
            .dropDuplicatesWithinWatermark(["event_id"])
            .groupBy(window("occurred_at", WINDOW), "product_id")
            .agg((-sum_("delta")).alias("net_units")))


def warehouse_fill(stock):
    """Dernier remplissage connu de chaque entrepôt. Chaque événement du topic porte used/capacity après
    la modification ; l'état initial vient des événements warehouse_snapshot écrits par le seed (jamais d'appel
    à GET /warehouses, interdit à service_account)."""
    return (stock
            .withWatermark("occurred_at", WATERMARK)
            .dropDuplicatesWithinWatermark(["event_id"])
            .groupBy(window("occurred_at", WINDOW), "warehouse_id")
            .agg(max_("occurred_at").alias("measured_at"),
                 max_by("warehouse_used_units", "occurred_at").alias("used_units"),
                 max_by("warehouse_capacity_units", "occurred_at").alias("capacity_units"))
            .withColumn("fill_rate", col("used_units") / col("capacity_units"))
            .withColumn("near_full", col("fill_rate") > NEAR_FULL))


_token = {"value": None, "expires": 0.0}


def service_token() -> str:
    """Jeton service_account obtenu avec la clé d'API du compte technique ; renouvelé une minute avant expiration."""
    if _token["value"] and time.time() < _token["expires"] - 60:
        return _token["value"]
    r = httpx.post(f"{AUTH_URL}/auth/token", headers={"X-API-Key": API_KEY}, timeout=5)
    r.raise_for_status()
    body = r.json()
    _token.update(value=body["access_token"], expires=time.time() + body.get("expires_in", 900))
    return _token["value"]


SELLING = """
SELECT product_id, SUM(net_units) AS sold
FROM analytics.demand_minute
WHERE window_start >= now() - make_interval(mins => %s)
GROUP BY product_id
HAVING SUM(net_units) > 0
"""

KPI_UPSERT = """
INSERT INTO analytics.kpi_minute (window_start, window_end, orders_placed, orders_paid, revenue_cents, payments_failed, updated_at)
VALUES (%s, %s, %s, %s, %s, %s, now())
ON CONFLICT (window_start) DO UPDATE SET
  orders_placed = EXCLUDED.orders_placed, orders_paid = EXCLUDED.orders_paid,
  revenue_cents = EXCLUDED.revenue_cents, payments_failed = EXCLUDED.payments_failed, updated_at = now()
"""

DEMAND_UPSERT = """
INSERT INTO analytics.demand_minute (window_start, window_end, product_id, net_units, updated_at)
VALUES (%s, %s, %s, %s, now())
ON CONFLICT (window_start, product_id) DO UPDATE SET net_units = EXCLUDED.net_units, updated_at = now()
"""

ALERT_UPSERT = """
INSERT INTO analytics.stock_alerts (product_id, remaining_units, units_per_minute, minutes_left, active, raised_at, updated_at)
VALUES (%s, %s, %s, %s, %s, now(), now())
ON CONFLICT (product_id) DO UPDATE SET
  remaining_units = EXCLUDED.remaining_units, units_per_minute = EXCLUDED.units_per_minute,
  minutes_left = EXCLUDED.minutes_left, active = EXCLUDED.active, updated_at = now(),
  raised_at = CASE WHEN stock_alerts.active OR NOT EXCLUDED.active THEN stock_alerts.raised_at ELSE now() END
"""
FILL_UPSERT = """
INSERT INTO analytics.warehouse_fill
  (warehouse_id, used_units, capacity_units, fill_rate, near_full, measured_at, near_full_since, updated_at)
VALUES (%s, %s, %s, %s, %s, %s, CASE WHEN %s THEN now() END, now())
ON CONFLICT (warehouse_id) DO UPDATE SET
  used_units = EXCLUDED.used_units, capacity_units = EXCLUDED.capacity_units,
  fill_rate = EXCLUDED.fill_rate, near_full = EXCLUDED.near_full,
  measured_at = EXCLUDED.measured_at, updated_at = now(),
  near_full_since = CASE
    WHEN NOT EXCLUDED.near_full THEN NULL                          -- redevenu normal
    WHEN warehouse_fill.near_full THEN warehouse_fill.near_full_since  -- déjà saturé : on garde le début
    ELSE now() END                                                 -- vient de passer au-dessus de 90 %
WHERE EXCLUDED.measured_at > warehouse_fill.measured_at              -- jamais un état plus ancien sur un plus récent
"""

def evaluate_stock_alerts(conn):
    """Rupture proche : rythme de vente (5 dernières minutes) comparé au stock restant lu par l'API Stock."""
    selling = conn.execute(SELLING, (HORIZON_MINUTES,)).fetchall()
    if selling:
        ids = ",".join(str(pid) for pid, _ in selling[:200])                  # l'API accepte 200 ids maximum
        r = httpx.get(f"{STOCK_URL}/stock", params={"product_ids": ids},
                      headers={"Authorization": f"Bearer {service_token()}"}, timeout=5)
        r.raise_for_status()
        remaining = {s["product_id"]: s["total_quantity"] for s in r.json()}
        rows = []
        for pid, sold in selling:
            if pid not in remaining:
                continue
            per_minute = sold / HORIZON_MINUTES
            minutes_left = remaining[pid] / per_minute
            rows.append((pid, remaining[pid], round(per_minute, 2), round(minutes_left, 1), minutes_left < ALERT_MINUTES))
        conn.cursor().executemany(ALERT_UPSERT, rows)
    # Les produits qui ne se vendent plus ne sont plus en danger : l'alerte s'éteint.
    selling_ids = [pid for pid, _ in selling] or [-1]
    conn.execute("UPDATE analytics.stock_alerts SET active = false, updated_at = now() "
                 "WHERE active AND NOT (product_id = ANY(%s))", (selling_ids,))


def to_postgres(df, name: str, sql: str, columns: list[str], after=None):
    """Chaque lot : les fenêtres modifiées (mode update) sont réécrites dans PostgreSQL.
    Écrire en écrasant rend le lot rejouable : après une panne, Spark le rejoue et la base reste juste.
    `after(conn)` : traitement facultatif après l'écriture (ici, l'évaluation des alertes)."""
    flat = df.select(col("window.start").alias("window_start"), col("window.end").alias("window_end"), "*").drop("window")

    def write(batch, batch_id):
        rows = [tuple(r[c] for c in columns) for r in batch.collect()]
        if not rows:
            return
        with psycopg.connect(DSN, options="-c TimeZone=UTC") as conn:
            conn.cursor().executemany(sql, rows)
        if after:
            try:
                with psycopg.connect(DSN, options="-c TimeZone=UTC") as conn:
                    after(conn)
            except Exception:
                # API Stock ou auth indisponible : on le note et on continue. Les KPI ne s'arrêtent pas,
                # et le lot suivant recalcule les alertes depuis la table.
                log.exception("évaluation des alertes impossible (lot %s)", batch_id)

    return (flat.writeStream.outputMode("update").foreachBatch(write)
                .option("checkpointLocation", f"{CHECKPOINTS}/{name}")
                .queryName(name).start())

def to_console(df, name: str, mode: str = "append"):
    return (df.writeStream.format("console").option("truncate", False).outputMode(mode)
              .option("checkpointLocation", f"/tmp/checkpoints/console-{name}")
              .queryName(name).start())



orders = read("orders.events", ORDER_EVENT)
stock = read("inventory.stock-changed", STOCK_EVENT)
to_postgres(orders_per_minute(orders), "kpi_orders", KPI_UPSERT,
            ["window_start", "window_end", "orders_placed", "orders_paid", "revenue_cents", "payments_failed"])
to_postgres(demand_per_minute(stock), "demand", DEMAND_UPSERT,
            ["window_start", "window_end", "product_id", "net_units"], after=evaluate_stock_alerts)
# near_full apparaît deux fois : la 2e alimente le CASE de near_full_since (paramètres positionnels).
to_postgres(warehouse_fill(stock), "warehouse_fill", FILL_UPSERT,
            ["warehouse_id", "used_units", "capacity_units", "fill_rate", "near_full", "measured_at", "near_full"])
spark.streams.awaitAnyTermination()