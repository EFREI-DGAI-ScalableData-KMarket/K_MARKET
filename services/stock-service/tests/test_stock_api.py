"""Tests d'intégration du service Stock (contrat : contracts/inventory.openapi.yaml).

Prérequis : pile lancée (`make up`) et seed terminé. Lancement : `make test-stock`.
Les appels passent par la gateway ; seul /health est appelé en direct (non routé par Traefik).
Rejouable sans `make reseed` : order_id aléatoires, réservations libérées en fin de test.
Traces laissées : des expéditions et des lignes de stock à 0 pour des ids de test (>= 900 000 000).

Variables : JWT_SECRET (obligatoire), STOCK_BASE_URL, STOCK_DIRECT_URL.
"""
import os
import random
import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import jwt
import pytest

BASE = os.getenv("STOCK_BASE_URL", "http://localhost:8000/v1")      # via la gateway
DIRECT = os.getenv("STOCK_DIRECT_URL", "http://localhost:8002/v1")  # service en direct
SECRET = os.environ["JWT_SECRET"]

TEST_ID_MIN = 900_000_000          # order_id / product_id de test, hors plage du seed (int4 < 2 147 483 647)


def new_id() -> int:
    return random.randint(TEST_ID_MIN, 2_000_000_000)


def token(*roles: str, expires_in: int = 600) -> dict:
    claims = {"iss": "kmarket-auth", "sub": "tests", "roles": list(roles), "exp": int(time.time()) + expires_in}
    return {"Authorization": "Bearer " + jwt.encode(claims, SECRET, algorithm="HS256")}


SERVICE = token("service_account")
LOGISTICS = token("logistics")
ADMIN = token("admin")


def assert_error(r: httpx.Response, status: int, code: str):
    """Toute erreur respecte le schéma `Error` du contrat."""
    assert r.status_code == status, r.text
    body = r.json()
    assert body["code"] == code
    assert body["message"]
    assert body["request_id"]


#  fixtures
@pytest.fixture(scope="session")
def api():
    with httpx.Client(base_url=BASE, timeout=10) as client:
        yield client


def total_stock(api, product_id: int) -> int:
    return api.get(f"/stock/{product_id}", headers=SERVICE).json()["total_quantity"]


def warehouse_qty(api, product_id: int) -> dict[int, int]:
    rows = api.get(f"/stock/{product_id}", headers=SERVICE).json()["by_warehouse"]
    return {r["warehouse_id"]: r["quantity"] for r in rows}


def find_product(api, predicate, exclude=()) -> int:
    """Premier produit du seed (ids 1..300) dont le stock par entrepôt vérifie `predicate`."""
    for pid in range(1, 301):
        if pid in exclude:
            continue
        r = api.get(f"/stock/{pid}", headers=SERVICE)
        if r.status_code == 200 and predicate(warehouse_qty(api, pid)):
            return pid
    pytest.skip("aucun produit du seed ne convient (seed lancé ?)")


@pytest.fixture(scope="session")
def stocked_product(api) -> int:
    """Produit avec au moins 20 unités dans un entrepôt."""
    return find_product(api, lambda q: max(q.values(), default=0) >= 20)


@pytest.fixture(scope="session")
def second_product(api, stocked_product) -> int:
    """Autre produit, avec au moins 5 unités dans un entrepôt."""
    return find_product(api, lambda q: max(q.values(), default=0) >= 5, exclude={stocked_product})


@pytest.fixture(scope="session")
def warehouse_id(api) -> int:
    return api.get("/warehouses", headers=LOGISTICS).json()[0]["id"]


#  santé et sécurité
def test_health_sans_jeton():
    r = httpx.get(f"{DIRECT}/health", timeout=5)
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "version": "v1"}


def test_401_sans_jeton(api, stocked_product):
    assert_error(api.get(f"/stock/{stocked_product}"), 401, "UNAUTHORIZED")


def test_401_jeton_mal_signe(api, stocked_product):
    bad = {"Authorization": "Bearer " + jwt.encode({"roles": ["admin"], "exp": int(time.time()) + 60},
                                                   "mauvais-secret-de-test-assez-long", algorithm="HS256")}
    assert_error(api.get(f"/stock/{stocked_product}", headers=bad), 401, "UNAUTHORIZED")


