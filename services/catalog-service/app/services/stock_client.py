"""Client du service Stock : jeton service_account obtenu auprès d'auth-api (D6), puis POST /stock."""
import time

import httpx

from app.core.config import settings
from app.core.errors import gql_error

_token: dict = {"value": None, "expires_at": 0.0}


def _service_token() -> str:
    """Échange CATALOG_API_KEY contre un JWT court ; mis en cache jusqu'à 30 s avant expiration."""
    if _token["value"] and time.time() < _token["expires_at"] - 30:
        return _token["value"]
    if not settings.auth_url:
        raise gql_error("UPSTREAM_ERROR", "auth-api non configurée (AUTH_URL vide) : stock initial indisponible")
    if not settings.catalog_api_key:
        raise gql_error("UPSTREAM_ERROR", "CATALOG_API_KEY non configurée : impossible d'appeler le service Stock")
    try:
        r = httpx.post(f"{settings.auth_url}/auth/token",
                       headers={"X-API-Key": settings.catalog_api_key}, timeout=5)
        r.raise_for_status()
    except httpx.HTTPError as e:
        raise gql_error("UPSTREAM_ERROR", f"auth-api indisponible ou clé refusée : {e}")
    body = r.json()
    _token.update(value=body["access_token"], expires_at=time.time() + body.get("expires_in", 900))
    return _token["value"]


def create_stock(product_id: int, warehouse_id: int, quantity: int) -> None:
    try:
        r = httpx.post(f"{settings.stock_url}/stock",
                       json={"product_id": product_id, "warehouse_id": warehouse_id, "quantity": quantity},
                       headers={"Authorization": f"Bearer {_service_token()}"}, timeout=5)
    except httpx.HTTPError as e:
        raise gql_error("UPSTREAM_ERROR", f"Service Stock injoignable : {e}")
    if r.status_code == 201:
        return
    message = r.json().get("message", r.text) if r.headers.get("content-type", "").startswith("application/json") else r.text
    code = {400: "VALIDATION_ERROR", 404: "NOT_FOUND", 409: "CONFLICT"}.get(r.status_code, "UPSTREAM_ERROR")
    raise gql_error(code, f"Service Stock : {message}")