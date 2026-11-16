"""Relais de l'outbox : publie dans Kafka les événements écrits dans inventory.outbox.

Lancement : python -m app.outbox_relay   (service `stock-outbox-relay` du compose, profil "stream")

Garantie « au moins une fois » (at-least-once) :
- un lot est lu avec FOR UPDATE SKIP LOCKED (plusieurs relais possibles sans double lecture) ;
- il n'est marqué publié qu'après l'accusé de réception de Kafka (acks=all) ;
- si Kafka est arrêté, la transaction est annulée et le lot sera retenté : rien n'est perdu,
  mais un événement peut être publié deux fois -> les consommateurs dédupliquent sur event_id.
"""
import json
import logging
import os
import time

import psycopg
from confluent_kafka import KafkaException, Producer
from prometheus_client import Counter, Gauge, start_http_server
from psycopg.rows import dict_row

from app.core.config import settings

log = logging.getLogger("outbox-relay")

BATCH_SIZE = 200
IDLE_SLEEP_S = 1.0
ERROR_SLEEP_S = 5.0
RETENTION = "7 days"            # événements publiés conservés 7 jours dans l'outbox (Kafka les garde aussi)
PURGE_EVERY_S = 3600
METRICS_PORT = 9100             # /metrics lu par Prometheus

# Métriques Prometheus du relais (débit Kafka et retard de publication)
PUBLISHED = Counter("outbox_events_published_total", "Événements publiés dans Kafka", ["topic"])
FAILURES = Counter("outbox_publish_failures_total", "Lots non publiés (Kafka ou PostgreSQL indisponible)")
PENDING = Gauge("outbox_pending_events", "Événements en attente de publication dans inventory.outbox")
LAST_SUCCESS = Gauge("outbox_last_success_timestamp_seconds", "Horodatage du dernier lot publié")


def make_producer() -> Producer:
    return Producer({
        "bootstrap.servers": os.getenv("KAFKA_BOOTSTRAP", "kafka:19092"),
        "acks": "all",
        "enable.idempotence": True,        # pas de doublon dû aux retries internes du producteur
        "linger.ms": 20,
    })


def publish_batch(conn, producer) -> int:
    """Publie un lot ; renvoie le nombre d'événements publiés. Lève une exception si Kafka échoue."""
    rows = conn.execute(
        "SELECT id, topic, event_key, payload FROM inventory.outbox "
        "WHERE published_at IS NULL ORDER BY id LIMIT %s FOR UPDATE SKIP LOCKED", (BATCH_SIZE,)).fetchall()
    if not rows:
        return 0

    failures = []

    def on_delivery(err, _msg):
        if err is not None:
            failures.append(err)

    for r in rows:
        producer.produce(r["topic"], key=r["event_key"], value=json.dumps(r["payload"]), on_delivery=on_delivery)
    remaining = producer.flush(10)
    if remaining or failures:
        raise KafkaException(f"{remaining} message(s) non confirmé(s), erreurs : {failures[:3]}")

    conn.execute("UPDATE inventory.outbox SET published_at = now() WHERE id = ANY(%s)", ([r["id"] for r in rows],))
    for r in rows:
        PUBLISHED.labels(r["topic"]).inc()
    LAST_SUCCESS.set_to_current_time()
    return len(rows)


def count_pending(conn) -> int:
    return conn.execute("SELECT count(*) AS n FROM inventory.outbox WHERE published_at IS NULL").fetchone()["n"]


def purge_published(conn) -> int:
    """Supprime les événements publiés depuis plus de RETENTION. Un événement en attente n'est jamais supprimé."""
    return conn.execute("DELETE FROM inventory.outbox WHERE published_at < now() - %s::interval",
                        (RETENTION,)).rowcount


def run(producer=None) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    producer = producer or make_producer()
    dsn = (f"postgresql://{settings.postgres_user}:{settings.postgres_password}"
           f"@{settings.postgres_host}:{settings.postgres_port}/{settings.postgres_db}")
    start_http_server(METRICS_PORT)
    log.info("Relais démarré (métriques sur :%d/metrics)", METRICS_PORT)
    while True:
        try:
            with psycopg.connect(dsn, row_factory=dict_row) as conn:
                last_purge = 0.0
                while True:
                    try:
                        with conn.transaction():
                            n = publish_batch(conn, producer)
                    finally:
                        PENDING.set(count_pending(conn))          # mis à jour même si Kafka est arrêté
                    if n:
                        log.info("%d événement(s) publié(s)", n)
                    else:
                        time.sleep(IDLE_SLEEP_S)
                    if time.time() - last_purge > PURGE_EVERY_S:
                        with conn.transaction():
                            purged = purge_published(conn)
                        if purged:
                            log.info("%d événement(s) publié(s) depuis plus de %s supprimé(s)", purged, RETENTION)
                        last_purge = time.time()
        except Exception as e:                         # Kafka ou PostgreSQL indisponible : on réessaie
            FAILURES.inc()
            log.warning("Échec (%s), nouvel essai dans %.0f s", e, ERROR_SLEEP_S)
            time.sleep(ERROR_SLEEP_S)


if __name__ == "__main__":
    run()