def test_401_jeton_expire(api, stocked_product):
    assert_error(api.get(f"/stock/{stocked_product}", headers=token("admin", expires_in=-10)), 401, "UNAUTHORIZED")


def test_403_role_insuffisant(api, stocked_product):
    assert_error(api.get("/warehouses", headers=SERVICE), 403, "FORBIDDEN")           # service_account : pas les entrepôts
    assert_error(api.get("/warehouses", headers=token("catalog_manager")), 403, "FORBIDDEN")
    assert_error(api.post("/stock/reserve", headers=LOGISTICS,
                          json={"order_id": new_id(), "items": [{"product_id": 1, "quantity": 1}]}), 403, "FORBIDDEN")
    assert_error(api.put(f"/stock/{stocked_product}/warehouses/1", headers=token("analyst"),
                         json={"quantity": 1}), 403, "FORBIDDEN")                  # analyst : lecture seule
    assert_error(api.put(f"/stock/{stocked_product}/warehouses/1", headers=token("catalog_manager"),
                         json={"quantity": 1}), 403, "FORBIDDEN")


def test_matrice_des_droits_lecture(api, stocked_product):
    """Matrice du sujet : catalog_manager et analyst lisent les stocks ; analyst lit les entrepôts."""
    for role in ("admin", "logistics", "analyst", "catalog_manager", "service_account"):
        assert api.get(f"/stock/{stocked_product}", headers=token(role)).status_code == 200, role
    for role in ("admin", "logistics", "analyst"):
        assert api.get("/warehouses", headers=token(role)).status_code == 200, role


#  entrepôts
def test_warehouses(api):
    r = api.get("/warehouses", headers=LOGISTICS)
    assert r.status_code == 200
    warehouses = r.json()
    assert isinstance(warehouses, list) and warehouses
    for w in warehouses:
        assert set(w) == {"id", "name", "capacity_units", "used_units", "fill_rate", "status"}
        assert w["fill_rate"] == pytest.approx(w["used_units"] / w["capacity_units"], abs=1e-3)
        assert w["status"] == ("near_full" if w["used_units"] / w["capacity_units"] > 0.90 else "normal")
    assert any(w["status"] == "near_full" for w in warehouses), "le seed prévoit un entrepôt au-dessus de 90 %"


#  lecture du stock
def test_stock_summary(api, stocked_product):
    r = api.get(f"/stock/{stocked_product}", headers=SERVICE)
    assert r.status_code == 200
    body = r.json()
    assert body["product_id"] == stocked_product
    assert body["total_quantity"] == sum(i["quantity"] for i in body["by_warehouse"])
    assert all(i["warehouse_name"] for i in body["by_warehouse"])


def test_stock_produit_inconnu(api):
    assert_error(api.get(f"/stock/{new_id()}", headers=SERVICE), 404, "NOT_FOUND")


def test_stock_par_entrepot(api, stocked_product):
    wid, qty = next(iter(warehouse_qty(api, stocked_product).items()))
    r = api.get(f"/stock/{stocked_product}/warehouses/{wid}", headers=LOGISTICS)
    assert r.status_code == 200
    assert r.json()["quantity"] == qty
    assert_error(api.get(f"/stock/{stocked_product}/warehouses/999999", headers=LOGISTICS), 404, "NOT_FOUND")


#  mise à jour du stock (réapprovisionnement)
def test_mise_a_jour_du_stock(api, stocked_product):
    wid, original = next(iter(warehouse_qty(api, stocked_product).items()))
    try:
        r = api.put(f"/stock/{stocked_product}/warehouses/{wid}", headers=LOGISTICS, json={"quantity": original + 7})
        assert r.status_code == 200 and r.json()["quantity"] == original + 7
        # idempotent : même valeur, même résultat
        r = api.put(f"/stock/{stocked_product}/warehouses/{wid}", headers=token("service_account"),
                    json={"quantity": original + 7})
        assert r.status_code == 200 and r.json()["quantity"] == original + 7
    finally:
        api.put(f"/stock/{stocked_product}/warehouses/{wid}", headers=ADMIN, json={"quantity": original})
    assert warehouse_qty(api, stocked_product)[wid] == original


