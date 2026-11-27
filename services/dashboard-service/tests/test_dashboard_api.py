"""Tests d'intégration du service Dashboard (contrat : contracts/dashboard.openapi.yaml).

Prérequis : pile lancée et migration analytics appliquée. Lancement : make test-dashboard.
Variables : JWT_SECRET (obligatoire), DASHBOARD_URL, DASHBOARD_DIRECT_URL.
"""
import os
import time

import httpx
import jwt
import pytest

URL = os.getenv("DASHBOARD_URL", "http://localhost:8000/v1/dashboard/summary")   # via la gateway
DIRECT = os.getenv("DASHBOARD_DIRECT_URL", "http://localhost:8006")               # service en direct
SECRET = os.environ["JWT_SECRET"]


def token(*roles: str, expires_in: int = 600) -> dict:
    claims = {"iss": "kmarket-auth", "sub": "tests", "roles": list(roles), "exp": int(time.time()) + expires_in}
    return {"Authorization": "Bearer " + jwt.encode(claims, SECRET, algorithm="HS256")}


def test_health():
    r = httpx.get(f"{DIRECT}/v1/health", timeout=5)
    assert r.status_code == 200 and r.json() == {"status": "ok", "version": "v1"}


def test_sans_jeton_ou_jeton_expire_401():
    assert httpx.get(URL, timeout=5).status_code == 401
    r = httpx.get(URL, headers=token("admin", expires_in=-10), timeout=5)
    assert r.status_code == 401 and r.json()["code"] == "UNAUTHORIZED"


@pytest.mark.parametrize("role", ["customer", "service_account"])
def test_roles_refuses_403(role):
    r = httpx.get(URL, headers=token(role), timeout=5)
    assert r.status_code == 403 and r.json()["code"] == "FORBIDDEN"


@pytest.mark.parametrize("role", ["admin", "catalog_manager", "analyst", "logistics"])
def test_roles_autorises_et_forme_de_la_reponse(role):
    r = httpx.get(URL, headers=token(role), timeout=5)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"data_as_of", "today", "hourly", "stock_alerts", "warehouses"}
    assert set(body["today"]) == {"orders", "orders_paid", "revenue_cents", "average_basket_cents", "payments_failed"}


def test_panier_moyen_et_alertes_coherents():
    body = httpx.get(URL, headers=token("analyst"), timeout=5).json()
    t = body["today"]
    if t["orders_paid"]:
        assert t["average_basket_cents"] == round(t["revenue_cents"] / t["orders_paid"])
    minutes = [a["minutes_left"] for a in body["stock_alerts"]]
    assert minutes == sorted(minutes)                                  # la plus urgente d'abord
    assert all((w["fill_rate"] > 0.9) == w["near_full"] for w in body["warehouses"])


def test_metriques_prometheus():
    r = httpx.get(f"{DIRECT}/metrics", timeout=5)
    assert r.status_code == 200 and "http_requests_total" in r.text