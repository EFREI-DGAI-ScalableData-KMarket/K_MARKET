import logging
import uuid

from fastapi import FastAPI
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.routers import dashboard

CODES = {400: "BAD_REQUEST", 401: "UNAUTHORIZED", 403: "FORBIDDEN", 404: "NOT_FOUND"}

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
app = FastAPI(title="Dashboard Service", version="1.0.0")
app.include_router(dashboard.router, prefix="/v1")
# Métriques Prometheus sur /metrics (lues par Prometheus sur le réseau Docker, non routées par la gateway)
Instrumentator().instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)


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