def test_mise_a_jour_du_stock_erreurs(api, stocked_product):
    assert_error(api.put(f"/stock/{new_id()}/warehouses/1", headers=LOGISTICS, json={"quantity": 5}), 404, "NOT_FOUND")
    assert_error(api.put(f"/stock/{stocked_product}/warehouses/1", headers=LOGISTICS, json={"quantity": -1}),
                 400, "BAD_REQUEST")


#  création du stock
def test_create_stock(api, warehouse_id):
    pid = new_id()
    body = {"product_id": pid, "warehouse_id": warehouse_id, "quantity": 0}   # 0 : n'altère pas le remplissage
    r = api.post("/stock", headers=SERVICE, json=body)
    assert r.status_code == 201, r.text
    assert r.json()["product_id"] == pid and r.json()["quantity"] == 0
    assert_error(api.post("/stock", headers=SERVICE, json=body), 409, "CONFLICT")


def test_create_stock_entrepot_inconnu(api):
    assert_error(api.post("/stock", headers=SERVICE,
                          json={"product_id": new_id(), "warehouse_id": 999999, "quantity": 1}), 404, "NOT_FOUND")


def test_create_stock_corps_invalide(api, warehouse_id):
    assert_error(api.post("/stock", headers=SERVICE, json={"product_id": new_id(), "quantity": 1}),
                 400, "BAD_REQUEST")                                                  # warehouse_id manquant
    assert_error(api.post("/stock", headers=SERVICE,
                          json={"product_id": new_id(), "warehouse_id": warehouse_id, "quantity": -1}),
                 400, "BAD_REQUEST")


#  Saga : reserve / commit / release
def test_cycle_reserve_commit_release(api, stocked_product):
    order_id, pid = new_id(), stocked_product
    before = total_stock(api, pid)
    payload = {"order_id": order_id, "items": [{"product_id": pid, "quantity": 2},
                                               {"product_id": pid, "quantity": 1}]}   # même produit : fusionné

    r = api.post("/stock/reserve", headers=SERVICE, json=payload)
    assert r.status_code == 201, r.text
    reservations = r.json()["reservations"]
    assert len(reservations) == 1 and reservations[0]["quantity"] == 3
    assert reservations[0]["status"] == "reserved"
    assert total_stock(api, pid) == before - 3

    # rejeu : même réponse, pas de nouvelle décrémentation
    r2 = api.post("/stock/reserve", headers=SERVICE, json=payload)
    assert r2.status_code == 201
    assert r2.json()["reservations"][0]["id"] == reservations[0]["id"]
    assert total_stock(api, pid) == before - 3

    r = api.post("/stock/commit", headers=SERVICE, json={"order_id": order_id})
    assert r.status_code == 200
    assert {x["status"] for x in r.json()["reservations"]} == {"committed"}

    r = api.post("/stock/release", headers=SERVICE, json={"order_id": order_id})
    assert r.status_code == 200
    assert {x["status"] for x in r.json()["reservations"]} == {"released"}
    assert total_stock(api, pid) == before

    # release rejoué : le stock n'est pas rendu deux fois
    assert api.post("/stock/release", headers=SERVICE, json={"order_id": order_id}).status_code == 200
    assert total_stock(api, pid) == before

    # commit après libération : interdit
    assert_error(api.post("/stock/commit", headers=SERVICE, json={"order_id": order_id}), 409, "CONFLICT")


