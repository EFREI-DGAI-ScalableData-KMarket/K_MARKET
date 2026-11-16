"""Vérification du JWT émis par auth-api (HS256, secret partagé JWT_SECRET, claim `roles`).

Le contrôle est fait sur chaque query et mutation (exigence du sujet pour GraphQL).
Chaque refus (UNAUTHORIZED ou FORBIDDEN) est journalisé avec la date, l'utilisateur et l'opération demandée.
"""
import logging

import jwt
from strawberry.types import Info

from app.core.config import settings
from app.core.errors import gql_error

log = logging.getLogger("security")

ALL_ROLES = ("admin", "catalog_manager", "analyst", "logistics", "service_account")
WRITE_ROLES = ("catalog_manager", "admin")


def _deny(code: str, message: str, info: Info, claims: dict | None = None):
    claims = claims or {}
    action = f"{info.operation.operation.value} {info.field_name}"          # ex. « mutation createProduct »
    log.warning("refus %s user=%s roles=%s action=%s raison=%r", code, claims.get("sub", "-"),
                claims.get("roles", []), action, message)
    raise gql_error(code, message)


def require_roles(info: Info, *allowed: str) -> dict:
    """Lève UNAUTHORIZED (jeton absent/invalide/expiré) ou FORBIDDEN (rôle non autorisé)."""
    header = info.context["request"].headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token:
        _deny("UNAUTHORIZED", "Jeton absent", info)
    try:
        claims = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError:
        _deny("UNAUTHORIZED", "Jeton expiré", info)
    except jwt.InvalidTokenError:
        _deny("UNAUTHORIZED", "Jeton invalide", info)
    if not set(claims.get("roles", [])) & set(allowed):
        _deny("FORBIDDEN", f"Rôle requis : {', '.join(allowed)}", info, claims)
    return claims