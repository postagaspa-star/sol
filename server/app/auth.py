"""Ogni dispositivo abbinato ha il suo token; il server ne conosce solo l'hash."""

import sqlite3
from dataclasses import dataclass
from datetime import timedelta
from typing import Annotated

from fastapi import Depends, Header

from . import clock
from .db import get_conn, hash_segreto
from .errori import ErroreApi


@dataclass(frozen=True)
class Dispositivo:
    id: int
    nome: str
    tipo: str


def token_da_header(authorization: str | None) -> str | None:
    if not authorization or not authorization.startswith("Bearer "):
        return None
    token = authorization.removeprefix("Bearer ").strip()
    return token or None


def dispositivo_del_token(conn: sqlite3.Connection, token: str | None) -> sqlite3.Row | None:
    if not token:
        return None
    # Si cerca l'hash, non il token: il confronto non dice niente del segreto.
    return conn.execute(
        "SELECT * FROM dispositivi WHERE token_hash = ?", (hash_segreto(token),)
    ).fetchone()


def richiede_token(
    authorization: Annotated[str | None, Header()] = None,
    conn: sqlite3.Connection = Depends(get_conn),
) -> Dispositivo:
    riga = dispositivo_del_token(conn, token_da_header(authorization))
    if riga is None:
        raise ErroreApi(401, "Questo dispositivo non è abbinato o il suo accesso è stato revocato.")
    ora = clock.now()
    # ultimo_accesso al minuto: scriverlo a ogni richiesta (il PC chiede ogni 2 minuti
    # più i ricaricamenti ogni 2-5 s) sarebbe solo rumore sul disco del NAS.
    if riga["ultimo_accesso"] is None or ora - clock.da_iso(riga["ultimo_accesso"]) > timedelta(minutes=1):
        conn.execute("UPDATE dispositivi SET ultimo_accesso = ? WHERE id = ?", (clock.iso(ora), riga["id"]))
    return Dispositivo(id=riga["id"], nome=riga["nome"], tipo=riga["tipo"])