def test_release_une_ligne(api, stocked_product, second_product):
    order_id = new_id()
    items = [{"product_id": stocked_product, "quantity": 1}, {"product_id": second_product, "quantity": 1}]
    assert api.post("/stock/reserve", headers=SERVICE, json={"order_id": order_id, "items": items}).status_code == 201
    try:
        r = api.post("/stock/release", headers=SERVICE, json={"order_id": order_id, "product_id": second_product})
        assert r.status_code == 200
        assert [(x["product_id"], x["status"]) for x in r.json()["reservations"]] == [(second_product, "released")]
        r = api.post("/stock/commit", headers=SERVICE, json={"order_id": order_id})
        assert_error(r, 409, "CONFLICT")                                         # une ligne libérée : pas de commit
    finally:
        api.post("/stock/release", headers=SERVICE, json={"order_id": order_id})


def test_reserve_stock_insuffisant_tout_ou_rien(api, stocked_product, second_product):
    order_id = new_id()
    before = total_stock(api, stocked_product)
    r = api.post("/stock/reserve", headers=SERVICE, json={"order_id": order_id, "items": [
        {"product_id": stocked_product, "quantity": 1},          # servable
        {"product_id": second_product, "quantity": 10_000_000},  # pas servable
    ]})
    assert_error(r, 422, "INSUFFICIENT_STOCK")
    assert r.json()["details"] == [{"product_id": second_product, "requested": 10_000_000,
                                    "available": max(warehouse_qty(api, second_product).values())}]
    assert total_stock(api, stocked_product) == before                       # la ligne servable est annulée aussi
    assert_error(api.post("/stock/release", headers=SERVICE, json={"order_id": order_id}), 404, "NOT_FOUND")


def test_reserve_corps_invalide(api, stocked_product):
    assert_error(api.post("/stock/reserve", headers=SERVICE, json={
        "order_id": new_id(), "items": [{"product_id": stocked_product, "quantity": 0}]}), 400, "BAD_REQUEST")
    assert_error(api.post("/stock/reserve", headers=SERVICE, json={"order_id": new_id(), "items": []}),
                 400, "BAD_REQUEST")


def test_commit_release_commande_inconnue(api):
    assert_error(api.post("/stock/commit", headers=SERVICE, json={"order_id": new_id()}), 404, "NOT_FOUND")
    assert_error(api.post("/stock/release", headers=SERVICE, json={"order_id": new_id()}), 404, "NOT_FOUND")


def test_reserve_concurrent_sans_survente(api):
    """Plusieurs commandes simultanées visent tout le stock d'un produit : aucune survente."""
    pid = find_product(api, lambda q: 2 <= max(q.values(), default=0) <= 30)
    before = warehouse_qty(api, pid)
    q = max(before.values())                                    # chaque commande veut tout un entrepôt
    orders = [new_id() for _ in range(8)]

    def reserve(order_id):
        with httpx.Client(base_url=BASE, timeout=10) as c:
            return c.post("/stock/reserve", headers=SERVICE,
                          json={"order_id": order_id, "items": [{"product_id": pid, "quantity": q}]}).status_code

    with ThreadPoolExecutor(len(orders)) as pool:
        codes = list(pool.map(reserve, orders))
    try:
        after = warehouse_qty(api, pid)
        assert set(codes) <= {201, 422}, codes
        assert all(v >= 0 for v in after.values())
        assert sum(before.values()) - sum(after.values()) == codes.count(201) * q
        assert codes.count(201) >= 1
    finally:
        for order_id, code in zip(orders, codes):
            if code == 201:
                api.post("/stock/release", headers=SERVICE, json={"order_id": order_id})
    assert warehouse_qty(api, pid) == before


#  expéditions
def test_cycle_expedition(api, warehouse_id):
    order_id = new_id()
    r = api.post("/shipments", headers=LOGISTICS, json={"order_id": order_id, "warehouse_id": warehouse_id})
    assert r.status_code == 201, r.text
    shipment = r.json()
    assert shipment["status"] == "preparing" and shipment["shipped_at"] is None
    sid = shipment["id"]

    assert_error(api.post("/shipments", headers=LOGISTICS, json={"order_id": order_id, "warehouse_id": warehouse_id}),
                 409, "CONFLICT")
    assert_error(api.patch(f"/shipments/{sid}/status", headers=LOGISTICS, json={"status": "delivered"}),
                 409, "CONFLICT")                                                   # on ne saute pas « shipped »

    r = api.patch(f"/shipments/{sid}/status", headers=LOGISTICS, json={"status": "shipped"})
    assert r.status_code == 200 and r.json()["shipped_at"] is not None

    assert api.patch(f"/shipments/{sid}/status", headers=LOGISTICS, json={"status": "shipped"}).status_code == 200
    assert_error(api.patch(f"/shipments/{sid}/status", headers=LOGISTICS, json={"status": "preparing"}),
                 409, "CONFLICT")                                                   # pas de retour arrière

    r = api.patch(f"/shipments/{sid}/status", headers=ADMIN, json={"status": "delivered"})
    assert r.status_code == 200 and r.json()["delivered_at"] is not None


