"""Niente resta indietro (contratto, sezione 10)."""

import json
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, Depends

from .. import clock, db, forme
from ..auth import richiede_token
from ..caselle.testo import oggetto_risposta
from ..db import get_conn
from ..errori import non_trovato
from ..lavoro import risposta_arrivata
from ..servizi import Servizi, servizi
from .comuni import LUCCHETTO_BOZZE, richiedi_scrittore

router = APIRouter(dependencies=[Depends(richiede_token)])


def _sollecito_aperto(conn: sqlite3.Connection, inviata_id: int) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM bozze WHERE inviata_id = ? AND tipo = 'sollecito' AND stato != 'scartata' ORDER BY id DESC LIMIT 1",
        (inviata_id,),
    ).fetchone()


@router.get("/indietro")
def indietro(conn: sqlite3.Connection = Depends(get_conn)):
    imp = db.impostazioni(conn)
    ora = clock.now()

    limite_te = clock.iso(ora - timedelta(days=imp["giorni_risposta"]))
    righe = conn.execute(
        forme.SELECT_MAIL + " WHERE m.da_rispondere = 1 AND m.risposta_data = 0 AND m.sospetta = 0 AND m.ricevuta_il < ?"
        " ORDER BY m.ricevuta_il, m.id",
        (limite_te,),
    ).fetchall()
    aspettano_te = [
        {"mail": mail, "giorni": clock.giorni_trascorsi(r["ricevuta_il"])}
        for r, mail in zip(righe, forme.mail_lista(conn, righe))
    ]

    aspetti_tu = [
        {
            "inviata": forme.inviata(i),
            "giorni": giorni,
            "bozza_sollecito": {"id": bozza["id"], "stato": bozza["stato"]} if bozza else None,
        }
        for i, giorni, bozza in elenco_aspetti_tu(conn, imp)
    ]
    return {"aspettano_te": aspettano_te, "aspetti_tu": aspetti_tu}


def elenco_aspetti_tu(conn: sqlite3.Connection, imp: dict) -> list[tuple[sqlite3.Row, int, sqlite3.Row | None]]:
    """Le mail inviate che aspettano una risposta da troppo: (inviata, giorni, sollecito aperto o None)."""
    ora = clock.now()
    giorni_sollecito = imp["giorni_sollecito"]
    limite_tu = clock.iso(ora - timedelta(days=giorni_sollecito))
    uscita = []
    for i in conn.execute(
        "SELECT * FROM inviate WHERE aspetta_risposta = 1 AND ignorata = 0 AND inviata_il < ? ORDER BY inviata_il",
        (limite_tu,),
    ).fetchall():
        if risposta_arrivata(conn, i):
            continue
        # Nello stesso filo conta l'ultima mail mandata (per esempio un sollecito già spedito).
        if i["filo"] and conn.execute(
            "SELECT 1 FROM inviate WHERE casella_id = ? AND filo = ? AND inviata_il > ? LIMIT 1",
            (i["casella_id"], i["filo"], i["inviata_il"]),
        ).fetchone():
            continue
        bozza = _sollecito_aperto(conn, i["id"])
        if bozza is not None and bozza["stato"] == "inviata":
            if clock.da_iso(bozza["inviata_il"]) > ora - timedelta(days=giorni_sollecito):
                continue  # sollecitata da poco: si aspetta di nuovo
            bozza = None
        uscita.append((i, clock.giorni_trascorsi(i["inviata_il"]), bozza))
    return uscita


def _inviata(conn: sqlite3.Connection, inviata_id: int) -> sqlite3.Row:
    i = conn.execute("SELECT * FROM inviate WHERE id = ?", (inviata_id,)).fetchone()
    if i is None:
        raise non_trovato("Mail inviata non trovata.")
    return i


@router.post("/indietro/{inviata_id}/sollecito", status_code=202)
def sollecito(inviata_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    i = _inviata(conn, inviata_id)
    richiedi_scrittore(conn, s)
    with LUCCHETTO_BOZZE:
        # Se c'è già un sollecito aperto si restituisce quello (per rifarlo: /bozze/{id}/rifai):
        # così un secondo tocco non butta via quello che l'utente sta già correggendo.
        aperto = conn.execute(
            "SELECT * FROM bozze WHERE inviata_id = ? AND tipo = 'sollecito'"
            " AND stato IN ('in_preparazione', 'pronta', 'errore') ORDER BY id DESC LIMIT 1",
            (inviata_id,),
        ).fetchone()
        if aperto is not None:
            return forme.bozza(aperto)
        cur = conn.execute(
            "INSERT INTO bozze (inviata_id, tipo, stato, a, oggetto, richiesta_utente, rifai_da, creata_il)"
            " VALUES (?, 'sollecito', 'in_preparazione', ?, ?, 1, 'zero', ?)",
            (inviata_id, json.dumps(json.loads(i["a"] or "[]")), oggetto_risposta(i["oggetto"]), clock.adesso()),
        )
    s.lavoro.sveglia("scrittore")
    return forme.bozza(conn.execute("SELECT * FROM bozze WHERE id = ?", (cur.lastrowid,)).fetchone())


@router.post("/indietro/{inviata_id}/ignora")
def ignora(inviata_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    _inviata(conn, inviata_id)
    conn.execute("UPDATE inviate SET ignorata = 1 WHERE id = ?", (inviata_id,))
    return {"ok": True}
