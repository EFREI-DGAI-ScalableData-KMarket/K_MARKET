#!/usr/bin/env python3
"""Seed KMarket : remplit les bases sources (PostgreSQL + MongoDB) avec un historique réaliste.

Le seed n'écrit QUE dans les sources. Le Data Lake (raw -> staging -> curated -> consumption)
est alimenté par les pipelines d'ingestion, jamais directement par ce script.

Idempotent : si les bases contiennent déjà des données, il ne fait rien (sauf avec --reset).

Variables d'environnement (voir .env.example) :
  PG_DSN, MONGO_URI, MONGO_DB, DEMO_PASSWORD,
  ORDERS_API_KEY, CATALOG_API_KEY, STREAM_CONSUMER_API_KEY
"""
import argparse
import hashlib
import json
import os
import random
import sys
import uuid
import time
from datetime import datetime, timedelta, timezone
from itertools import accumulate
from pathlib import Path

import bcrypt
import psycopg2
from faker import Faker
from psycopg2.extras import execute_values
from pymongo import MongoClient

# Règle métier du service Paiement : refus au-dessus de 200 €.
PAYMENT_LIMIT_CENTS = 20_000
BATCH_ORDERS = 20_000          # commandes insérées par lot (mémoire constante)
SHOWCASE_FILE = Path(__file__).with_name("showcase.json")

# --------------------------------------------------------------------------------------
# Référentiels (groupes, séries, marques : fictifs ; la vitrine réelle est dans showcase.json)
# --------------------------------------------------------------------------------------
GROUPS = [
    "NOVA9", "LUNARIA", "STELLARBOYS", "MOONLIT", "ECLIPSE7", "HALOGIRLS",
    "AURORA", "KINETIC", "BLOOMY", "NEONCREW", "SEOULSTAR", "VELVETINE",
    "PRISM", "ZENITH", "CHERRYBOMB", "ORBIT", "TWILIGHT5", "MIRAGE",
    "SUNDAY", "COMETS", "LUMEN", "DAYBREAK", "WILDFIRE", "STARDUST",
]
COULEURS = ["noir", "blanc", "gris", "rose", "bleu", "violet", "beige"]
ALBUM_A = ["Neon", "Midnight", "First", "Sweet", "Blue", "Paper", "Velvet", "Golden", "Silent", "Wild"]
ALBUM_B = ["Dreams", "Sun", "Light", "Chaos", "Hour", "Moon", "Rain", "Flower", "Echo", "Summer"]
ALBUM_VERSIONS = ["Photobook", "Jewel Case", "Kit", "Digipack", "Limited"]

# (type, prix min, prix max, fonction d'attributs propres)
MERCH = [
    ("Hoodie", 4500, 7500, lambda: {"size": random.sample(["S", "M", "L", "XL"], k=3),
                                    "couleur": random.sample(COULEURS, k=2), "matiere": "coton"}),
    ("T-shirt", 2500, 4500, lambda: {"size": random.sample(["S", "M", "L", "XL"], k=3),
                                     "couleur": random.sample(COULEURS, k=2), "matiere": "coton"}),
    ("Tote bag", 1500, 2900, lambda: {"matiere": "coton"}),
    ("Poster set", 1200, 2200, lambda: {"format": "A2", "pieces": random.choice([3, 5, 8])}),
    ("Porte-clés", 800, 1600, lambda: {"matiere": random.choice(["acrylique", "métal"])}),
    ("Porte-photocards", 700, 1500, lambda: {"couleur": [random.choice(COULEURS)]}),
]

DRAMAS = [
    ("Moonlit Hospital", "médical"), ("Seoul Rain", "romance"), ("The Last Hanbok", "historique"),
    ("Crown of Jeju", "historique"), ("Midnight Café Busan", "romance"), ("Shadow Prosecutor", "thriller"),
    ("Hanok Diaries", "tranche de vie"), ("Love in Gangnam", "romance"), ("Neon Detectives", "thriller"),
    ("Spring at Gyeongju", "romance"), ("The Idol Code", "comédie"), ("Winter Palace", "historique"),
]
DRAMA_SUPPORTS = [("DVD", 2490, 3490), ("Blu-ray", 2990, 4490), ("Coffret", 5990, 8990), ("Artbook", 2490, 3990)]

