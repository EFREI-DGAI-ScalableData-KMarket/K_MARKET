from fastapi import FastAPI
from app.routers import stock

app = FastAPI(title="Stock Service", version="1.0.0")

app.include_router(stock.router, prefix="/v1/stock", tags=["stock"])

@app.get("/health")
def health_check():
    return {"status": "ok"}