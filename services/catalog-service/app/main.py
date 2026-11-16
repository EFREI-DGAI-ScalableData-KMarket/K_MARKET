from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from prometheus_fastapi_instrumentator import Instrumentator

from app.routers import catalog
from app.schemas.catalog import service
from app.services.images import product_svg

app = FastAPI(title="Catalog Service", version="1.0.0")


@app.get("/v1/health")
def health():
    return {"status": "ok", "version": "v1"}


@app.get("/v1/catalog/images/{product_id}.svg", include_in_schema=False)
def product_image(product_id: int):
    """Visuel du produit, public (une balise <img> n'envoie pas de jeton)."""
    doc = service().get_product(product_id)
    if doc is None:
        raise HTTPException(404, "Produit introuvable")
    return Response(product_svg(doc), media_type="image/svg+xml",
                    headers={"Cache-Control": "public, max-age=300"})


app.include_router(catalog.router, prefix="/v1/catalog")

# Métriques Prometheus sur /metrics (lues par Prometheus sur le réseau Docker, non routées par la gateway)
Instrumentator().instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)