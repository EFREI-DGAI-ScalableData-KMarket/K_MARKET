import strawberry
from typing import Optional, List
from app.services.catalog import service

@strawberry.type
class Product:
    product_id: int
    name: str
    category: str
    price_cents: int
    status: str
    description: Optional[str] = None
    attributes_json: str 

@strawberry.type
class Query:
    @strawberry.field
    def products(self, category: Optional[str] = None) -> List[Product]:
        data = service.get_products(category)
        return [Product(**item) for item in data]

    @strawberry.field
    def product(self, product_id: int) -> Optional[Product]:
        data = service.get_product(product_id)
        if data:
            return Product(**data)
        return None

schema = strawberry.Schema(query=Query)