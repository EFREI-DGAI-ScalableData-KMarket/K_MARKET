"""Vérification du JWT émis par auth-api (HS256, secret partagé JWT_SECRET, claim `roles`).

Chaque refus (401 ou 403) est journalisé avec la date, l'utilisateur et l'action demandée (exigence du sujet).
"""
import logging

import jwt
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import settings

log = logging.getLogger("security")
_bearer = HTTPBearer(auto_error=False)   # on gère nous-mêmes le 401 (format Error)


def _deny(status: int, message: str, request: Request, claims: dict | None = None):
    claims = claims or {}
    log.warning("refus %d user=%s roles=%s action=%s %s raison=%r", status, claims.get("sub", "-"),
                claims.get("roles", []), request.method, request.url.path, message)
    raise HTTPException(status, message)


def require_roles(*allowed: str):
    """Dépendance FastAPI : 401 si le jeton est absent ou invalide, 403 si aucun rôle autorisé."""

    def checker(request: Request, cred: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> dict:
        if cred is None:
            _deny(401, "Jeton absent", request)
        try:
            claims = jwt.decode(cred.credentials, settings.jwt_secret, algorithms=["HS256"])
        except jwt.ExpiredSignatureError:
            _deny(401, "Jeton expiré", request)
        except jwt.InvalidTokenError:
            _deny(401, "Jeton invalide", request)
        if not set(claims.get("roles", [])) & set(allowed):
            _deny(403, f"Rôle requis : {', '.join(allowed)}", request, claims)
        return claims

    return checker