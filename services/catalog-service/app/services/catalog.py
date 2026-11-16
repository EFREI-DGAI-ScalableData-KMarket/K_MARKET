"""Accès MongoDB (collection catalog) du service Catalogue."""
import json
from datetime import datetime, timezone

from bson import json_util
from pymongo import ASCENDING, MongoClient, ReturnDocument
from pymongo.errors import DuplicateKeyError, OperationFailure

from app.core.config import settings
from app.core.errors import gql_error

MAX_PAGE = 200


def _now():
    return datetime.now(timezone.utc)


def _validation_error(e: OperationFailure):
    """Code 121 = document refusé par le validator $jsonSchema (ex. Livre sans ISBN).

    insert lève WriteError, findAndModify lève OperationFailure (classe mère) : on attrape la seconde.
    Le détail contient des types BSON (ObjectId…) : json_util les rend sérialisables en JSON.
    """
    if e.code == 121:
        rules = ((e.details or {}).get("errInfo") or {}).get("details")
        return gql_error("VALIDATION_ERROR", "Produit refusé par le schéma de sa catégorie",
                         rules=json.loads(json_util.dumps(rules)))
    return e


class CatalogService:
    def __init__(self, db=None):
        self.db = db if db is not None else MongoClient(settings.mongo_uri)[settings.mongo_db]
        self.catalog = self.db["catalog"]

    # -- lecture
    def list_products(self, flt, limit: int, offset: int) -> tuple[list[dict], int]:
        if not 1 <= limit <= MAX_PAGE or offset < 0:
            raise gql_error("VALIDATION_ERROR", f"limit doit être entre 1 et {MAX_PAGE}, offset >= 0")
        query: dict = {}
        projection, sort = None, [("product_id", ASCENDING)]
        if flt is not None:
            if flt.category is not None:
                query["category"] = flt.category.value
            if flt.status is not None:
                query["status"] = flt.status.value
            price = {}
            if flt.min_price_cents is not None:
                price["$gte"] = flt.min_price_cents
            if flt.max_price_cents is not None:
                price["$lte"] = flt.max_price_cents
            if price:
                query["price_cents"] = price
            if flt.search:
                query["$text"] = {"$search": flt.search}
                projection = {"score": {"$meta": "textScore"}}
                sort = [("score", {"$meta": "textScore"}), ("product_id", ASCENDING)]
        total = self.catalog.count_documents(query)
        docs = list(self.catalog.find(query, projection).sort(sort).skip(offset).limit(limit))
        return docs, total

    def get_product(self, product_id: int) -> dict | None:
        return self.catalog.find_one({"product_id": product_id})

    def get_products_by_ids(self, ids: list[int]) -> list[dict]:
        if len(ids) > MAX_PAGE:
            raise gql_error("VALIDATION_ERROR", f"{MAX_PAGE} identifiants maximum")
        by_id = {d["product_id"]: d for d in self.catalog.find({"product_id": {"$in": ids}})}
        return [by_id[i] for i in dict.fromkeys(ids) if i in by_id]       # ordre de la demande, sans doublon

    # -- écriture
    def _next_product_id(self) -> int:
        counter = self.db["counters"].find_one_and_update(
            {"_id": "product_id"}, {"$inc": {"seq": 1}}, upsert=True, return_document=ReturnDocument.AFTER)
        return counter["seq"]

    def create_product(self, data: dict) -> dict:
        if data["price_cents"] < 0:
            raise gql_error("VALIDATION_ERROR", "price_cents doit être >= 0")
        doc = {"product_id": self._next_product_id(), "status": "active", "indexed_at": _now(), **data}
        try:
            self.catalog.insert_one(doc)
        except DuplicateKeyError:
            raise gql_error("CONFLICT", "product_id déjà utilisé : compteur désynchronisé (voir collection counters)")
        except OperationFailure as e:
            raise _validation_error(e)
        return doc

    def delete_product(self, product_id: int) -> None:
        """Compensation uniquement (stock initial refusé) : un produit commandé s'archive, il ne se supprime pas."""
        self.catalog.delete_one({"product_id": product_id})

    def update_product(self, product_id: int, changes: dict, unset: list[str]) -> dict:
        if changes.get("price_cents", 0) < 0:
            raise gql_error("VALIDATION_ERROR", "price_cents doit être >= 0")
        update = {"$set": {**changes, "indexed_at": _now()}}
        if unset:
            update["$unset"] = {field: "" for field in unset}
        try:
            doc = self.catalog.find_one_and_update({"product_id": product_id}, update,
                                                   return_document=ReturnDocument.AFTER)
        except OperationFailure as e:
            raise _validation_error(e)
        if doc is None:
            raise gql_error("NOT_FOUND", f"Produit {product_id} introuvable")
        return doc

    def set_status(self, product_id: int, status: str) -> dict:
        return self.update_product(product_id, {"status": status}, [])