BEAUTY_BRANDS = ["Hanbit Skin", "Seoul Glow", "Jeju Dew", "Moonbeam Lab", "Bori Beauty", "Cherry Hanok"]
BEAUTY_TYPES = [  # (type, contenances possibles en ml, prix min, prix max)
    ("Sérum", [30, 50], 1490, 3490), ("Crème hydratante", [50, 75], 1690, 3290),
    ("Masque tissu", [25], 290, 590), ("Toner", [150, 200], 1290, 2490),
    ("Crème solaire", [50], 1190, 2290), ("Huile démaquillante", [150, 200], 1390, 2690),
]
BEAUTY_INGREDIENTS = ["centella asiatica", "niacinamide", "acide hyaluronique", "riz", "thé vert",
                      "ginseng", "mucine d'escargot", "propolis", "céramides"]
SKIN_TYPES = ["sèche", "grasse", "mixte", "sensible", "tous types"]

BOOK_TEMPLATES = [  # (titre, genre, tags)
    ("Apprendre le coréen - Niveau {n}", "méthode", ["langue", "coréen"]),
    ("Hangul en 30 jours - Volume {n}", "méthode", ["langue", "hangul"]),
    ("Cuisine coréenne : {x} recettes", "cuisine", ["cuisine", "corée"]),
    ("Voyage à Séoul : le guide {y}", "voyage", ["voyage", "séoul"]),
    ("Histoire de la Corée - Tome {n}", "histoire", ["histoire", "corée"]),
    ("Dans les coulisses de la K-pop - Tome {n}", "essai", ["k-pop", "essai"]),
    ("Manhwa Sakura Night - Tome {n}", "manhwa", ["manhwa", "romance"]),
    ("Manhwa Seoul Academy - Tome {n}", "manhwa", ["manhwa", "school"]),
]

# Répartition des produits générés par catégorie (en plus de la vitrine)
CATEGORY_SHARE = {"Album": 0.35, "Lightstick": 0.05, "Merch": 0.22, "Drama": 0.13, "Livre": 0.12, "Beaute": 0.13}

# Les 5 rôles applicatifs (D3) sont créés par db/postgres/05_auth.sql : le seed ne fait que les attribuer.
DEMO_ACCOUNTS = [  # comptes fixes pour la démo (un par rôle humain)
    ("admin@market.test", "admin"),
    ("catalog@market.test", "catalog_manager"),
    ("analyst@market.test", "analyst"),
    ("logistics@market.test", "logistics"),
]
SERVICE_KEYS = [  # (nom du compte technique, variable d'environnement contenant la clé)
    ("orders-api", "ORDERS_API_KEY"),
    ("catalog-api", "CATALOG_API_KEY"),
    ("stream-consumer", "STREAM_CONSUMER_API_KEY"),
]

# Entrepôts de la maquette, avec le taux de remplissage visé (un au-dessus de 90 % pour l'alerte)
WAREHOUSES = [
    ("Entrepôt Paris Nord", 20_000, 0.91),
    ("Entrepôt Lyon Sud", 15_000, 0.61),
    ("Entrepôt Lille", 10_000, 0.78),
    ("Entrepôt Marseille", 12_000, 0.45),
]

PG_TABLES = [
    "orders.order_items", "orders.idempotency_keys", "orders.orders", "orders.customers",
    "payments.payments",
    "inventory.reservations", "inventory.shipments", "inventory.stock", "inventory.warehouses",
    "auth.refresh_tokens", "auth.api_keys", "auth.user_roles", "auth.users",  # auth.roles : référence, jamais vidée
]
SERIAL_TABLES = [
    "orders.customers", "orders.orders", "orders.order_items", "payments.payments",
    "inventory.warehouses", "inventory.reservations", "inventory.shipments",
    "auth.users", "auth.api_keys", "auth.refresh_tokens",
]


