#!/usr/bin/env python3
import argparse
import hashlib
import os
import random
from datetime import datetime, timedelta, timezone
from itertools import accumulate

import psycopg2
from faker import Faker
from psycopg2.extras import execute_values
from pymongo import MongoClient

GROUPS = [
    "NOVA9", "LUNARIA", "STELLARBOYS", "MOONLIT", "ECLIPSE7", "HALOGIRLS",
    "AURORA", "KINETIC", "BLOOMY", "NEONCREW", "SEOULSTAR", "VELVETINE",
    "PRISM", "ZENITH", "CHERRYBOMB", "ORBIT", "TWILIGHT5", "MIRAGE",
    "SUNDAY", "COMETS", "LUMEN", "DAYBREAK", "WILDFIRE", "STARDUST"
]
COULEURS = ["noir", "blanc", "gris", "rose", "bleu", "violet", "beige"]
ALBUM_A = ["Neon", "Midnight", "First", "Sweet", "Blue", "Paper", "Velvet", "Golden", "Silent", "Wild"]
ALBUM_B = ["Dreams", "Sun", "Light", "Chaos", "Hour", "Moon", "Rain", "Flower", "Echo", "Summer"]
ALBUM_VERSIONS = ["Photobook", "Jewel Case", "Kit", "Digipack", "Limited"]

# (type, prix min, prix max, fonction d'attributs)
MERCH = [
    ("Lightstick", 3500, 5900, lambda g: {"artist": g, "bluetooth": True}),
    ("Hoodie", 4500, 7500, lambda g: {"artist": g, "size": random.sample(["S", "M", "L", "XL"], k=3),
                                      "couleur": random.sample(COULEURS, k=2)}),
    ("T-shirt", 2500, 4500, lambda g: {"artist": g, "size": random.sample(["S", "M", "L", "XL"], k=3),
                                       "couleur": random.sample(COULEURS, k=2)}),
    ("Tote bag", 1500, 2900, lambda g: {"artist": g, "matiere": "coton"}),
    ("Poster set", 1200, 2200, lambda g: {"artist": g, "format": "A2", "pieces": random.choice([3, 5, 8])}),
    ("Porte-clés", 800, 1600, lambda g: {"artist": g, "matiere": random.choice(["acrylique", "métal"])}),
    ("Porte-photocards", 700, 1500, lambda g: {"artist": g, "couleur": random.choice(COULEURS)}),
]

BOOK_TEMPLATES = [
    ("Apprendre le coréen - Niveau {n}", ["langue", "coréen"]),
    ("Hangul en 30 jours - Volume {n}", ["langue", "hangul"]),
    ("Cuisine coréenne : {x} recettes", ["cuisine", "corée"]),
    ("Voyage à Séoul : le guide {y}", ["voyage", "séoul"]),
    ("Histoire de la Corée - Tome {n}", ["histoire", "corée"]),
    ("Dans les coulisses de la K-pop - Tome {n}", ["k-pop", "essai"]),
    ("Manhwa Sakura Night - Tome {n}", ["manhwa", "romance"]),
    ("Manhwa Seoul Academy - Tome {n}", ["manhwa", "school"]),
]

ROLES = [
    ("admin", "Accès complet"),
    ("bi", "Équipe BI : lecture des agrégats"),
    ("marketing", "Équipe marketing : clients et commandes"),
    ("logistics", "Équipe logistique : stocks et expéditions"),
    ("support", "Support client"),
]


def parse_args():
    p = argparse.ArgumentParser(description="Génère des données Market K-store (PostgreSQL + MongoDB)")
    p.add_argument("--pg-dsn", default=os.getenv("PG_DSN", "postgresql://postgres:postgres@localhost:5432/market"))
    p.add_argument("--mongo-uri", default=os.getenv("MONGO_URI", "mongodb://localhost:27017/market"))
    p.add_argument("--mongo-db", default=os.getenv("MONGO_DB", "market"))
    p.add_argument("--customers", type=int, default=1000)
    p.add_argument("--products", type=int, default=300)
    p.add_argument("--warehouses", type=int, default=4)
    p.add_argument("--orders", type=int, default=10000)
    p.add_argument("--users", type=int, default=20, help="Comptes internes (schéma auth)")
    p.add_argument("--days", type=int, default=90, help="Historique couvert, en jours")
    p.add_argument("--peak-share", type=float, default=0.2,
                   help="Part des commandes concentrées sur le pic (comeback)")
    p.add_argument("--fraud-bursts", type=int, default=3, help="Nombre de rafales de fraude injectées")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--reset", action="store_true", help="Vide toutes les tables avant insertion")
    return p.parse_args()


