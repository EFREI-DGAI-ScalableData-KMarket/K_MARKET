"""Vérification du JWT émis par auth-api (HS256, secret partagé JWT_SECRET).

Claims attendus : `sub` et `roles` (liste de rôles applicatifs, voir auth.openapi.yaml).
"""
import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import settings

_bearer = HTTPBearer(auto_error=False)   


def require_roles(*allowed: str):
    """Dépendance FastAPI : 401 si le jeton est absent ou invalide, 403 si aucun rôle autorisé."""

    def checker(cred: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> dict:
        if cred is None:
            raise HTTPException(401, "Jeton absent")
        try:
            claims = jwt.decode(cred.credentials, settings.jwt_secret, algorithms=["HS256"])
        except jwt.ExpiredSignatureError:
            raise HTTPException(401, "Jeton expiré")
        except jwt.InvalidTokenError:
            raise HTTPException(401, "Jeton invalide")
        if not set(claims.get("roles", [])) & set(allowed):
            raise HTTPException(403, f"Rôle requis : {', '.join(allowed)}")
        return claims

    return checker