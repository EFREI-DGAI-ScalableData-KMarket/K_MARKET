"""Réponse de GET /v1/dashboard/summary : même forme que contracts/dashboard.openapi.yaml."""
from datetime import datetime

from pydantic import BaseModel


class Today(BaseModel):
    orders: int
    orders_paid: int
    revenue_cents: int
    average_basket_cents: int | None
    payments_failed: int


class Hour(BaseModel):
    hour: datetime
    orders: int
    revenue_cents: int


class StockAlert(BaseModel):
    product_id: int
    remaining_units: int
    units_per_minute: float
    minutes_left: float
    raised_at: datetime


class WarehouseFill(BaseModel):
    warehouse_id: int
    used_units: int
    capacity_units: int
    fill_rate: float
    near_full: bool
    near_full_since: datetime | None
    measured_at: datetime


class Summary(BaseModel):
    data_as_of: datetime | None
    today: Today
    hourly: list[Hour]
    stock_alerts: list[StockAlert]
    warehouses: list[WarehouseFill]