def fake_hash(password: str) -> str:
    salt = os.urandom(8).hex()
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 10_000).hex()
    return f"pbkdf2_sha256$10000${salt}${digest}"


def psych(price: int) -> int:
    """Prix psychologique : finit par 90 centimes."""
    return (price // 100) * 100 + 90


def build_catalog(n, fake, now):
    """Retourne (produits, poids de popularité, groupe par produit, groupe en comeback)."""
    groups = GROUPS[:]
    random.shuffle(groups)
    group_w = {g: 1 / (i + 1) for i, g in enumerate(groups)}   # loi de puissance
    comeback_group = groups[2]
    group_list = list(group_w)
    group_weights = [group_w[g] for g in group_list]

    products, weight, group_of = [], {}, {}

    def add(category, name, price, tags, description, attributes, w, group=None):
        pid = len(products) + 1
        products.append({
            "product_id": pid,
            "name": name,
            "category": category,
            "price_cents": price,
            "status": "active" if random.random() < 0.95 else "inactive",
            "tags": tags,
            "description": description,
            "attributes": attributes,
            "indexed_at": now,
        })
        weight[pid] = w
        if group:
            group_of[pid] = group

    n_books = int(n * 0.15)
    n_merch = int(n * 0.30)
    n_albums = n - n_books - n_merch

    for _ in range(n_albums):
        g = random.choices(group_list, weights=group_weights)[0]
        version = random.choice(ALBUM_VERSIONS)
        title = f"{random.choice(ALBUM_A)} {random.choice(ALBUM_B)}"
        add("Albums", f"{g} - {title} ({version})",
            psych(random.randint(2200, 4200)),
            ["k-pop", "album", g.lower()],
            fake.sentence(nb_words=12),
            {"artist": g, "version": version, "photocards": random.randint(1, 6),
             "limited_edition": version == "Limited",
             "release_date": (now - timedelta(days=random.randint(0, 900))).strftime("%Y-%m-%d")},
            group_w[g] * random.uniform(0.6, 1.4), group=g)

    for _ in range(n_merch):
        g = random.choices(group_list, weights=group_weights)[0]
        kind, pmin, pmax, attr_fn = random.choice(MERCH)
        add("Merch", f"{g} {kind} Ver.{random.randint(1, 3)}",
            psych(random.randint(pmin, pmax)),
            ["k-pop", "merch", g.lower()],
            fake.sentence(nb_words=12),
            attr_fn(g),
            group_w[g] * random.uniform(0.6, 1.4), group=g)

    for _ in range(n_books):
        tpl, tags = random.choice(BOOK_TEMPLATES)
        name = tpl.format(n=random.randint(1, 6), x=random.choice([50, 80, 100]), y=random.choice([2025, 2026]))
        add("Livres", name, psych(random.randint(1200, 3200)), ["livre"] + tags,
            fake.sentence(nb_words=12),
            {"isbn": "978" + "".join(str(random.randint(0, 9)) for _ in range(10)),
             "langue": random.choice(["fr", "fr", "en", "ko"]),
             "pages": random.randint(96, 420),
             "auteur": fake.name()},
            0.08 * random.uniform(0.5, 1.5))

    return products, weight, group_of, comeback_group


def build_stock(products, warehouse_ids):
    rows = []
    for p in products:
        limited = p["attributes"].get("limited_edition", False)
        chosen = [w for w in warehouse_ids if random.random() < 0.7] or [random.choice(warehouse_ids)]
        for w in chosen:
            r = random.random()
            if r < 0.05:
                qty = 0                      # rupture
            elif r < 0.15:
                qty = random.randint(1, 5)   # stock bas
            elif limited:
                qty = random.randint(5, 40)  # édition limitée
            else:
                qty = random.randint(10, 500)
            rows.append((p["product_id"], w, qty))
    return rows


def order_status_for(ts, now):
    if now - ts < timedelta(hours=2):
        return random.choices(["pending", "paid"], weights=[60, 40])[0]
    return random.choices(["paid", "shipped", "delivered", "cancelled", "pending"],
                          weights=[8, 17, 60, 10, 5])[0]


def payment_for(order):
    """Retourne (status, amount) du paiement associé, ou None."""
    s = order["status"]
    if s in ("paid", "shipped", "delivered"):
        return "succeeded", order["total_cents"]
    if s == "cancelled":
        return random.choices(["failed", "refunded"], weights=[70, 30])[0], order["total_cents"]
    return ("pending", order["total_cents"]) if random.random() < 0.5 else None


def build_orders(args, products, weight, group_of, comeback_group, now):
    price = {p["product_id"]: p["price_cents"] for p in products}
    active = [p["product_id"] for p in products if p["status"] == "active"]

    cum_base = list(accumulate(weight[pid] for pid in active))
    cum_peak = list(accumulate(weight[pid] * (30 if group_of.get(pid) == comeback_group else 1)
                               for pid in active))

    cheap_ids = [p["product_id"] for p in
                 sorted((p for p in products if p["status"] == "active"),
                        key=lambda x: x["price_cents"])[:5]]

    peak_start = (now - timedelta(days=14)).replace(hour=18, minute=0, second=0, microsecond=0)
    peak_end = peak_start + timedelta(hours=6)

    def pick(k, cum):
        chosen, guard = set(), 0
        while len(chosen) < k and guard < 50:
            chosen.add(random.choices(active, cum_weights=cum)[0])
            guard += 1
        return list(chosen)

    def make_order(customer_id, ts, status, product_ids):
        items = [{"product_id": pid,
                  "quantity": random.randint(1, 4),
                  "unit_price_cents": price[pid]} for pid in product_ids]
        return {
            "customer_id": customer_id,
            "status": status,
            "total_cents": sum(i["quantity"] * i["unit_price_cents"] for i in items),
            "created_at": ts,
            "items": items,
            "force_payment": None,
        }

    orders = []

    # Trafic normal + pic « comeback »
    for _ in range(args.orders):
        if random.random() < args.peak_share:
            ts = peak_start + timedelta(seconds=random.randint(0, 6 * 3600))
        else:
            ts = now - timedelta(seconds=random.randint(0, args.days * 86400))
        in_peak = peak_start <= ts < peak_end
        cum = cum_peak if (in_peak and random.random() < 0.7) else cum_base
        orders.append(make_order(random.randint(1, args.customers), ts,
                                 order_status_for(ts, now), pick(random.randint(1, 5), cum)))

    # Rafales de fraude : même client, petits montants, paiements refusés, quelques secondes d'écart
    for _ in range(args.fraud_bursts):
        cid = random.randint(1, args.customers)
        start = now - timedelta(days=random.randint(1, 30), seconds=random.randint(0, 80000))
        for k in range(random.randint(12, 20)):
            ts = start + timedelta(seconds=k * random.randint(3, 9))
            o = make_order(cid, ts, "cancelled", [random.choice(cheap_ids)])
            o["force_payment"] = ("failed", o["total_cents"])
            orders.append(o)

    orders.sort(key=lambda o: o["created_at"])
    for i, o in enumerate(orders, start=1):
        o["id"] = i
    return orders


def main():
    args = parse_args()
    random.seed(args.seed)
    Faker.seed(args.seed)
    fake = Faker("fr_FR")
    now = datetime.now(timezone.utc)

    # ----- Génération en mémoire -----
    products, weight, group_of, comeback_group = build_catalog(args.products, fake, now)
    warehouse_rows = [(i, f"Entrepôt {fake.city()}", random.choice([5000, 10000, 20000, 50000]))
                      for i in range(1, args.warehouses + 1)]
    stock_rows = build_stock(products, [w[0] for w in warehouse_rows])
    customer_rows = [(i, fake.name(), fake.unique.email()) for i in range(1, args.customers + 1)]
    orders = build_orders(args, products, weight, group_of, comeback_group, now)

    order_rows, item_rows, payment_rows = [], [], []
    item_id, pay_id = 1, 1
    for o in orders:
        order_rows.append((o["id"], o["customer_id"], o["status"], o["total_cents"], o["created_at"]))
        for it in o["items"]:
            item_rows.append((item_id, o["id"], it["product_id"], it["quantity"], it["unit_price_cents"]))
            item_id += 1
        pay = o["force_payment"] or payment_for(o)
        if pay:
            pay_ts = o["created_at"] + timedelta(seconds=random.randint(1, 120))
            payment_rows.append((pay_id, o["id"], pay[1], pay[0], pay_ts))
            pay_id += 1

    role_rows = [(i, name, desc) for i, (name, desc) in enumerate(ROLES, start=1)]
    user_rows, user_role_rows = [], []
    for uid in range(1, args.users + 1):
        user_rows.append((uid, fake.unique.company_email(), fake_hash("demo1234"),
                          random.random() < 0.9, now - timedelta(days=random.randint(0, 365))))
        for rid in random.sample([r[0] for r in role_rows], k=random.randint(1, 2)):
            user_role_rows.append((uid, rid))

    # ----- MongoDB -----
    mongo = MongoClient(args.mongo_uri)
    catalog = mongo[args.mongo_db]["catalog"]
    if args.reset:
        catalog.delete_many({})
    catalog.insert_many(products)

    # ----- PostgreSQL (une seule transaction) -----
    conn = psycopg2.connect(args.pg_dsn)
    try:
        with conn, conn.cursor() as cur:
            if args.reset:
                cur.execute("""
                    TRUNCATE orders.order_items, orders.orders, orders.customers,
                             payments.payments,
                             inventory.stock, inventory.warehouses,
                             auth.user_roles, auth.users, auth.roles
                    RESTART IDENTITY CASCADE
                """)
            ins = lambda sql, rows: execute_values(cur, sql, rows, page_size=5000)
            ins("INSERT INTO orders.customers (id, name, email) VALUES %s", customer_rows)
            ins("INSERT INTO orders.orders (id, customer_id, status, total_cents, created_at) VALUES %s", order_rows)
            ins("INSERT INTO orders.order_items (id, order_id, product_id, quantity, unit_price_cents) VALUES %s", item_rows)
            ins("INSERT INTO payments.payments (id, order_id, amount_cents, status, created_at) VALUES %s", payment_rows)
            ins("INSERT INTO inventory.warehouses (id, name, capacity_units) VALUES %s", warehouse_rows)
            ins("INSERT INTO inventory.stock (product_id, warehouse_id, quantity) VALUES %s", stock_rows)
            ins("INSERT INTO auth.roles (id, name, description) VALUES %s", role_rows)
            ins("INSERT INTO auth.users (id, email, password_hash, is_active, created_at) VALUES %s", user_rows)
            ins("INSERT INTO auth.user_roles (user_id, role_id) VALUES %s", user_role_rows)

            for table in ["orders.customers", "orders.orders", "orders.order_items", "payments.payments",
                          "inventory.warehouses", "auth.users", "auth.roles"]:
                cur.execute(f"SELECT setval(pg_get_serial_sequence('{table}', 'id'), "
                            f"COALESCE((SELECT MAX(id) FROM {table}), 1))")
    finally:
        conn.close()

    by_cat = {}
    for p in products:
        by_cat[p["category"]] = by_cat.get(p["category"], 0) + 1
    print("Données générées :")
    print(f"  MongoDB    catalog           {len(products):>8}  {by_cat}")
    print(f"  PostgreSQL customers         {len(customer_rows):>8}")
    print(f"             orders            {len(order_rows):>8}")
    print(f"             order_items       {len(item_rows):>8}")
    print(f"             payments          {len(payment_rows):>8}")
    print(f"             warehouses        {len(warehouse_rows):>8}")
    print(f"             stock             {len(stock_rows):>8}  (dont {sum(1 for r in stock_rows if r[2] == 0)} ruptures)")
    print(f"             users / roles     {len(user_rows):>4} / {len(role_rows)}")
    print(f"  Groupe en comeback (pic)  : {comeback_group}")
    print(f"  Rafales de fraude injectées : {args.fraud_bursts}")


if __name__ == "__main__":
    main()