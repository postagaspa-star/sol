"""Scadenze (contratto, sezione 9)."""

import sqlite3
from typing import Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from .. import clock, forme
from ..auth import richiede_token
from ..db import get_conn
from ..errori import non_trovato, non_valido
from ..schemi import ScadenzaIn, ScadenzaPatch

router = APIRouter(dependencies=[Depends(richiede_token)])

DATA_SBAGLIATA = "La data va scritta come AAAA-MM-GG, per esempio 2026-10-16."
ORA_SBAGLIATA = "L'ora va scritta come HH:MM, per esempio 09:30."


def _scadenza(conn: sqlite3.Connection, scadenza_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM scadenze WHERE id = ?", (scadenza_id,)).fetchone()
    if r is None:
        raise non_trovato("Scadenza non trovata.")
    return r


@router.get("/scadenze")
def elenco(stato: Literal["aperta", "fatta", "scartata"] | None = None, conn: sqlite3.Connection = Depends(get_conn)):
    ordine = " ORDER BY quando, COALESCE(ora, '99:99'), id"
    if stato:
        righe = conn.execute("SELECT * FROM scadenze WHERE stato = ?" + ordine, (stato,))
    else:
        righe = conn.execute("SELECT * FROM scadenze" + ordine)
    return [forme.scadenza(r) for r in righe]


@router.post("/scadenze")
def crea(corpo: ScadenzaIn, conn: sqlite3.Connection = Depends(get_conn)):
    if not clock.data_valida(corpo.quando):
        raise non_valido(DATA_SBAGLIATA)
    if corpo.ora is not None and not clock.ora_valida(corpo.ora):
        raise non_valido(ORA_SBAGLIATA)
    if corpo.mail_id is not None and not conn.execute("SELECT 1 FROM mail WHERE id = ?", (corpo.mail_id,)).fetchone():
        raise non_trovato("Mail non trovata.")
    cur = conn.execute(
        "INSERT INTO scadenze (mail_id, descrizione, quando, ora, creata_il) VALUES (?, ?, ?, ?, ?)",
        (corpo.mail_id, corpo.descrizione.strip(), corpo.quando, corpo.ora, clock.adesso()),
    )
    return JSONResponse(status_code=201, content=forme.scadenza(_scadenza(conn, cur.lastrowid)))


@router.patch("/scadenze/{scadenza_id}")
def modifica(scadenza_id: int, corpo: ScadenzaPatch, conn: sqlite3.Connection = Depends(get_conn)):
    _scadenza(conn, scadenza_id)
    if corpo.quando is not None and not clock.data_valida(corpo.quando):
        raise non_valido(DATA_SBAGLIATA)
    campi = {}
    if corpo.stato is not None:
        campi["stato"] = corpo.stato
        # Per la pagina Oggi (18.1): quando è stata segnata fatta.
        if corpo.stato == "fatta" and _scadenza(conn, scadenza_id)["stato"] != "fatta":
            campi["fatta_il"] = clock.adesso()
        elif corpo.stato != "fatta":
            campi["fatta_il"] = None
    if corpo.quando is not None:
        campi["quando"] = corpo.quando
    if corpo.descrizione is not None:
        campi["descrizione"] = corpo.descrizione.strip()
    if campi:
        assegnazioni = ", ".join(f"{k} = ?" for k in campi)
        conn.execute(f"UPDATE scadenze SET {assegnazioni} WHERE id = ?", (*campi.values(), scadenza_id))
    return forme.scadenza(_scadenza(conn, scadenza_id))
