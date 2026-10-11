"""Pagina "Oggi" (contratto 18.1): tutto in una chiamata. Solo campi: le frasi le compongono le app."""

import json
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, Depends

from .. import clock, db
from ..auth import richiede_token
from ..db import get_conn
from ..servizi import Servizi, servizi
from .indietro import elenco_aspetti_tu
from .riassunti import riassunto_di_oggi

router = APIRouter(dependencies=[Depends(richiede_token)])

VOCI_MASSIME = 8

SELECT_MAIL_GRUPPO = "SELECT m.*, g.colore AS gruppo_colore FROM mail m LEFT JOIN gruppi g ON g.id = m.gruppo_id"


def iniziali(nome: str | None, indirizzo: str | None) -> str:
    """1-2 lettere: prima e ultima parola del nome, altrimenti dalla parte prima della @."""
    nome = (nome or "").strip()
    if nome and "@" not in nome:
        parole = nome.split()
    else:
        locale = (nome if "@" in nome else (indirizzo or "")).split("@", 1)[0]
        parole = locale.replace(".", " ").replace("_", " ").replace("-", " ").split()
    lettere = [next((c for c in p if c.isalpha()), "") for p in parole]
    lettere = [c.upper() for c in lettere if c]
    if not lettere:
        return ""
    return lettere[0] if len(lettere) == 1 else lettere[0] + lettere[-1]


def _voce_mail(tipo: str, m: sqlite3.Row) -> dict:
    return {
        "tipo": tipo,
        "mail_id": m["id"],
        "mittente": m["mittente_nome"] or m["mittente_indirizzo"],
        "oggetto": m["oggetto"],
        "riassunto": m["riassunto"],
        "colore": m["gruppo_colore"],
        "iniziali": iniziali(m["mittente_nome"], m["mittente_indirizzo"]),
    }


def _voce_risposta(m: sqlite3.Row) -> dict:
    return {
        "tipo": "risposta",
        "mail_id": m["id"],
        "bozza_id": m["bozza_id"],
        "mittente": m["mittente_nome"] or m["mittente_indirizzo"],
        "oggetto": m["oggetto"],
        "riassunto": m["riassunto"],
        "urgenza": m["urgenza"] or "normale",
        "colore": m["gruppo_colore"],
        "iniziali": iniziali(m["mittente_nome"], m["mittente_indirizzo"]),
    }


def _nome_di(conn: sqlite3.Connection, indirizzo: str) -> str:
    """Il nome con cui quella persona firma le sue mail; se non le ha mai scritto, l'indirizzo."""
    r = conn.execute(
        "SELECT mittente_nome FROM mail WHERE lower(mittente_indirizzo) = lower(?) AND COALESCE(mittente_nome, '') != ''"
        " ORDER BY ricevuta_il DESC LIMIT 1",
        (indirizzo,),
    ).fetchone()
    return r["mittente_nome"] if r else indirizzo


