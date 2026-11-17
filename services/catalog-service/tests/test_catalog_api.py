"""Tests d'intégration du service Catalogue (contrat : contracts/catalog.graphql).

Prérequis : pile lancée (`make up`) et seed terminé. Lancement : `make test-catalog`.
Les appels passent par la gateway ; seul /health est appelé en direct (non routé par Traefik).
Traces laissées : quelques produits de test (tag "pytest"), archivés en fin de test.

Variables : JWT_SECRET (obligatoire), CATALOG_URL, CATALOG_DIRECT_URL.
"""
import os
import time
import uuid

import httpx
import jwt
import pytest

URL = os.getenv("CATALOG_URL", "http://localhost:8000/v1/catalog")            # via la gateway
DIRECT = os.getenv("CATALOG_DIRECT_URL", "http://localhost:8004/v1")          # service en direct
SECRET = os.environ["JWT_SECRET"]

# `type` existe dans trois catégories avec des types GraphQL différents : alias obligatoires (voir le contrat)
PRODUCT_FIELDS = """
  product_id name category price_cents tags description status indexed_at
  attributes {
    __typename
    ... on AlbumAttributes { artist version album_type: type release_date photocards limited_edition }
    ... on LightstickAttributes { artist version bluetooth piles }
    ... on MerchAttributes { artist merch_type: type size couleur matiere format pieces }
    ... on DramaAttributes { serie support saison episodes sous_titres }
    ... on LivreAttributes { isbn langue auteur pages genre }
    ... on BeauteAttributes { marque contenance_ml beaute_type: type type_peau ingredients }
  }
"""


def token(*roles: str, expires_in: int = 600) -> dict:
    claims = {"sub": "pytest", "roles": list(roles), "exp": int(time.time()) + expires_in}
    return {"Authorization": "Bearer " + jwt.encode(claims, SECRET, algorithm="HS256")}


MANAGER = token("catalog_manager")
ANALYST = token("analyst")
SERVICE = token("service_account")


def gql(query: str, variables: dict | None = None, headers: dict | None = MANAGER) -> dict:
    r = httpx.post(URL, json={"query": query, "variables": variables or {}}, headers=headers or {}, timeout=10)
    assert r.status_code == 200, r.text
    return r.json()


def ok(body: dict) -> dict:
    assert "errors" not in body, body["errors"]
    return body["data"]


def assert_error(body: dict, code: str):
    assert body.get("errors"), f"erreur {code} attendue, reçu : {body}"
    assert body["errors"][0]["extensions"]["code"] == code, body["errors"]


def isbn13(seed: int) -> str:
    body = "978" + f"{seed % 10**9:09d}"
    total = sum((1 if i % 2 == 0 else 3) * int(d) for i, d in enumerate(body))
    return body + str((10 - total % 10) % 10)


def livre_input(**overrides) -> dict:
    n = uuid.uuid4().int
    data = {"name": f"Test pytest {n % 10**6}", "category": "Livre", "price_cents": 1990, "tags": ["pytest"],
            "description": "Livre créé par les tests", "attributes": {"isbn": isbn13(n), "langue": "fr", "pages": 120}}
    data.update(overrides)
    return data


CREATE = f"mutation($input: ProductInput!, $stock: InitialStock) {{ createProduct(input: $input, initial_stock: $stock) {{ {PRODUCT_FIELDS} }} }}"
UPDATE = f"mutation($id: Int!, $input: ProductUpdateInput!) {{ updateProduct(product_id: $id, input: $input) {{ {PRODUCT_FIELDS} }} }}"
LIST = f"query($f: ProductFilter, $limit: Int!, $offset: Int!) {{ products(filter: $f, limit: $limit, offset: $offset) {{ total has_more items {{ {PRODUCT_FIELDS} }} }} }}"


def page(f=None, limit=50, offset=0, headers=MANAGER) -> dict:
    return ok(gql(LIST, {"f": f, "limit": limit, "offset": offset}, headers))["products"]


@pytest.fixture
def created():
    """Produits créés pendant un test : archivés à la fin pour ne pas polluer le catalogue actif."""
    ids = []
    yield ids
    for pid in ids:
        gql("mutation($id: Int!) { archiveProduct(product_id: $id) { status } }", {"id": pid})


