"""Riassunti (contratto, sezione 8)."""

import json
import sqlite3

from fastapi import APIRouter, Depends

from .. import clock, db, forme
from ..auth import richiede_token
from ..db import get_conn
from ..errori import non_trovato
from ..schemi import RiassuntoIn
from ..servizi import Servizi, servizi
from .comuni import richiedi_scrittore

router = APIRouter(dependencies=[Depends(richiede_token)])


# Prima di /riassunti/{id}: "oggi" non è un numero.
@router.get("/riassunti/oggi")
def oggi(conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = conn.execute("SELECT * FROM riassunto_oggi WHERE id = 1").fetchone()
    if r["giorno"] != clock.oggi().isoformat():
        # Quello che c'è è di ieri: non lo si mostra come se fosse di oggi.
        db.segna_riassunto_oggi_da_rifare(conn)
        s.lavoro.sveglia("scrittore")
        return {"testo": "", "aggiornato_il": None, "mail_citate": [], "stato": "in_coda"}
    return {
        "testo": r["testo"],
        "aggiornato_il": clock.per_utente(r["aggiornato_il"]),
        "mail_citate": json.loads(r["mail_citate"] or "[]"),
        "stato": r["stato"],
    }


@router.post("/riassunti", status_code=202)
def chiedi(corpo: RiassuntoIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    richiedi_scrittore(conn, s)
    cur = conn.execute(
        "INSERT INTO riassunti (richiesta, stato, creato_il) VALUES (?, 'in_coda', ?)",
        (corpo.richiesta.strip(), clock.adesso()),
    )
    s.lavoro.sveglia("scrittore")
    return {"id": cur.lastrowid, "stato": "in_coda"}


@router.get("/riassunti")
def ultimi(conn: sqlite3.Connection = Depends(get_conn)):
    return [forme.riassunto(r) for r in conn.execute("SELECT * FROM riassunti ORDER BY id DESC LIMIT 20")]


@router.get("/riassunti/{riassunto_id}")
def dettaglio(riassunto_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    r = conn.execute("SELECT * FROM riassunti WHERE id = ?", (riassunto_id,)).fetchone()
    if r is None:
        raise non_trovato("Riassunto non trovato.")
    return forme.riassunto(r)