# --------------------------------------------------------------------------------------
# Paramètres
# --------------------------------------------------------------------------------------
def parse_args():
    p = argparse.ArgumentParser(description="Génère l'historique KMarket (PostgreSQL + MongoDB)")
    p.add_argument("--pg-dsn", default=os.getenv("PG_DSN"),
                   help="ex. postgresql://postgres:***@localhost:5432/kmarket (défaut : $PG_DSN)")
    p.add_argument("--mongo-uri", default=os.getenv("MONGO_URI"),
                   help="ex. mongodb://root:***@localhost:27017/?authSource=admin (défaut : $MONGO_URI)")
    p.add_argument("--mongo-db", default=os.getenv("MONGO_DB", "kmarket"))
    p.add_argument("--customers", type=int, default=2000)
    p.add_argument("--products", type=int, default=300, help="Nombre total de produits, vitrine comprise")
    p.add_argument("--warehouses", type=int, default=4)
    p.add_argument("--orders", type=int, default=20_000, help="500000 pour la démo EXPLAIN")
    p.add_argument("--users", type=int, default=10, help="Comptes internes en plus des comptes de démo")
    p.add_argument("--days", type=int, default=90, help="Historique couvert, en jours")
    p.add_argument("--peak-share", type=float, default=0.2, help="Part des commandes sur le pic « comeback »")
    p.add_argument("--fraud-bursts", type=int, default=3, help="Rafales de paiements refusés injectées")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--reset", action="store_true", help="Vide les tables et la collection avant insertion")
    args = p.parse_args()
    if not args.pg_dsn or not args.mongo_uri:
        p.error("PG_DSN et MONGO_URI sont obligatoires (variables d'environnement ou options)")
    return args


def require_env(name):
    value = os.getenv(name)
    if not value:
        sys.exit(f"Variable d'environnement manquante : {name} (voir .env.example)")
    return value