def test_expedition_erreurs(api):
    assert_error(api.post("/shipments", headers=LOGISTICS, json={"order_id": new_id(), "warehouse_id": 999999}),
                 404, "NOT_FOUND")
    assert_error(api.patch("/shipments/999999999/status", headers=LOGISTICS, json={"status": "shipped"}),
                 404, "NOT_FOUND")
    assert_error(api.patch("/shipments/1/status", headers=LOGISTICS, json={"status": "perdu"}),
                 400, "BAD_REQUEST")


def test_liste_expeditions_filtre_et_pagination(api):
    r = api.get("/shipments", headers=LOGISTICS, params={"status": "delivered", "limit": 5})
    assert r.status_code == 200
    page = r.json()
    assert len(page["items"]) <= 5 and page["total"] >= len(page["items"])
    assert all(s["status"] == "delivered" for s in page["items"])
    dates = [s["created_at"] for s in page["items"]]
    assert dates == sorted(dates, reverse=True)                                      # plus récentes d'abord

    page2 = api.get("/shipments", headers=LOGISTICS, params={"status": "delivered", "limit": 5, "offset": 5}).json()
    assert {s["id"] for s in page["items"]}.isdisjoint({s["id"] for s in page2["items"]})

    assert_error(api.get("/shipments", headers=LOGISTICS, params={"limit": 500}), 400, "BAD_REQUEST")


#  métriques
def test_metriques_prometheus(api):
    api.get("/warehouses", headers=LOGISTICS)                           # au moins une requête comptée
    r = httpx.get(DIRECT.rsplit("/v1", 1)[0] + "/metrics", timeout=5)
    assert r.status_code == 200
    assert "http_requests_total" in r.text and "http_request_duration_seconds" in r.text


#  lecture groupée, stocks bas, noms d'entrepôts
def test_lecture_groupee(api, stocked_product, second_product):
    unknown = new_id()
    r = api.get("/stock", headers=token("catalog_manager"),
                params={"product_ids": f"{second_product},{stocked_product},{unknown},{second_product}"})
    assert r.status_code == 200
    body = r.json()
    assert [s["product_id"] for s in body] == [second_product, stocked_product]     # ordre demandé, sans doublon
    assert body[1]["total_quantity"] == total_stock(api, stocked_product)
    assert_error(api.get("/stock", headers=SERVICE, params={"product_ids": "1,abc"}), 400, "BAD_REQUEST")
    assert_error(api.get("/stock", headers=SERVICE,
                         params={"product_ids": ",".join(map(str, range(1, 202)))}), 400, "BAD_REQUEST")


def test_stocks_bas(api):
    r = api.get("/stock/low", headers=token("analyst"), params={"threshold": 5, "limit": 10})
    assert r.status_code == 200
    totals = [s["total_quantity"] for s in r.json()]
    assert totals and len(totals) <= 10
    assert all(t <= 5 for t in totals) and totals == sorted(totals)            # du plus bas au plus haut


def test_noms_des_entrepots(api):
    r = api.get("/warehouses/names", headers=token("catalog_manager"))
    assert r.status_code == 200
    assert all(set(w) == {"id", "name"} for w in r.json()) and r.json()
    assert_error(api.get("/warehouses", headers=token("catalog_manager")), 403, "FORBIDDEN")   # remplissage : non
    assert_error(api.get("/warehouses/names", headers=SERVICE), 403, "FORBIDDEN")