#  santé et sécurité
def test_health():
    r = httpx.get(f"{DIRECT}/health", timeout=5)
    assert r.status_code == 200 and r.json() == {"status": "ok", "version": "v1"}


def test_sans_jeton():
    assert_error(gql("{ product(product_id: 1) { name } }", headers={}), "UNAUTHORIZED")


def test_jeton_expire_ou_mal_signe():
    assert_error(gql("{ product(product_id: 1) { name } }", headers=token("admin", expires_in=-10)), "UNAUTHORIZED")
    bad = {"Authorization": "Bearer " + jwt.encode({"roles": ["admin"], "exp": int(time.time()) + 60},
                                                   "mauvais-secret-de-test-assez-long", algorithm="HS256")}
    assert_error(gql("{ product(product_id: 1) { name } }", headers=bad), "UNAUTHORIZED")


def test_lecture_tous_roles_ecriture_reservee():
    for headers in (ANALYST, SERVICE, token("logistics"), token("admin")):
        assert ok(gql("{ product(product_id: 1) { product_id } }", headers=headers))["product"]["product_id"] == 1
    assert_error(gql(CREATE, {"input": livre_input()}, headers=ANALYST), "FORBIDDEN")
    assert_error(gql("mutation { archiveProduct(product_id: 1) { status } }", headers=SERVICE), "FORBIDDEN")


#  lecture
def test_tout_le_catalogue_respecte_le_schema():
    """Chaque document MongoDB se lit avec les types GraphQL (enums, union, champs obligatoires)."""
    seen, offset = 0, 0
    while True:
        p = page(limit=200, offset=offset)
        for item in p["items"]:
            assert item["attributes"]["__typename"] == f'{item["category"]}Attributes'
        seen += len(p["items"])
        offset += 200
        if not p["has_more"]:
            break
    assert seen == p["total"] >= 300


def test_pagination():
    p1, p2 = page(limit=5), page(limit=5, offset=5)
    assert len(p1["items"]) == 5 and p1["has_more"] and p1["total"] >= 300
    ids1, ids2 = [i["product_id"] for i in p1["items"]], [i["product_id"] for i in p2["items"]]
    assert ids1 == sorted(ids1) and max(ids1) < min(ids2)
    assert_error(gql(LIST, {"f": None, "limit": 500, "offset": 0}), "VALIDATION_ERROR")


@pytest.mark.parametrize("category", ["Album", "Lightstick", "Merch", "Drama", "Livre", "Beaute"])
def test_filtre_categorie(category):
    p = page({"category": category}, limit=200)
    assert p["total"] > 0
    assert all(i["category"] == category and i["attributes"]["__typename"] == f"{category}Attributes"
               for i in p["items"])


def test_filtre_statut_et_prix():
    inactive = page({"status": "inactive"}, limit=200)
    assert inactive["total"] > 0 and all(i["status"] == "inactive" for i in inactive["items"])
    cheap = page({"min_price_cents": 1000, "max_price_cents": 2000}, limit=200)
    assert cheap["total"] > 0 and all(1000 <= i["price_cents"] <= 2000 for i in cheap["items"])


def test_recherche_plein_texte():
    target = page({"category": "Beaute"}, limit=1)["items"][0]
    word = target["attributes"]["marque"].split()[0]
    found = page({"search": word}, limit=200)
    assert target["product_id"] in [i["product_id"] for i in found["items"]]


def test_product_et_product_inconnu():
    p = ok(gql(f"{{ product(product_id: 1) {{ {PRODUCT_FIELDS} }} }}"))["product"]
    assert p["product_id"] == 1 and p["name"]
    assert ok(gql("{ product(product_id: 999999999) { name } }"))["product"] is None


def test_products_by_ids():
    data = ok(gql("{ productsByIds(ids: [3, 1, 999999999, 1]) { product_id price_cents } }", headers=SERVICE))
    assert [p["product_id"] for p in data["productsByIds"]] == [3, 1]       # ordre demandé, inconnu ignoré, sans doublon


def test_drama_support_codes():
    supports = {i["attributes"]["support"] for i in page({"category": "Drama"}, limit=200)["items"]}
    assert supports <= {"DVD", "Blu_ray", "Coffret", "Artbook"}


