"""Types GraphQL du catalogue — alignés sur contracts/catalog.graphql."""
import typing
from datetime import datetime
from enum import Enum
from typing import Annotated, List, Optional, Union

import strawberry
from strawberry.scalars import JSON


# ---------------------------------------------------------------- enums (valeurs = celles stockées dans MongoDB)
@strawberry.enum
class Category(Enum):
    Album = "Album"
    Lightstick = "Lightstick"
    Merch = "Merch"
    Drama = "Drama"
    Livre = "Livre"
    Beaute = "Beaute"


@strawberry.enum
class ProductStatus(Enum):
    active = "active"
    inactive = "inactive"


@strawberry.enum
class AlbumType(Enum):
    studio = "studio"
    mini = "mini"
    single = "single"
    mixtape = "mixtape"
    anthology = "anthology"


@strawberry.enum
class DramaSupport(Enum):
    DVD = "DVD"
    Blu_ray = "Blu_ray"
    Coffret = "Coffret"
    Artbook = "Artbook"


@strawberry.enum
class Langue(Enum):
    fr = "fr"
    en = "en"
    ko = "ko"


# ---------------------------------------------------------------- attributs par catégorie
@strawberry.type
class AlbumAttributes:
    artist: str
    version: str
    type: AlbumType
    release_date: str
    photocards: Optional[int] = None
    limited_edition: Optional[bool] = None


@strawberry.type
class LightstickAttributes:
    artist: str
    version: str
    bluetooth: Optional[bool] = None
    piles: Optional[str] = None


@strawberry.type
class MerchAttributes:
    artist: str
    type: str
    size: Optional[List[str]] = None
    couleur: Optional[List[str]] = None
    matiere: Optional[str] = None
    format: Optional[str] = None
    pieces: Optional[int] = None


@strawberry.type
class DramaAttributes:
    serie: str
    support: DramaSupport
    saison: Optional[int] = None
    episodes: Optional[int] = None
    sous_titres: Optional[List[str]] = None


@strawberry.type
class LivreAttributes:
    isbn: str
    langue: Langue
    auteur: Optional[str] = None
    pages: Optional[int] = None
    genre: Optional[str] = None


@strawberry.type
class BeauteAttributes:
    marque: str
    contenance_ml: int
    type: Optional[str] = None
    type_peau: Optional[str] = None
    ingredients: Optional[List[str]] = None


ProductAttributes = Annotated[
    Union[AlbumAttributes, LightstickAttributes, MerchAttributes,
          DramaAttributes, LivreAttributes, BeauteAttributes],
    strawberry.union("ProductAttributes"),
]

ATTRIBUTE_TYPES = {
    "Album": AlbumAttributes, "Lightstick": LightstickAttributes, "Merch": MerchAttributes,
    "Drama": DramaAttributes, "Livre": LivreAttributes, "Beaute": BeauteAttributes,
}


# ---------------------------------------------------------------- produit
@strawberry.type
class Product:
    product_id: int
    name: str
    category: Category
    price_cents: int
    tags: List[str]
    description: Optional[str]
    attributes: ProductAttributes
    status: ProductStatus
    indexed_at: Optional[datetime]


@strawberry.type
class ProductPage:
    items: List[Product]
    total: int
    has_more: bool


# ---------------------------------------------------------------- inputs
@strawberry.input
class ProductFilter:
    category: Optional[Category] = None
    status: Optional[ProductStatus] = None
    search: Optional[str] = strawberry.field(default=None, description="Plein texte sur name et description (index MongoDB french)")
    min_price_cents: Optional[int] = None
    max_price_cents: Optional[int] = None


@strawberry.input
class InitialStock:
    warehouse_id: int
    quantity: int


@strawberry.input
class ProductInput:
    name: str
    category: Category
    price_cents: int
    attributes: JSON = strawberry.field(description="Attributs propres à la catégorie, validés par le validator MongoDB")
    tags: Optional[List[str]] = None
    description: Optional[str] = None


@strawberry.input(description="Mise à jour partielle : seuls les champs fournis sont modifiés")
class ProductUpdateInput:
    name: Optional[str] = strawberry.UNSET
    category: Optional[Category] = strawberry.UNSET
    price_cents: Optional[int] = strawberry.UNSET
    attributes: Optional[JSON] = strawberry.UNSET
    tags: Optional[List[str]] = strawberry.UNSET
    description: Optional[str] = strawberry.UNSET


# ---------------------------------------------------------------- conversion document MongoDB -> types GraphQL
def _enum_of(hint) -> type[Enum] | None:
    for candidate in (hint, *typing.get_args(hint)):
        if isinstance(candidate, type) and issubclass(candidate, Enum):
            return candidate
    return None


def _build(cls, data: dict):
    """Instancie un type Strawberry à partir d'un dict, en convertissant les enums et en ignorant les clés inconnues."""
    kwargs = {}
    for name, hint in typing.get_type_hints(cls).items():
        if name in data:
            value, enum_cls = data[name], _enum_of(hint)
            kwargs[name] = enum_cls(value) if enum_cls and value is not None else value
    return cls(**kwargs)


def to_product(doc: dict) -> Product:
    return Product(
        product_id=doc["product_id"],
        name=doc["name"],
        category=Category(doc["category"]),
        price_cents=doc["price_cents"],
        tags=doc.get("tags", []),
        description=doc.get("description"),
        attributes=_build(ATTRIBUTE_TYPES[doc["category"]], doc.get("attributes", {})),
        status=ProductStatus(doc["status"]),
        indexed_at=doc.get("indexed_at"),
    )
