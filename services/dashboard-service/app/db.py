"""Connexion PostgreSQL (rôle dashboard_svc, lecture seule). Une connexion par requête : quelques appels par minute."""
from contextlib import contextmanager

import psycopg
from psycopg.rows import dict_row

from app.core.config import settings


def _conninfo() -> str:
    return (f"host={settings.postgres_host} port={settings.postgres_port} dbname={settings.postgres_db} "
            f"user={settings.postgres_user} password={settings.postgres_password}")


@contextmanager
def connect():
    # Lignes sous forme de dict ; heures renvoyées en UTC (le front les affiche à l'heure locale).
    with psycopg.connect(_conninfo(), row_factory=dict_row, options="-c TimeZone=UTC") as conn:
        yield conn