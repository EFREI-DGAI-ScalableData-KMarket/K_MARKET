#!/usr/bin/env python3
"""Générateur de trafic pour la démo (Grafana, Kafka) : simule l'activité de la boutique.

Chaque cycle, choisi au hasard :
  - lecture catalogue (liste, fiche, recherche) ............ GraphQL /v1/catalog
  - lecture du stock et des entrepôts ...................... GET /v1/stock/{id}, /v1/warehouses
  - réservation puis libération (2 événements Kafka) ....... POST /v1/stock/reserve + /release
  - commande (événement order_placed) ...................... POST /v1/orders
  - quelques erreurs 4xx volontaires (produit inconnu) ..... pour la courbe d'erreurs
Avec --sales : vraies ventes (reserve + commit), le stock baisse pour de bon ; --hot les concentre sur quelques produits.

Sans --sales, les réservations sont toujours libérées : le stock final est inchangé.
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
import uuid
BASE = os.getenv("BASE_URL", "http://localhost:8000/v1")          # la gateway
STOCK = os.getenv("STOCK_URL", BASE)
CATALOG = os.getenv("CATALOG_URL", f"{BASE}/catalog")
ORDERS = os.getenv("ORDERS_URL", BASE)
CUSTOMERS = int(os.getenv("TRAFFIC_CUSTOMERS", "500"))   # ids clients existants : 1..N
HOT: list[int] = []                                       # produits vendus en priorité (--hot)
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

def place_order(c):
    """Commande → événement order_placed (topic orders.events), pour les KPI du jour.
    Tant que la saga n'existe pas, la commande est enregistrée « paid » et le stock n'est pas touché."""
    products = random.sample(range(1, 301), k=random.randint(1, 3))
    body = {"customer_id": random.randint(1, CUSTOMERS),
            "items": [{"product_id": p, "quantity": random.randint(1, 3)} for p in products]}
    headers = {**SERVICE, "Idempotency-Key": str(uuid.uuid4())}
    count("order", c.post(f"{ORDERS}/orders", headers=headers, json=body))


def sale(c):
    """Vente réelle : réservation puis validation (commit). Le stock baisse pour de bon.
    80 % des ventes portent sur les produits --hot, pour faire apparaître une rupture proche."""
    order_id = random.randint(900_000_000, 2_000_000_000)
    product = random.choice(HOT) if HOT and random.random() < 0.8 else random.randint(1, 300)
    items = [{"product_id": product, "quantity": random.randint(1, 3)}]
    r = c.post(f"{STOCK}/stock/reserve", headers=SERVICE, json={"order_id": order_id, "items": items})
    count("sale-reserve", r)                                       # 409 si le stock ne suffit plus
    if r.status_code == 201:
        count("sale-commit", c.post(f"{STOCK}/stock/commit", headers=SERVICE, json={"order_id": order_id}))


def errors_4xx(c):
    """404 volontaires : alimentent la courbe des erreurs 4xx sans déclencher d'alerte (seuls les 5xx alertent)."""
    count("404", c.get(f"{STOCK}/stock/{random.randint(10**9, 2 * 10**9)}", headers=SERVICE))


def run_safely(fn, c):
    try:
        fn(c)
    except httpx.HTTPError as e:                                   # service arrêté : on compte et on continue
        with lock:
            stats[f"{fn.__name__} injoignable ({type(e).__name__})"] += 1


ACTIONS = [(catalog_read, 35), (stock_read, 25), (reserve_release, 20), (place_order, 15), (errors_4xx, 5)]


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--duration", type=int, default=120, help="secondes (défaut 120)")
    p.add_argument("--rps", type=float, default=10, help="actions par seconde (défaut 10)")
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--sales", action="store_true", help="ajoute de vraies ventes (reserve + commit) : le stock baisse")
    p.add_argument("--hot", default="", help="produits vendus en priorité, ex. 23,154 (avec --sales)")
    args = p.parse_args()

    HOT.extend(int(x) for x in args.hot.split(",") if x.strip())
    actions = ACTIONS + ([(sale, 20)] if args.sales else [])
    funcs, weights = zip(*actions)
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