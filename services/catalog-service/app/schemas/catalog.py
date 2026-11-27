"""Schéma GraphQL du catalogue (Query + Mutation) — contrat : contracts/catalog.graphql.

Lectures publiques (D8) : un visiteur sans jeton ou un client (customer) ne voit que les produits actifs ;
l'équipe et les comptes techniques voient aussi les archivés. Écritures : admin et catalog_manager seulement.
"""
from typing import List, Optional

import strawberry
from strawberry.schema.config import StrawberryConfig
from strawberry.types import Info

from app.core.errors import gql_error
from app.core.security import WRITE_ROLES, require_roles, sees_archived
from app.schemas.types import (
    InitialStock, Product, ProductFilter, ProductInput, ProductPage, ProductStatus, ProductUpdateInput, to_product,
)
from app.services import stock_client
from app.services.catalog import CatalogService

_service: CatalogService | None = None


def service() -> CatalogService:
    """Créé au premier appel (et remplaçable dans les tests)."""
    global _service
    if _service is None:
        _service = CatalogService()
    return _service


def _active_only(filter: Optional[ProductFilter]) -> ProductFilter:
    """Même filtre, statut forcé à « active » (le visiteur ne peut pas demander les archivés)."""
    f = filter or ProductFilter()
    return ProductFilter(category=f.category, status=ProductStatus.active, search=f.search,
                         min_price_cents=f.min_price_cents, max_price_cents=f.max_price_cents)


@strawberry.type
class Query:
    @strawberry.field(description="Lister les produits (filtres + pagination, tri par product_id ou pertinence). "
                                  "Public : sans jeton ou pour un client, produits actifs seulement")
    def products(self, info: Info, filter: Optional[ProductFilter] = None,
                 limit: int = 50, offset: int = 0) -> ProductPage:
        if not sees_archived(info):
            filter = _active_only(filter)
        docs, total = service().list_products(filter, limit, offset)
        return ProductPage(items=[to_product(d) for d in docs], total=total, has_more=offset + len(docs) < total)

    @strawberry.field(description="Lire un produit ; null s'il n'existe pas (ou s'il est archivé, pour un visiteur ou un client)")
    def product(self, info: Info, product_id: int) -> Optional[Product]:
        staff = sees_archived(info)              # toujours en premier : un jeton fourni doit être valide
        doc = service().get_product(product_id)
        if doc is None or (doc["status"] != "active" and not staff):
            return None
        return to_product(doc)

    @strawberry.field(description="Lire plusieurs produits en un appel (orders-api : prix des lignes de commande). "
                                  "Ordre de la demande ; les ids inconnus sont ignorés (et les archivés, pour un "
                                  "visiteur ou un client) ; 200 ids maximum")
    def productsByIds(self, info: Info, ids: List[int]) -> List[Product]:
        docs = service().get_products_by_ids(ids)
        if not sees_archived(info):
            docs = [d for d in docs if d["status"] == "active"]
        return [to_product(d) for d in docs]


@strawberry.type
class Mutation:
    @strawberry.mutation(description="Créer un produit (actif). Avec initial_stock : appelle POST /stock du service "
                                     "Stock ; si cet appel échoue, le produit est supprimé (compensation)")
    def createProduct(self, info: Info, input: ProductInput,
                      initial_stock: Optional[InitialStock] = None) -> Product:
        require_roles(info, *WRITE_ROLES)
        data = {"name": input.name, "category": input.category.value, "price_cents": input.price_cents,
                "tags": input.tags or [], "attributes": dict(input.attributes)}
        if input.description is not None:
            data["description"] = input.description
        doc = service().create_product(data)
        if initial_stock is not None:
            try:
                stock_client.create_stock(doc["product_id"], initial_stock.warehouse_id, initial_stock.quantity)
            except Exception:
                service().delete_product(doc["product_id"])
                raise
        return to_product(doc)

    @strawberry.mutation(description="Mettre à jour un produit (partiel : seuls les champs fournis changent)")
    def updateProduct(self, info: Info, product_id: int, input: ProductUpdateInput) -> Product:
        require_roles(info, *WRITE_ROLES)
        changes, unset = {}, []
        for field in ("name", "category", "price_cents", "attributes", "tags", "description"):
            value = getattr(input, field)
            if value is strawberry.UNSET:
                continue
            if value is None:
                if field == "description":
                    unset.append(field)
                elif field == "tags":
                    changes["tags"] = []
                else:
                    raise gql_error("VALIDATION_ERROR", f"{field} ne peut pas être null")
                continue
            changes[field] = value.value if field == "category" else dict(value) if field == "attributes" else value
        if not changes and not unset:
            raise gql_error("VALIDATION_ERROR", "Aucun champ à modifier")
        return to_product(service().update_product(product_id, changes, unset))

    @strawberry.mutation(description="Archiver un produit (status inactive) : on ne supprime pas un produit déjà commandé")
    def archiveProduct(self, info: Info, product_id: int) -> Product:
        require_roles(info, *WRITE_ROLES)
        return to_product(service().set_status(product_id, "inactive"))

    @strawberry.mutation(description="Réactiver un produit archivé")
    def reactivateProduct(self, info: Info, product_id: int) -> Product:
        require_roles(info, *WRITE_ROLES)
        return to_product(service().set_status(product_id, "active"))


class CatalogSchema(strawberry.Schema):
    """Ne journalise pas en erreur (avec trace) les refus et erreurs métier prévus (extensions.code) :
    ils sont déjà tracés proprement (refus : logger « security »). Les erreurs inattendues restent journalisées."""

    def process_errors(self, errors, execution_context=None):
        unexpected = [e for e in errors if not (e.extensions or {}).get("code")]
        if unexpected:
            super().process_errors(unexpected, execution_context)


schema = CatalogSchema(query=Query, mutation=Mutation, config=StrawberryConfig(auto_camel_case=False))