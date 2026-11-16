#!/usr/bin/env python3
"""Générateur de trafic pour la démo (Grafana, Kafka) : simule l'activité de la boutique.

Chaque cycle, choisi au hasard :
  - lecture catalogue (liste, fiche, recherche) ............ GraphQL /v1/catalog
  - lecture du stock et des entrepôts ...................... GET /v1/stock/{id}, /v1/warehouses
  - réservation puis libération (2 événements Kafka) ....... POST /v1/stock/reserve + /release
  - quelques erreurs 4xx volontaires (produit inconnu) ..... pour la courbe d'erreurs

Les réservations sont toujours libérées : le stock final est inchangé.
Usage : python scripts/traffic.py --duration 120 --rps 10    (ou make traffic DURATION=120 RPS=10)
Variable : JWT_SECRET (lue dans .env par le Makefile), BASE_URL (défaut : la gateway).
"""
import argparse
import os
import random
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import httpx
import jwt

BASE = os.getenv("BASE_URL", "http://localhost:8000/v1")          # la gateway
STOCK = os.getenv("STOCK_URL", BASE)
CATALOG = os.getenv("CATALOG_URL", f"{BASE}/catalog")
SECRET = os.environ["JWT_SECRET"]


def token(*roles):
    claims = {"sub": "traffic", "roles": list(roles), "exp": int(time.time()) + 3600}
    return {"Authorization": "Bearer " + jwt.encode(claims, SECRET, algorithm="HS256")}


SERVICE, LOGISTICS, MANAGER = token("service_account"), token("logistics"), token("catalog_manager")
SEARCH_WORDS = ["seoul", "album", "moon", "hoodie", "serum", "coree", "lightstick", "drama"]
stats, lock = Counter(), threading.Lock()


def count(label, r):
    with lock:
        stats[f"{label} {r.status_code}"] += 1


def gql(c, query):
    return c.post(CATALOG, json={"query": query}, headers=MANAGER)


def catalog_read(c):
    kind = random.random()
    if kind < 0.4:
        r = gql(c, "{ products(limit: 20, offset: %d) { total items { product_id name price_cents } } }" % random.randint(0, 280))
    elif kind < 0.8:
        r = gql(c, "{ product(product_id: %d) { name price_cents image_url } }" % random.randint(1, 300))
    else:
        r = gql(c, '{ products(filter: {search: "%s"}, limit: 10) { total } }' % random.choice(SEARCH_WORDS))
    count("catalog", r)


def stock_read(c):
    if random.random() < 0.7:
        count("stock", c.get(f"{STOCK}/stock/{random.randint(1, 300)}", headers=SERVICE))
    else:
        count("warehouses", c.get(f"{STOCK}/warehouses", headers=LOGISTICS))


def reserve_release(c):
    order_id = random.randint(900_000_000, 2_000_000_000)
    items = [{"product_id": random.randint(1, 300), "quantity": 1}]
    r = c.post(f"{STOCK}/stock/reserve", headers=SERVICE, json={"order_id": order_id, "items": items})
    count("reserve", r)
    if r.status_code == 201:
        time.sleep(random.uniform(0.05, 0.3))                     # le « paiement »
        count("release", c.post(f"{STOCK}/stock/release", headers=SERVICE, json={"order_id": order_id}))


def errors_4xx(c):
    """404 volontaires : alimentent la courbe des erreurs 4xx sans déclencher d'alerte (seuls les 5xx alertent)."""
    count("404", c.get(f"{STOCK}/stock/{random.randint(10**9, 2 * 10**9)}", headers=SERVICE))


def run_safely(fn, c):
    try:
        fn(c)
    except httpx.HTTPError as e:                                   # service arrêté : on compte et on continue
        with lock:
            stats[f"{fn.__name__} injoignable ({type(e).__name__})"] += 1


ACTIONS = [(catalog_read, 40), (stock_read, 30), (reserve_release, 25), (errors_4xx, 5)]


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--duration", type=int, default=120, help="secondes (défaut 120)")
    p.add_argument("--rps", type=float, default=10, help="actions par seconde (défaut 10)")
    p.add_argument("--workers", type=int, default=8)
    args = p.parse_args()

    funcs, weights = zip(*ACTIONS)
    stop_at = time.time() + args.duration
    print(f"Trafic vers {STOCK} et {CATALOG} : {args.rps} actions/s pendant {args.duration} s (Ctrl+C pour arrêter)")
    with httpx.Client(timeout=10) as c, ThreadPoolExecutor(args.workers) as pool:
        try:
            last_report = time.time()
            while time.time() < stop_at:
                fn = random.choices(funcs, weights=weights)[0]
                pool.submit(run_safely, fn, c)
                time.sleep(1 / args.rps)
                if time.time() - last_report >= 10:
                    print("  " + ", ".join(f"{k}: {v}" for k, v in sorted(stats.items())))
                    last_report = time.time()
        except KeyboardInterrupt:
            print("Arrêt demandé")
    print("Total : " + ", ".join(f"{k}: {v}" for k, v in sorted(stats.items())))


if __name__ == "__main__":
    main()