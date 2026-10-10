"""Pezzi usati da più route."""

import re
import sqlite3
import threading

from .. import db
from ..caselle import ErroreCasella
from ..errori import ErroreApi, non_trovato, non_valido
from ..servizi import Servizi

INDIRIZZO = re.compile(r"^[^@\s,;<>]+@[^@\s,;<>]+\.[^@\s,;<>]+$")

# Un lucchetto per le bozze: invio, modifica, rifai e scarta controllano e cambiano lo
# stato sotto lo stesso lucchetto, così una modifica non si infila a metà di un invio
# (contratto 15.7). La rete (SMTP, Graph) si fa fuori dal lucchetto.
LUCCHETTO_BOZZE = threading.Lock()


def richiedi_scrittore(conn: sqlite3.Connection, s: Servizi) -> None:
    """503 solo se non c'è nessuno scrittore configurato e raggiungibile (contratto,
    sezione 1). I fallimenti dei lavori in sottofondo non bloccano le richieste dell'utente."""
    imp = db.impostazioni(conn)
    pronto = s.decisore.salute()["stato"] == "pronto"
    if not s.scrittore.puo_scrivere(imp["scrittori"], pronto):
        raise ErroreApi(
            503,
            "In questo momento nessun servizio di scrittura è disponibile. Riprova tra qualche minuto.",
        )


def connettore_della_casella(conn: sqlite3.Connection, s: Servizi, casella_id: int):
    c = conn.execute("SELECT * FROM caselle WHERE id = ?", (casella_id,)).fetchone()
    if c is None:
        raise non_trovato("La casella di questa mail non c'è più.")
    if c["stato"] == "in_collegamento":
        raise ErroreApi(502, "Questa casella non ha ancora finito il collegamento.")
    try:
        return s.fabbrica.per(c)
    except ErroreCasella as e:
        raise ErroreApi(502, e.messaggio) from e


def indirizzi_validi(lista: list[str], campo: str) -> list[str]:
    puliti = []
    for a in lista:
        a = (a or "").strip().lower()
        if not a:
            continue
        if not INDIRIZZO.match(a):
            etichetta = "dei destinatari in copia" if campo == "cc" else "dei destinatari"
            raise non_valido(f"L'indirizzo «{a}» {etichetta} non è scritto bene.", campo=campo)
        if a not in puliti:
            puliti.append(a)
    return puliti
