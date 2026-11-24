from fastapi import APIRouter, Depends

from app.core.security import require_roles
from app.repositories import dashboard
from app.schemas import Summary

router = APIRouter()

# Matrice du sujet : GET /v1/dashboard/summary → admin, catalog_manager, analyst, logistics ; pas service_account.
READ = Depends(require_roles("admin", "catalog_manager", "analyst", "logistics"))


@router.get("/dashboard/summary", response_model=Summary, dependencies=[READ])
def get_summary():
    return dashboard.summary()