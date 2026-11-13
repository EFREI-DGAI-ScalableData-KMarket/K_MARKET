import json
from typing import List, Optional
from pymongo import MongoClient
from app.core.config import settings

class CatalogService:
    def __init__(self):
        self.client = MongoClient(settings.mongo_uri)
        self.db = self.client[settings.mongo_db]
        self.collection = self.db["catalog"]

    def get_products(self, category: Optional[str] = None) -> List[dict]:
        query = {"status": "active"}
        if category:
            query["category"] = category
        
        docs = self.collection.find(query)
        results = []
        for doc in docs:
            results.append({
                "product_id": doc.get("product_id"),
                "name": doc.get("name"),
                "category": doc.get("category"),
                "price_cents": doc.get("price_cents"),
                "status": doc.get("status"),
                "description": doc.get("description"),
                "attributes_json": json.dumps(doc.get("attributes", {}))
            })
        return results

    def get_product(self, product_id: int) -> Optional[dict]:
        doc = self.collection.find_one({"product_id": product_id})
        if not doc:
            return None
        return {
            "product_id": doc.get("product_id"),
            "name": doc.get("name"),
            "category": doc.get("category"),
            "price_cents": doc.get("price_cents"),
            "status": doc.get("status"),
            "description": doc.get("description"),
            "attributes_json": json.dumps(doc.get("attributes", {}))
        }

service = CatalogService()