def _da_fare(conn: sqlite3.Connection, imp: dict):
    """Le voci nell'ordine della 18.1; chi chiama si ferma a VOCI_MASSIME."""
    for m in conn.execute(
        SELECT_MAIL_GRUPPO + " WHERE m.sospetta = 1 AND m.sospetta_utente IS NULL ORDER BY m.ricevuta_il DESC, m.id DESC"
    ):
        yield _voce_mail("truffa", m)

    pronte, viste = [], set()
    for m in conn.execute(
        "SELECT m.*, g.colore AS gruppo_colore, b.id AS bozza_id FROM bozze b JOIN mail m ON m.id = b.mail_id"
        " LEFT JOIN gruppi g ON g.id = m.gruppo_id WHERE b.tipo = 'risposta' AND b.stato = 'pronta'"
        " AND m.sospetta = 0 AND m.risposta_data = 0 ORDER BY m.ricevuta_il DESC, m.id DESC, b.id DESC"
    ):
        if m["id"] not in viste:
            viste.add(m["id"])
            pronte.append(m)
    for m in pronte:
        if m["urgenza"] == "alta":
            yield _voce_risposta(m)

    da_smistare = conn.execute(
        "SELECT COUNT(*) FROM mail WHERE smistamento = 'da_controllare' AND sospetta = 0"
    ).fetchone()[0]
    if da_smistare:
        yield {"tipo": "smista", "conteggio": da_smistare}

    for m in pronte:
        if m["urgenza"] != "alta":
            yield _voce_risposta(m)

    for m in conn.execute(
        SELECT_MAIL_GRUPPO + " WHERE m.da_rispondere = 1 AND m.risposta_data = 0 AND m.sospetta = 0"
        " AND NOT EXISTS (SELECT 1 FROM bozze b WHERE b.mail_id = m.id AND b.tipo = 'risposta' AND b.stato = 'pronta')"
        " ORDER BY (m.urgenza = 'alta') DESC, m.ricevuta_il DESC, m.id DESC"
    ):
        yield _voce_mail("rispondi", m)

    for i, giorni, bozza in elenco_aspetti_tu(conn, imp):
        destinatari = json.loads(i["a"] or "[]")
        a = destinatari[0] if destinatari else ""
        nome = _nome_di(conn, a) if a else ""
        yield {
            "tipo": "sollecito",
            "inviata_id": i["id"],
            # "Con la bozza se già preparata": solo una bozza pronta da guardare e mandare.
            "bozza_id": bozza["id"] if bozza is not None and bozza["stato"] == "pronta" else None,
            "a": a,
            "nome": nome,
            "oggetto": i["oggetto"],
            "giorni": giorni,
            "iniziali": iniziali(nome, a),
        }

    oggi = clock.oggi()
    for s in conn.execute(
        "SELECT s.* FROM scadenze s LEFT JOIN mail m ON m.id = s.mail_id WHERE s.stato = 'aperta'"
        " AND s.quando >= ? AND s.quando <= ? AND COALESCE(m.sospetta, 0) = 0"
        " ORDER BY s.quando, s.ora IS NULL, s.ora, s.id",
        (oggi.isoformat(), (oggi + timedelta(days=7)).isoformat()),
    ):
        yield {"tipo": "scadenza", "scadenza_id": s["id"], "descrizione": s["descrizione"], "quando": s["quando"],
               "mail_id": s["mail_id"]}


def _fatte_oggi(conn: sqlite3.Connection, da: str) -> int:
    return conn.execute(
        "SELECT (SELECT COUNT(*) FROM bozze WHERE stato = 'inviata' AND inviata_il >= ?)"
        " + (SELECT COUNT(*) FROM mail WHERE sistemata_il >= ?)"
        " + (SELECT COUNT(*) FROM mail WHERE truffa_chiarita_il >= ?)"
        " + (SELECT COUNT(*) FROM scadenze WHERE stato = 'fatta' AND fatta_il >= ?)",
        (da, da, da, da),
    ).fetchone()[0]


@router.get("/oggi")
def oggi(conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    imp = db.impostazioni(conn)
    giorno = clock.oggi()
    da = clock.iso(clock.inizio_giorno(giorno))

    da_fare = []
    for voce in _da_fare(conn, imp):
        da_fare.append(voce)
        if len(da_fare) == VOCI_MASSIME:
            break

    prossima = conn.execute(
        "SELECT s.* FROM scadenze s LEFT JOIN mail m ON m.id = s.mail_id WHERE s.stato = 'aperta' AND s.quando >= ?"
        " AND COALESCE(m.sospetta, 0) = 0 ORDER BY s.quando, s.ora IS NULL, s.ora, s.id LIMIT 1",
        (giorno.isoformat(),),
    ).fetchone()
    fatte = _fatte_oggi(conn, da)
    return {
        "data": giorno.isoformat(),
        "nome": imp["nome"],
        "mail_oggi": conn.execute("SELECT COUNT(*) FROM mail WHERE ricevuta_il >= ?", (da,)).fetchone()[0],
        "riassunto": riassunto_di_oggi(conn, s),
        "scadenza_prossima": (
            {"id": prossima["id"], "descrizione": prossima["descrizione"], "quando": prossima["quando"],
             "mail_id": prossima["mail_id"]}
            if prossima else None
        ),
        "da_fare": da_fare,
        "fatte_oggi": fatte,
        # 18.1: le voci di da_fare (quelle restituite); "smista" vale quanto il suo conteggio.
        "totale_oggi": fatte + sum(v["conteggio"] if v["tipo"] == "smista" else 1 for v in da_fare),
    }