# --------------------------------------------------------------------------------------
# Petits utilitaires
# --------------------------------------------------------------------------------------
def psych(price):
    """Prix psychologique : finit par 90 centimes."""
    return (price // 100) * 100 + 90


def isbn13():
    body = "978" + "".join(str(random.randint(0, 9)) for _ in range(9))
    total = sum((1 if i % 2 == 0 else 3) * int(d) for i, d in enumerate(body))
    return body + str((10 - total % 10) % 10)


def bcrypt_hash(password):
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()


def sha256(value):
    return hashlib.sha256(value.encode()).hexdigest()


def before_now(ts, now, margin_minutes=1):
    """Plafonne une date à « maintenant » (les dates générées ne doivent pas être dans le futur)."""
    return min(ts, now - timedelta(minutes=margin_minutes))


# --------------------------------------------------------------------------------------
# Catalogue (MongoDB)
# --------------------------------------------------------------------------------------
def build_catalog(n_total, fake, now, showcase):
    """Retourne (produits, poids de popularité, groupe par produit, groupe en comeback)."""
    groups = GROUPS[:]
    random.shuffle(groups)
    group_w = {g: 1 / (i + 1) for i, g in enumerate(groups)}   # loi de puissance
    comeback_group = groups[2]
    group_list, group_weights = list(group_w), list(group_w.values())

    products, weight, group_of = [], {}, {}

    def add(category, name, price, tags, description, attributes, w, group=None, always_active=False):
        pid = len(products) + 1
        products.append({
            "product_id": pid,
            "name": name,
            "category": category,
            "price_cents": price,
            "status": "active" if always_active or random.random() < 0.95 else "inactive",
            "tags": tags,
            "description": description,
            "attributes": attributes,
            "indexed_at": now,
        })
        weight[pid] = w
        if group:
            group_of[pid] = group

    def pick_group():
        return random.choices(group_list, weights=group_weights)[0]

    # --- Vitrine réelle : un produit par version, toujours actif, très demandé ---
    for a in showcase["albums"]:
        for v in a["versions"]:
            add("Album", f'{a["artist"]} - {a["title"]} ({v})', a["price_cents"],
                ["k-pop", "album", a["artist"].lower()], f'{a["artist"]}, album {a["type"]}.',
                {"artist": a["artist"], "version": v, "type": a["type"], "release_date": a["release_date"],
                 "photocards": random.randint(1, 4), "limited_edition": v == "Limited"},
                w=3.0, group=a["artist"], always_active=True)
    for ls in showcase["lightsticks"]:
        add("Lightstick", ls["name"], ls["price_cents"], ["k-pop", "lightstick", ls["artist"].lower()],
            f'Lightstick officiel {ls["artist"]}.',
            {"artist": ls["artist"], "version": ls["version"], "bluetooth": True, "piles": "AAA x3"},
            w=2.0, group=ls["artist"], always_active=True)

    # --- Produits générés ---
    remaining = max(n_total - len(products), 0)
    counts = {c: int(remaining * s) for c, s in CATEGORY_SHARE.items()}
    counts["Album"] += remaining - sum(counts.values())

    for _ in range(counts["Album"]):
        g = pick_group()
        version = random.choice(ALBUM_VERSIONS)
        add("Album", f"{g} - {random.choice(ALBUM_A)} {random.choice(ALBUM_B)} ({version})",
            psych(random.randint(2200, 4200)), ["k-pop", "album", g.lower()], fake.sentence(nb_words=12),
            {"artist": g, "version": version,
             "type": random.choices(["studio", "mini", "single"], weights=[2, 5, 2])[0],
             "release_date": (now - timedelta(days=random.randint(0, 900))).strftime("%Y-%m-%d"),
             "photocards": random.randint(1, 6), "limited_edition": version == "Limited"},
            group_w[g] * random.uniform(0.6, 1.4), group=g)

    for _ in range(counts["Lightstick"]):
        g = pick_group()
        v = f"Ver.{random.randint(1, 3)}"
        add("Lightstick", f"{g} Official Lightstick {v}", psych(random.randint(3500, 5900)),
            ["k-pop", "lightstick", g.lower()], fake.sentence(nb_words=12),
            {"artist": g, "version": v, "bluetooth": random.random() < 0.8, "piles": "AAA x3"},
            group_w[g] * random.uniform(0.6, 1.4), group=g)

    for _ in range(counts["Merch"]):
        g = pick_group()
        kind, pmin, pmax, attr_fn = random.choice(MERCH)
        add("Merch", f"{g} {kind} Ver.{random.randint(1, 3)}", psych(random.randint(pmin, pmax)),
            ["k-pop", "merch", g.lower()], fake.sentence(nb_words=12),
            {"artist": g, "type": kind, **attr_fn()},
            group_w[g] * random.uniform(0.6, 1.4), group=g)

    for _ in range(counts["Drama"]):
        serie, genre = random.choice(DRAMAS)
        support, pmin, pmax = random.choice(DRAMA_SUPPORTS)
        saison = random.choice([1, 1, 2, 3])
        add("Drama", f"{serie} - Saison {saison} ({support})", psych(random.randint(pmin, pmax)),
            ["k-drama", genre], fake.sentence(nb_words=12),
            {"serie": serie, "saison": saison, "episodes": random.choice([12, 16, 20]),
             "support": support, "sous_titres": random.sample(["fr", "en", "ko"], k=random.randint(1, 3))},
            0.25 * random.uniform(0.5, 1.5))

    for _ in range(counts["Livre"]):
        tpl, genre, tags = random.choice(BOOK_TEMPLATES)
        name = tpl.format(n=random.randint(1, 6), x=random.choice([50, 80, 100]), y=random.choice([2025, 2026]))
        add("Livre", name, psych(random.randint(1200, 3200)), ["livre"] + tags, fake.sentence(nb_words=12),
            {"isbn": isbn13(), "langue": random.choice(["fr", "fr", "en", "ko"]),
             "pages": random.randint(96, 420), "auteur": fake.name(), "genre": genre},
            0.08 * random.uniform(0.5, 1.5))

    for _ in range(counts["Beaute"]):
        brand = random.choice(BEAUTY_BRANDS)
        kind, sizes, pmin, pmax = random.choice(BEAUTY_TYPES)
        ml = random.choice(sizes)
        add("Beaute", f"{brand} {kind} {ml} ml", psych(random.randint(pmin, pmax)),
            ["k-beauty", kind.lower()], fake.sentence(nb_words=12),
            {"marque": brand, "type": kind, "contenance_ml": ml, "type_peau": random.choice(SKIN_TYPES),
             "ingredients": random.sample(BEAUTY_INGREDIENTS, k=3)},
            0.2 * random.uniform(0.5, 1.5))

    return products, weight, group_of, comeback_group


# --------------------------------------------------------------------------------------
# Entrepôts et stock (PostgreSQL inventory)
# --------------------------------------------------------------------------------------
def build_warehouses(n, fake):
    rows = []
    used = {w[0] for w in WAREHOUSES}
    for i in range(n):
        if i < len(WAREHOUSES):
            name, capacity, fill = WAREHOUSES[i]
        else:
            name = f"Entrepôt {fake.unique.city()}"
            while name in used:
                name = f"Entrepôt {fake.unique.city()}"
            capacity, fill = random.choice([8_000, 10_000, 15_000]), random.uniform(0.40, 0.85)
        used.add(name)
        rows.append((i + 1, name, capacity, fill))
    return rows


def build_stock(products, warehouses, now):
    """Répartit le stock pour atteindre le taux de remplissage visé de chaque entrepôt.

    Une partie des lignes est fixée (ruptures, stock bas) ; le reste est mis à l'échelle
    pour que sum(stock) / capacité = taux visé.
    """
    fixed = {w[0]: [] for w in warehouses}      # (product_id, qty)
    scalable = {w[0]: [] for w in warehouses}   # (product_id, poids)
    wids = [w[0] for w in warehouses]

    for p in products:
        limited = p["attributes"].get("limited_edition", False)
        chosen = [w for w in wids if random.random() < 0.7] or [random.choice(wids)]
        for w in chosen:
            r = random.random()
            if p["status"] == "inactive" or r < 0.05:
                fixed[w].append((p["product_id"], 0))                     # rupture / produit archivé
            elif r < 0.12:
                fixed[w].append((p["product_id"], random.randint(1, 5)))  # stock bas
            else:
                scalable[w].append((p["product_id"], random.uniform(0.2, 1.0) * (0.3 if limited else 1.0)))

    rows = []
    for wid, _name, capacity, fill in warehouses:
        target = round(capacity * fill)
        fixed_units = sum(q for _, q in fixed[wid])
        remainder = max(target - fixed_units, 0)
        total_w = sum(wt for _, wt in scalable[wid]) or 1.0
        rows += [(pid, wid, q, now) for pid, q in fixed[wid]]
        rows += [(pid, wid, max(6, round(wt / total_w * remainder)), now) for pid, wt in scalable[wid]]
    return rows


# --------------------------------------------------------------------------------------
# Commandes, paiements, réservations, livraisons
# --------------------------------------------------------------------------------------
def status_for_age(age):
    """Statut cohérent avec l'âge de la commande (décision D2)."""
    if age < timedelta(minutes=10):
        return random.choices(["pending", "stock_reserved", "paid"], weights=[40, 30, 30])[0]
    if age < timedelta(days=1):
        return random.choices(["paid", "preparing"], weights=[30, 70])[0]
    if age < timedelta(days=2):
        return random.choices(["preparing", "shipped"], weights=[50, 50])[0]
    if age < timedelta(days=5):
        return random.choices(["shipped", "delivered"], weights=[85, 15])[0]
    return random.choices(["delivered", "cancelled"], weights=[94, 6])[0]


PAID_STATUSES = {"paid", "preparing", "shipped", "delivered"}


def iter_orders(args, products, weight, group_of, comeback_group, now):
    """Produit les commandes dans l'ordre chronologique, sans tout garder en mémoire."""
    price = {p["product_id"]: p["price_cents"] for p in products}
    active = [p["product_id"] for p in products if p["status"] == "active"]
    cum_base = list(accumulate(weight[pid] for pid in active))
    cum_peak = list(accumulate(weight[pid] * (30 if group_of.get(pid) == comeback_group else 1) for pid in active))
    cheap_ids = [p["product_id"] for p in sorted((p for p in products if p["status"] == "active"),
                                                 key=lambda x: x["price_cents"])[:5]]

    peak_start = (now - timedelta(days=14)).replace(hour=18, minute=0, second=0, microsecond=0)
    peak_end = peak_start + timedelta(hours=6)

    # 1. Horodatages seuls (léger), triés ; kind = "normal" ou ("fraud", client)
    events = []
    for _ in range(args.orders):
        if random.random() < args.peak_share:
            ts = peak_start + timedelta(seconds=random.randint(0, 6 * 3600))
        else:
            ts = now - timedelta(seconds=random.randint(0, args.days * 86400))
        events.append((ts, None))
    # Rafales de fraude : même client, petits montants, paiements refusés, quelques secondes d'écart
    for _ in range(args.fraud_bursts):
        cid = random.randint(1, args.customers)
        start = now - timedelta(days=random.randint(1, 30), seconds=random.randint(0, 80_000))
        for k in range(random.randint(12, 20)):
            events.append((start + timedelta(seconds=k * random.randint(3, 9)), cid))
    events.sort(key=lambda e: e[0])

    # 2. Détail de chaque commande
    for order_id, (ts, fraud_customer) in enumerate(events, start=1):
        if fraud_customer:
            product_ids = [random.choice(cheap_ids)]
            customer_id = fraud_customer
        else:
            cum = cum_peak if (peak_start <= ts < peak_end and random.random() < 0.7) else cum_base
            k = random.choices([1, 2, 3, 4], weights=[55, 30, 12, 3])[0]
            product_ids = set()
            for _ in range(20):
                if len(product_ids) == k:
                    break
                product_ids.add(random.choices(active, cum_weights=cum)[0])
            product_ids = list(product_ids)
            customer_id = random.randint(1, args.customers)

        items = [(pid, random.choices([1, 2, 3], weights=[80, 15, 5])[0], price[pid]) for pid in product_ids]
        total = sum(q * u for _, q, u in items)

        status, failure = status_for_age(now - ts), None
        if fraud_customer:
            status, failure = "payment_failed", "card_declined"
        elif status in PAID_STATUSES | {"cancelled"}:
            if total > PAYMENT_LIMIT_CENTS:          # règle du service Paiement
                status, failure = "payment_failed", "amount_over_limit"
            elif random.random() < 0.02:            # carte refusée
                status, failure = "payment_failed", "card_declined"

        yield {"id": order_id, "customer_id": customer_id, "created_at": ts, "items": items,
               "total_cents": total, "status": status, "failure_reason": failure}


def expand_order(o, stock_wh, all_wids, now, item_id, res_id, pay_id, ship_id):
    """Lignes PostgreSQL d'une commande : order, items, payment, reservations, shipment."""
    ts, status = o["created_at"], o["status"]
    idem_keys = []  
    items, reservations, payments, shipments = [], [], [], []
    updated_at = ts

    # Réservations de la saga (une par ligne de commande)
    res_status = {"stock_reserved": "reserved", "payment_failed": "released",
                  "cancelled": "released"}.get(status, "committed" if status in PAID_STATUSES else None)
    first_wh = None
    for pid, qty, unit in o["items"]:
        items.append((item_id, o["id"], ts, pid, qty, unit))
        item_id += 1
        if res_status:
            wh = random.choice(stock_wh.get(pid) or all_wids)
            first_wh = first_wh or wh
            res_ts = ts + timedelta(seconds=random.randint(1, 5))
            reservations.append((res_id, o["id"], pid, wh, qty, res_status, res_ts, res_ts))
            res_id += 1

    # Paiement
    pay_status = {"stock_reserved": "pending", "payment_failed": "failed", "cancelled": "refunded"}.get(
        status, "succeeded" if status in PAID_STATUSES else None)
    if pay_status:
        pay_ts = before_now(ts + timedelta(seconds=random.randint(5, 60)), now, 0)
        payments.append((pay_id, o["id"], str(uuid.uuid4()), o["total_cents"], pay_status,
                         o["failure_reason"] if pay_status == "failed" else None, pay_ts))
        pay_id += 1
        updated_at = pay_ts

    # Livraison (décision D5)
    if status in ("preparing", "shipped", "delivered"):
        created = before_now(ts + timedelta(minutes=random.randint(10, 60)), now, 0)
        shipped = delivered = None
        if status in ("shipped", "delivered"):
            shipped = max(before_now(created + timedelta(hours=random.uniform(6, 30)), now, 5), created)
        if status == "delivered":
            delivered = max(before_now(shipped + timedelta(hours=random.uniform(24, 72)), now), shipped)
        ship_status = status
        shipments.append((ship_id, o["id"], first_wh, ship_status, created, shipped, delivered))
        ship_id += 1
        updated_at = delivered or shipped or created

    order_row = (o["id"], o["customer_id"], status, o["total_cents"], ts, updated_at)
    idem_keys.append((str(uuid.uuid4()), o["id"], ts))
    return order_row, items, payments, reservations, shipments,idem_keys, item_id, res_id, pay_id, ship_id


# --------------------------------------------------------------------------------------
# Comptes internes (auth)
# --------------------------------------------------------------------------------------
def build_auth(args, fake, now, demo_password, role_id):
    """role_id : {nom: id}, lu dans auth.roles (créée par 05_auth.sql)."""

    user_rows, user_role_rows = [], []
    for email, role in DEMO_ACCOUNTS:
        uid = len(user_rows) + 1
        user_rows.append((uid, email, bcrypt_hash(demo_password), True, now - timedelta(days=365)))
        user_role_rows.append((uid, role_id[role]))

    human_roles = ["catalog_manager", "analyst", "logistics"]
    for _ in range(args.users):
        uid = len(user_rows) + 1
        email = f"{fake.unique.user_name()}@market.test"
        user_rows.append((uid, email, bcrypt_hash(demo_password), random.random() < 0.9,
                          now - timedelta(days=random.randint(0, 365))))
        user_role_rows.append((uid, role_id[random.choice(human_roles)]))

    api_key_rows = [(i, name, sha256(require_env(env)), role_id["service_account"], True, now)
                    for i, (name, env) in enumerate(SERVICE_KEYS, start=1)]
    return user_rows, user_role_rows, api_key_rows


# --------------------------------------------------------------------------------------
# Écriture
# --------------------------------------------------------------------------------------
def already_seeded(cur, catalog):
    cur.execute("SELECT EXISTS (SELECT 1 FROM orders.customers)")
    return cur.fetchone()[0] or catalog.estimated_document_count() > 0


def sync_sequences(cur):
    """Les ids ont été insérés explicitement : on recale les séquences sur MAX(id)."""
    for table in SERIAL_TABLES:
        cur.execute(f"SELECT setval(pg_get_serial_sequence(%s, 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) "
                    f"FROM {table}", (table,))

def connect_pg(dsn, retries=10, delay=3):
    """Connexion PostgreSQL avec retry (le serveur ou le réseau Docker peuvent être lents)."""
    for attempt in range(1, retries + 1):
        try:
            return psycopg2.connect(dsn, connect_timeout=5)
        except psycopg2.OperationalError as e:
            if attempt == retries:
                raise
            reason = str(e).strip().splitlines()[0]
            print(f"PostgreSQL pas prêt ({attempt}/{retries}) : {reason} — nouvelle tentative dans {delay}s...",
                  flush=True)
            time.sleep(delay)

def main():
    args = parse_args()
    demo_password = require_env("DEMO_PASSWORD")
    random.seed(args.seed)
    Faker.seed(args.seed)
    fake = Faker("fr_FR")
    now = datetime.now(timezone.utc)
    showcase = json.loads(SHOWCASE_FILE.read_text(encoding="utf-8"))

    mongo = MongoClient(args.mongo_uri, serverSelectionTimeoutMS=10_000)
    catalog = mongo[args.mongo_db]["catalog"]
    conn = connect_pg(args.pg_dsn)

    try:
        with conn.cursor() as cur:
            if already_seeded(cur, catalog) and not args.reset:
                print("Bases déjà peuplées : rien à faire (relancer avec --reset pour tout régénérer).")
                return
        conn.rollback()

        # ----- Génération du référentiel -----
        products, weight, group_of, comeback_group = build_catalog(args.products, fake, now, showcase)
        warehouses = build_warehouses(args.warehouses, fake)
        stock_rows = build_stock(products, warehouses, now)
        stock_wh = {}
        for pid, wid, qty, _ in stock_rows:
            if qty > 0:
                stock_wh.setdefault(pid, []).append(wid)
        all_wids = [w[0] for w in warehouses]
        customer_rows = [(i, fake.name(), fake.unique.safe_email(),
                          now - timedelta(days=args.days + random.randint(0, 365)))
                         for i in range(1, args.customers + 1)]

        # ----- PostgreSQL : une seule transaction ; MongoDB écrit juste avant le COMMIT -----
        counts = dict.fromkeys(["orders", "items", "payments", "reservations", "shipments", "idem_keys"], 0)
        status_count = {}
        with conn, conn.cursor() as cur:
            def ins(sql, rows):
                execute_values(cur, sql, rows, page_size=5000)

            if args.reset:
                cur.execute(f"TRUNCATE {', '.join(PG_TABLES)} RESTART IDENTITY CASCADE")

            # Partitions couvrant tout l'historique généré (évite la partition DEFAULT)
            cur.execute("SELECT orders.ensure_monthly_partitions(%s, %s)",
                        ((now - timedelta(days=args.days + 31)).date(), (now + timedelta(days=365)).date()))

            cur.execute("SELECT name, id FROM auth.roles")
            role_id = dict(cur.fetchall())
            expected = {"admin", "catalog_manager", "analyst", "logistics", "service_account"}
            if not expected <= role_id.keys():
                sys.exit(f"Rôles manquants dans auth.roles : {sorted(expected - role_id.keys())} "
                         "(créés par db/postgres/05_auth.sql)")
            user_rows, user_role_rows, api_key_rows = build_auth(args, fake, now, demo_password, role_id)
            ins("INSERT INTO auth.users (id, email, password_hash, is_active, created_at) VALUES %s", user_rows)
            ins("INSERT INTO auth.user_roles (user_id, role_id) VALUES %s", user_role_rows)
            ins("INSERT INTO auth.api_keys (id, name, key_hash, role_id, is_active, created_at) VALUES %s", api_key_rows)

            ins("INSERT INTO inventory.warehouses (id, name, capacity_units) VALUES %s",
                [(wid, name, cap) for wid, name, cap, _ in warehouses])
            ins("INSERT INTO inventory.stock (product_id, warehouse_id, quantity, updated_at) VALUES %s", stock_rows)
            ins("INSERT INTO orders.customers (id, name, email, created_at) VALUES %s", customer_rows)

            buf = {"o": [], "i": [], "p": [], "r": [], "s": [],"k": []}
            ids = [1, 1, 1, 1]  # item_id, res_id, pay_id, ship_id

            def flush():
                ins("INSERT INTO orders.orders (id, customer_id, status, total_cents, created_at, updated_at) VALUES %s",
                    buf["o"])
                ins("INSERT INTO orders.order_items (id, order_id, order_created_at, product_id, quantity, "
                    "unit_price_cents) VALUES %s", buf["i"])
                if buf["k"]:                                             
                    ins("INSERT INTO orders.idempotency_keys (idempotency_key, order_id, order_created_at) VALUES %s",
                        buf["k"])
                if buf["p"]:
                    ins("INSERT INTO payments.payments (id, order_id, idempotency_key, amount_cents, status, "
                        "failure_reason, created_at) VALUES %s", buf["p"])
                if buf["r"]:
                    ins("INSERT INTO inventory.reservations (id, order_id, product_id, warehouse_id, quantity, "
                        "status, created_at, updated_at) VALUES %s", buf["r"])
                if buf["s"]:
                    ins("INSERT INTO inventory.shipments (id, order_id, warehouse_id, status, created_at, "
                        "shipped_at, delivered_at) VALUES %s", buf["s"])
                
                for k, key in (("orders", "o"), ("items", "i"), ("payments", "p"),
                               ("reservations", "r"), ("shipments", "s"), ("idem_keys", "k")):
                    counts[k] += len(buf[key])
                    buf[key].clear()

            for o in iter_orders(args, products, weight, group_of, comeback_group, now):
                order_row, items, pays, ress, ships,idem_keys, *new_ids = expand_order(o, stock_wh, all_wids, now, *ids)
                ids = new_ids
                buf["o"].append(order_row)
                buf["i"] += items
                buf["p"] += pays
                buf["r"] += ress
                buf["s"] += ships
                buf["k"] += idem_keys  
                status_count[o["status"]] = status_count.get(o["status"], 0) + 1
                if len(buf["o"]) >= BATCH_ORDERS:
                    flush()
            print(f"  ... {counts['orders']:>8} commandes insérées", flush=True)
            flush()
            sync_sequences(cur)

            # MongoDB en dernier : si l'insertion échoue, la transaction PostgreSQL est annulée.
            if args.reset:
                catalog.delete_many({})
            catalog.insert_many(products, ordered=True)
            mongo[args.mongo_db]["counters"].update_one(
                {"_id": "product_id"}, {"$set": {"seq": len(products)}}, upsert=True)
    finally:
        conn.close()
        mongo.close()

    # ----- Résumé -----
    by_cat = {}
    for p in products:
        by_cat[p["category"]] = by_cat.get(p["category"], 0) + 1
    print("Données générées :")
    print(f"  MongoDB    catalog        {len(products):>9}  {by_cat}")
    print(f"  PostgreSQL customers      {len(customer_rows):>9}")
    print(f"             orders         {counts['orders']:>9}  {dict(sorted(status_count.items()))}")
    print(f"             order_items    {counts['items']:>9}")
    print(f"             payments       {counts['payments']:>9}")
    print(f"             reservations   {counts['reservations']:>9}")
    print(f"             shipments      {counts['shipments']:>9}")
    print(f"             idempotency    {counts['idem_keys']:>9}")
    print(f"             warehouses     {len(warehouses):>9}")
    print(f"             stock          {len(stock_rows):>9}  (dont {sum(1 for r in stock_rows if r[2] == 0)} à zéro)")
    print(f"             users / roles  {len(user_rows):>5} / {len(role_id)}   api_keys {len(api_key_rows)}")
    print(f"  Groupe en comeback (pic) : {comeback_group}")
    print(f"  Comptes de démo : {', '.join(e for e, _ in DEMO_ACCOUNTS)} (mot de passe : $DEMO_PASSWORD)")


if __name__ == "__main__":
    main()