#  écriture
def test_cycle_creation_modification_archivage(created):
    product = ok(gql(CREATE, {"input": livre_input()}))["createProduct"]
    created.append(pid := product["product_id"])
    assert product["status"] == "active" and product["attributes"]["__typename"] == "LivreAttributes"
    assert product["indexed_at"]

    # mise à jour partielle : seul le prix change
    updated = ok(gql(UPDATE, {"id": pid, "input": {"price_cents": 2490}}))["updateProduct"]
    assert updated["price_cents"] == 2490 and updated["name"] == product["name"]

    # description à null : champ retiré
    assert ok(gql(UPDATE, {"id": pid, "input": {"description": None}}))["updateProduct"]["description"] is None

    status = "mutation($id: Int!) { %s(product_id: $id) { status } }"
    assert ok(gql(status % "archiveProduct", {"id": pid}))["archiveProduct"]["status"] == "inactive"
    assert ok(gql(status % "reactivateProduct", {"id": pid}))["reactivateProduct"]["status"] == "active"


def test_validator_mongo_refuse_un_livre_sans_isbn():
    before = page(limit=1)["total"]
    bad = livre_input(attributes={"langue": "fr"})
    assert_error(gql(CREATE, {"input": bad}), "VALIDATION_ERROR")
    assert page(limit=1)["total"] == before


def test_validator_mongo_refuse_attributs_d_une_autre_categorie():
    bad = livre_input(category="Album")          # attributs de Livre pour un Album
    assert_error(gql(CREATE, {"input": bad}), "VALIDATION_ERROR")


def test_changer_de_categorie_sans_attributs_est_refuse(created):
    pid = ok(gql(CREATE, {"input": livre_input()}))["createProduct"]["product_id"]
    created.append(pid)
    assert_error(gql(UPDATE, {"id": pid, "input": {"category": "Drama"}}), "VALIDATION_ERROR")
    ok_change = {"category": "Drama", "attributes": {"serie": "Test", "support": "Blu_ray"}}
    assert ok(gql(UPDATE, {"id": pid, "input": ok_change}))["updateProduct"]["attributes"]["support"] == "Blu_ray"


def test_erreurs_de_saisie():
    assert_error(gql(CREATE, {"input": livre_input(price_cents=-1)}), "VALIDATION_ERROR")
    assert_error(gql(UPDATE, {"id": 999999999, "input": {"price_cents": 100}}), "NOT_FOUND")
    assert_error(gql(UPDATE, {"id": 1, "input": {"name": None}}), "VALIDATION_ERROR")
    assert_error(gql(UPDATE, {"id": 1, "input": {}}), "VALIDATION_ERROR")


def test_stock_initial_refuse_le_produit_est_supprime():
    """Compensation : si POST /stock échoue (ici entrepôt inexistant, ou auth-api absente), pas de produit orphelin."""
    before = page(limit=1)["total"]
    body = gql(CREATE, {"input": livre_input(), "stock": {"warehouse_id": 999999, "quantity": 10}})
    assert body.get("errors") and body["errors"][0]["extensions"]["code"] in {"NOT_FOUND", "UPSTREAM_ERROR"}
    assert page(limit=1)["total"] == before

def test_image_du_produit():
    p = ok(gql("{ product(product_id: 1) { name image_url } }"))["product"]
    assert p["image_url"] == "/v1/catalog/images/1.svg"
    r = httpx.get(URL.rsplit("/v1/catalog", 1)[0] + p["image_url"], timeout=5)    # sans jeton : public
    assert r.status_code == 200 and r.headers["content-type"].startswith("image/svg+xml")
    assert r.text.startswith("<svg")
    assert httpx.get(f"{URL}/images/999999999.svg", timeout=5).status_code == 404


def test_metriques_prometheus():
    r = httpx.get(DIRECT.rsplit("/v1", 1)[0] + "/metrics", timeout=5)
    assert r.status_code == 200 and "http_requests_total" in r.text

def test_validator_date_album_et_attributs_merch():
    album = {"name": "Test date", "category": "Album", "price_cents": 1990, "tags": ["pytest"],
             "attributes": {"artist": "X", "version": "Kit", "type": "mini", "release_date": "16/11/2026"}}
    assert_error(gql(CREATE, {"input": album}), "VALIDATION_ERROR")
    merch = {"name": "Test merch", "category": "Merch", "price_cents": 990, "tags": ["pytest"],
             "attributes": {"artist": "X", "type": "Poster set", "pieces": 0}}
    assert_error(gql(CREATE, {"input": merch}), "VALIDATION_ERROR")