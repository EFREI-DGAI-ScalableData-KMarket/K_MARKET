from fastapi import APIRouter, HTTPException
from app.repositories.stock import StockRepository
from app.schemas.stock import StockByWarehouseResponse, StockTotalResponse
from app.schemas.stock import StockReserveRequest, StockReserveResponse
from app.schemas.stock import StockReleaseRequest, StockReleaseResponse
from app.schemas.stock import WarehouseListResponse
from app.schemas.stock import ShipmentResponse, ShipmentCreateRequest, ShipmentStatusUpdateRequest
from app.schemas.stock import StockDetailResponse, StockCreateRequest
router = APIRouter()
repo = StockRepository()

@router.get("/")
def get_stock_status():
    return {"message": "Stock service opérationnel"}

@router.get("/{product_id}/warehouses/{warehouse_id}", response_model=StockByWarehouseResponse)
def get_stock_by_warehouse(product_id: int, warehouse_id: int):
    stock = repo.get_stock_by_warehouse(product_id, warehouse_id)
    if not stock:
        raise HTTPException(status_code=404, detail="Aucun stock trouvé pour ce produit dans cet entrepôt")
    return stock


@router.get("/{product_id}", response_model=StockDetailResponse)
def get_stock_detail(product_id: int):
    return repo.get_stock_detail(product_id)

@router.post("", status_code=201)
def create_stock(request: StockCreateRequest):
    try:
        return repo.create_initial_stock(request.product_id, request.warehouse_id, request.quantity)
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))

@router.post("/reserve")
def reserve_stock(request: StockReserveRequest):
    try:
        items_dict = [{"product_id": i.product_id, "quantity": i.quantity} for i in request.items]
        results = repo.reserve_stock(request.order_id, items_dict)
        
        unique_results = []
        seen = set()
        for r in results:
            if r["reservation_id"] not in seen:
                unique_results.append(r)
                seen.add(r["reservation_id"])
                
        return {"message": "Réservation traitée", "details": unique_results}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


# Route 4 : Libérer du stock réservé pour une commande
@router.post("/release", response_model=StockReleaseResponse)
def release_stock(request: StockReleaseRequest):
    result = repo.release_stock(request.order_id, request.product_id)
    return StockReleaseResponse(
        order_id=request.order_id,
        product_id=request.product_id,
        status=result["status"],
        message=result["message"]
    )



@router.get("/warehouses", response_model=WarehouseListResponse)
def get_warehouses():
    return {"warehouses": repo.get_warehouses()}




@router.get("/shipments", response_model=list[ShipmentResponse])
def get_shipments(warehouse_id: int = None, status: str = None):
    return repo.get_shipments(warehouse_id, status)

@router.post("/shipments/{order_id}", response_model=ShipmentResponse)
def create_shipment(order_id: int, request: ShipmentCreateRequest):
    try:
        return repo.create_shipment(order_id, request.warehouse_id)
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))

@router.patch("/shipments/{shipment_id}/status")
def update_shipment_status(shipment_id: int, request: ShipmentStatusUpdateRequest):
    success = repo.update_shipment_status(shipment_id, request.status)
    if not success:
        raise HTTPException(status_code=404, detail="Expédition introuvable")
    return {"message": f"Statut mis à jour vers {request.status}"}