"""Schémas du service Stock — alignés sur contracts/inventory.openapi.yaml."""
from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field

ReservationStatus = Literal["reserved", "committed", "released"]
ShipmentStatus = Literal["preparing", "shipped", "delivered"]
WarehouseStatus = Literal["normal", "near_full"]


#  Stock 
class StockCreate(BaseModel):
    product_id: int
    warehouse_id: int
    quantity: int = Field(ge=0)


class StockItem(BaseModel):
    product_id: int
    warehouse_id: int
    warehouse_name: str
    quantity: int
    updated_at: Optional[datetime] = None

class StockUpdate(BaseModel):
    quantity: int = Field(ge=0, description="Nouvelle quantité disponible (valeur absolue)")

class StockSummary(BaseModel):
    product_id: int
    total_quantity: int
    by_warehouse: list[StockItem]


#  Réservations 
class ReserveItem(BaseModel):
    product_id: int
    quantity: int = Field(ge=1)


class ReserveRequest(BaseModel):
    order_id: int
    items: list[ReserveItem] = Field(min_length=1)


class OrderRef(BaseModel):
    order_id: int


class ReleaseRequest(BaseModel):
    order_id: int
    product_id: Optional[int] = None   # absent = toute la commande


class Reservation(BaseModel):
    id: int
    order_id: int
    product_id: int
    warehouse_id: int
    quantity: int
    status: ReservationStatus
    created_at: datetime
    updated_at: Optional[datetime] = None


class ReservationResult(BaseModel):
    order_id: int
    reservations: list[Reservation]


#  Entrepôts 
class Warehouse(BaseModel):
    id: int
    name: str
    capacity_units: int
    used_units: int
    fill_rate: float
    status: WarehouseStatus


#  Expéditions 
class ShipmentCreate(BaseModel):
    order_id: int
    warehouse_id: int


class ShipmentStatusUpdate(BaseModel):
    status: ShipmentStatus


class Shipment(BaseModel):
    id: int
    order_id: int
    warehouse_id: int
    status: ShipmentStatus
    created_at: datetime
    shipped_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None


class ShipmentPage(BaseModel):
    items: list[Shipment]
    total: int