"""Scelta delle mail pertinenti per un riassunto a richiesta ("le ultime mail sul CRM",
"cosa ha scritto Bianchi", "le fatture di questa settimana").

Niente modelli qui: parole della richiesta cercate in mittente, oggetto, riassunto e
corpo, più i gruppi nominati e il periodo ("oggi", "ieri", "settimana", "mese").
Al massimo 40 mail, le più pertinenti e poi le più recenti."""

import re
import sqlite3
from datetime import timedelta

from . import clock

MASSIMO = 40

PAROLE_VUOTE = {
    "che", "chi", "cosa", "come", "con", "del", "della", "delle", "dei", "degli", "dello", "dal", "dalla",
    "dalle", "dai", "nel", "nella", "nelle", "nei", "sul", "sulla", "sulle", "sui", "per", "tra", "fra",
    "una", "uno", "gli", "le", "lo", "la", "il", "di", "da", "in", "su", "ha", "hanno", "ho", "mi", "ci",
    "mail", "email", "posta", "messaggi", "messaggio", "ultime", "ultima", "ultimi", "ultimo", "tutte",
    "tutti", "quali", "quale", "sono", "arrivate", "arrivati", "arrivata", "ricevute", "ricevuto",
    "scritto", "scritta", "scrive", "detto", "riassunto", "riassumi", "dimmi", "fammi", "vedere",
    "mostra", "cerca", "trova", "parlano", "parla", "riguardano", "riguarda", "questa", "questo",
    "questi", "queste", "quella", "quello", "oggi", "ieri", "settimana", "mese", "giorni", "giorno",
    "scorsa", "scorso", "nuove", "nuovi", "importanti", "anche", "sempre", "più", "piu", "non",
    "and", "the",
}


def _parole(testo: str) -> list[str]:
    return [p for p in re.findall(r"[a-zà-ù0-9]+", testo.lower()) if len(p) >= 3 and p not in PAROLE_VUOTE]


def _radice(parola: str) -> str:
    # "fatture" trova anche "fattura", "offerte" anche "offerta": basta l'inizio.
    return parola[:5] if len(parola) > 5 else parola


def _periodo(testo: str):
    oggi = clock.oggi()
    t = testo.lower()
    if "oggi" in t:
        return clock.inizio_giorno(oggi), None
    if "ieri" in t:
        return clock.inizio_giorno(oggi - timedelta(days=1)), clock.inizio_giorno(oggi)
    if "settimana" in t:
        return clock.now() - timedelta(days=7), None
    if "mese" in t:
        return clock.now() - timedelta(days=30), None
    return None, None


def _gruppi_nominati(conn: sqlite3.Connection, parole: list[str]) -> set[int]:
    trovati = set()
    for g in conn.execute("SELECT id, nome FROM gruppi"):
        pezzi = [p for p in re.findall(r"[a-zà-ù]+", g["nome"].lower()) if len(p) >= 3 and p not in PAROLE_VUOTE]
        if any(_radice(p) == _radice(pz) for p in parole for pz in pezzi):
            trovati.add(g["id"])
    return trovati


def seleziona(conn: sqlite3.Connection, richiesta: str) -> list[sqlite3.Row]:
    parole = _parole(richiesta)
    dal, al = _periodo(richiesta)
    # Un gruppo nominato ("le fatture", "le pec") dà punti alle sue mail ma non esclude
    # le altre: la mail "Fattura n. 112" va trovata anche se non è ancora smistata.
    gruppi = _gruppi_nominati(conn, parole)

    condizioni, valori = [], []
    if dal is not None:
        condizioni.append("m.ricevuta_il >= ?")
        valori.append(clock.iso(dal))
    if al is not None:
        condizioni.append("m.ricevuta_il < ?")
        valori.append(clock.iso(al))
    dove = (" WHERE " + " AND ".join(condizioni)) if condizioni else ""
    candidate = conn.execute(
        "SELECT m.* FROM mail m" + dove + " ORDER BY m.ricevuta_il DESC, m.id DESC LIMIT 1000", valori
    ).fetchall()
    if not parole:
        return candidate[:MASSIMO]

    punteggi = []
    for posizione, m in enumerate(candidate):
        mittente = f"{m['mittente_nome']} {m['mittente_indirizzo']}".lower()
        oggetto = (m["oggetto"] or "").lower()
        riassunto = (m["riassunto"] or "").lower()
        corpo = (m["corpo"] or "").lower()
        punti = 3 if m["gruppo_id"] in gruppi else 0
        for p in parole:
            r = _radice(p)
            punti += 3 * (r in mittente) + 2 * (r in oggetto) + 2 * (r in riassunto) + 1 * (r in corpo)
        if punti:
            punteggi.append((-punti, posizione, m))
    punteggi.sort(key=lambda t: (t[0], t[1]))
    return [m for _, _, m in punteggi[:MASSIMO]]
