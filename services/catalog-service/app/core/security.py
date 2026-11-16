"""Vérification du JWT émis par auth-api (HS256, secret partagé JWT_SECRET, claim `roles`)."""
import jwt
from strawberry.types import Info

from app.core.config import settings
from app.core.errors import gql_error

ALL_ROLES = ("admin", "catalog_manager", "analyst", "logistics", "service_account")
WRITE_ROLES = ("catalog_manager", "admin")


def require_roles(info: Info, *allowed: str) -> dict:
    """Lève UNAUTHORIZED (jeton absent/invalide/expiré) ou FORBIDDEN (rôle non autorisé)."""
    header = info.context["request"].headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise gql_error("UNAUTHORIZED", "Jeton absent")
    try:
        claims = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError:
        raise gql_error("UNAUTHORIZED", "Jeton expiré")
    except jwt.InvalidTokenError:
        raise gql_error("UNAUTHORIZED", "Jeton invalide")
    if not set(claims.get("roles", [])) & set(allowed):
        raise gql_error("FORBIDDEN", f"Rôle requis : {', '.join(allowed)}")
    return claims
