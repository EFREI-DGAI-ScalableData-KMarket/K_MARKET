"""Vérification du JWT émis par auth-api (HS256, secret partagé JWT_SECRET, claim `roles`).

Le contrôle est fait sur chaque query et mutation (exigence du sujet pour GraphQL) :
- lectures (queries) : publiques (D8). Sans jeton, le visiteur ne voit que les produits actifs ;
  un jeton fourni doit être valide (sinon UNAUTHORIZED), et seuls les rôles de STAFF_ROLES voient les archivés ;
- écritures (mutations) : jeton obligatoire et rôle de WRITE_ROLES (UNAUTHORIZED / FORBIDDEN).
Chaque refus (UNAUTHORIZED ou FORBIDDEN) est journalisé avec la date, l'utilisateur et l'opération demandée.
"""
import logging

import jwt
from strawberry.types import Info

from app.core.config import settings
from app.core.errors import gql_error

log = logging.getLogger("security")

# Équipe et comptes techniques : voient tout le catalogue, archivés compris.
# customer et visiteur sans jeton : produits actifs seulement.
STAFF_ROLES = ("admin", "catalog_manager", "analyst", "logistics", "service_account")
WRITE_ROLES = ("catalog_manager", "admin")


def _deny(code: str, message: str, info: Info, claims: dict | None = None):
    claims = claims or {}
    action = f"{info.operation.operation.value} {info.field_name}"          # ex. « mutation createProduct »
    log.warning("refus %s user=%s roles=%s action=%s raison=%r", code, claims.get("sub", "-"),
                claims.get("roles", []), action, message)
    raise gql_error(code, message)


def _decode(header: str, info: Info) -> dict:
    """Décode l'en-tête « Bearer <jeton> » ; UNAUTHORIZED s'il est mal formé, invalide ou expiré."""
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token:
        _deny("UNAUTHORIZED", "Jeton absent", info)
    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    except jwt.ExpiredSignatureError:
        _deny("UNAUTHORIZED", "Jeton expiré", info)
    except jwt.InvalidTokenError:
        _deny("UNAUTHORIZED", "Jeton invalide", info)


def require_roles(info: Info, *allowed: str) -> dict:
    """Lève UNAUTHORIZED (jeton absent/invalide/expiré) ou FORBIDDEN (rôle non autorisé)."""
    claims = _decode(info.context["request"].headers.get("authorization", ""), info)
    if not set(claims.get("roles", [])) & set(allowed):
        _deny("FORBIDDEN", f"Rôle requis : {', '.join(allowed)}", info, claims)
    return claims


def optional_claims(info: Info) -> dict | None:
    """Lecture publique : None sans en-tête Authorization (visiteur). Un jeton fourni doit être valide."""
    header = info.context["request"].headers.get("authorization", "")
    return _decode(header, info) if header else None


def sees_archived(info: Info) -> bool:
    """Vrai pour l'équipe et les comptes techniques ; faux pour un visiteur ou un client (customer)."""
    claims = optional_claims(info)
    return claims is not None and bool(set(claims.get("roles", [])) & set(STAFF_ROLES))