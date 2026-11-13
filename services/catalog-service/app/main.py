from fastapi import FastAPI
from app.routers import catalog

app = FastAPI(title="Catalog Service", version="1.0.0")


app.include_router(catalog.router, prefix="/v1/catalog")

@app.get("/health")
def health_check():
    return {"status": "ok"}