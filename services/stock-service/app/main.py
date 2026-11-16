import uuid

from fastapi import FastAPI
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.repositories.stock import Conflict, InsufficientStock, NotFound
from app.routers import stock

CODES = {
        400: "BAD_REQUEST",
        401: "UNAUTHORIZED",
        403: "FORBIDDEN",
        404: "NOT_FOUND", 
        409: "CONFLICT",
        422: "INSUFFICIENT_STOCK"
        }

app = FastAPI(title="Stock Service", version="1.0.0")
app.include_router(stock.router, prefix="/v1")


@app.get("/v1/health")
def health():
    return {"status": "ok", "version": "v1"}


def error(status: int, body: dict) -> JSONResponse:
    body.setdefault("code", CODES.get(status, "INTERNAL_ERROR"))
    body["request_id"] = str(uuid.uuid4())
    return JSONResponse(status_code=status, content=jsonable_encoder(body))


@app.exception_handler(StarletteHTTPException)
async def http_error(request, exc):
    body = dict(exc.detail) if isinstance(exc.detail, dict) else {"message": str(exc.detail)}
    return error(exc.status_code, body)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    return error(400, {"message": "Requête invalide", "details": exc.errors()})


@app.exception_handler(NotFound)
async def not_found(request, exc):
    return error(404, {"message": str(exc)})


@app.exception_handler(Conflict)
async def conflict(request, exc):
    return error(409, {"message": str(exc)})


@app.exception_handler(InsufficientStock)
async def insufficient_stock(request, exc):
    return error(422, {"message": str(exc), "details": exc.details})