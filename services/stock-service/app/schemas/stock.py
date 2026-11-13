from pydantic import BaseModel
from datetime import datetime
from typing import Optional

class StockByWarehouseResponse(BaseModel):
    product_id: int
    warehouse_id: int
    quantity: int

class StockTotalResponse(BaseModel):
    product_id: int
    total_quantity: int

class ReserveItem(BaseModel):
    product_id: int
    quantity: int

class StockReserveRequest(BaseModel):
    order_id: int
    items: list[ReserveItem] 


class StockReserveResponse(BaseModel):
    reservation_id: int
    order_id: int
    product_id: int
    warehouse_id: int  
    quantity: int
    status: str
    message: str

class StockItemDetail(BaseModel):
    product_id: int
    warehouse_id: int
    quantity: int

class StockDetailResponse(BaseModel):
    product_id: int
    total_quantity: int
    by_warehouse: list[StockItemDetail]

class StockCreateRequest(BaseModel):
    product_id: int
    warehouse_id: int
    quantity: int

class StockReleaseRequest(BaseModel):
    order_id: int
    product_id: int

class StockReleaseResponse(BaseModel):
    order_id: int
    product_id: int
    status: str
    message: str

class StockItemDetail(BaseModel):
    product_id: int
    warehouse_id: int
    quantity: int

class StockDetailResponse(BaseModel):
    product_id: int
    total_quantity: int
    by_warehouse: list[StockItemDetail]

class StockCreateRequest(BaseModel):
    product_id: int
    warehouse_id: int
    quantity: int
    

class WarehouseResponse(BaseModel):
    id: int
    name: str
    capacity_units: int
    used_units: int
    fill_rate: float
    status: str  # "normal" ou "near_full"

class WarehouseListResponse(BaseModel):
    warehouses: list[WarehouseResponse]


class ShipmentResponse(BaseModel):
    id: int
    order_id: int
    warehouse_id: int
    status: str
    created_at: datetime
    shipped_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None

class ShipmentCreateRequest(BaseModel):
    warehouse_id: int

class ShipmentStatusUpdateRequest(BaseModel):
    status: str  # "preparing", "shipped", "delivered"