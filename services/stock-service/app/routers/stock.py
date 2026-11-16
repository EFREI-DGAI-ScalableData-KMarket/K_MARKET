"""Routes du service Stock — contrat : contracts/inventory.openapi.yaml (monté sous /v1)."""
from typing import Optional

from fastapi import APIRouter, Depends, Query

from app.core.security import require_roles
from app.repositories.stock import StockRepository
from app.schemas.stock import (
    OrderRef, ReleaseRequest, ReservationResult, ReserveRequest,
    Shipment, ShipmentCreate, ShipmentPage, ShipmentStatus, ShipmentStatusUpdate,
    StockCreate, StockItem, StockSummary, StockUpdate, Warehouse,
)

router = APIRouter()
repo = StockRepository()

SERVICE = Depends(require_roles("service_account", "admin"))                                    # Saga, catalogue
READ = Depends(require_roles("admin", "logistics", "analyst", "catalog_manager", "service_account"))  # lire le stock
UPDATE = Depends(require_roles("admin", "logistics", "service_account"))                        # mettre à jour le stock
WAREHOUSES = Depends(require_roles("admin", "logistics", "analyst"))                            # GET /warehouses
SHIPMENTS_READ = Depends(require_roles("admin", "logistics", "analyst"))
LOGISTICS = Depends(require_roles("admin", "logistics"))                                        # écrire les expéditions


# - STOCK
@router.post("/stock", status_code=201, response_model=StockItem, dependencies=[SERVICE])
def create_stock(body: StockCreate):
    return repo.create_initial_stock(body.product_id, body.warehouse_id, body.quantity)


@router.get("/stock/{product_id}", response_model=StockSummary, dependencies=[READ])
def get_stock(product_id: int):
    return repo.get_stock_summary(product_id)


@router.get("/stock/{product_id}/warehouses/{warehouse_id}", response_model=StockItem, dependencies=[READ])
def get_stock_in_warehouse(product_id: int, warehouse_id: int):
    return repo.get_stock_item(product_id, warehouse_id)

@router.put("/stock/{product_id}/warehouses/{warehouse_id}", response_model=StockItem, dependencies=[UPDATE])
def set_stock_in_warehouse(product_id:int, warehouse_id:int, body: StockUpdate):
    return repo.set_quantity(product_id,warehouse_id,body.quantity)

# - RÉSERVATIONS (Saga)
@router.post("/stock/reserve", status_code=201, response_model=ReservationResult, dependencies=[SERVICE])
def reserve_stock(body: ReserveRequest):
    items = [(i.product_id, i.quantity) for i in body.items]
    return {"order_id": body.order_id, "reservations": repo.reserve_stock(body.order_id, items)}


@router.post("/stock/commit", response_model=ReservationResult, dependencies=[SERVICE])
def commit_stock(body: OrderRef):
    return {"order_id": body.order_id, "reservations": repo.commit_reservations(body.order_id)}


@router.post("/stock/release", response_model=ReservationResult, dependencies=[SERVICE])
def release_stock(body: ReleaseRequest):
    return {"order_id": body.order_id, "reservations": repo.release_stock(body.order_id, body.product_id)}


# - ENTREPÔTS
@router.get("/warehouses", response_model=list[Warehouse], dependencies=[WAREHOUSES])
def get_warehouses():
    return repo.get_warehouses()


# - EXPÉDITIONS
@router.get("/shipments", response_model=ShipmentPage, dependencies=[SHIPMENTS_READ])
def get_shipments(
    warehouse_id: Optional[int] = None,
    status: Optional[ShipmentStatus] = None,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    return repo.get_shipments(warehouse_id, status, limit, offset)


@router.post("/shipments", status_code=201, response_model=Shipment, dependencies=[LOGISTICS])
def create_shipment(body: ShipmentCreate):
    return repo.create_shipment(body.order_id, body.warehouse_id)


@router.patch("/shipments/{shipment_id}/status", response_model=Shipment, dependencies=[LOGISTICS])
def update_shipment_status(shipment_id: int, body: ShipmentStatusUpdate):
    return repo.update_shipment_status(shipment_id, body.status)