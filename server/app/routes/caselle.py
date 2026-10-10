"""Caselle di posta (contratto, sezioni 4 e 15.3)."""

import re
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from .. import clock, forme
from ..auth import richiede_token
from ..caselle import ErroreCasella, finta
from ..db import get_conn
from ..errori import ErroreApi, conflitto, non_trovato, non_valido
from ..schemi import CasellaIn, CasellaPatch, RicollegaIn
from ..servizi import Servizi, servizi

router = APIRouter(dependencies=[Depends(richiede_token)])

INDIRIZZO_VALIDO = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _casella(conn: sqlite3.Connection, casella_id: int) -> sqlite3.Row:
    c = conn.execute("SELECT * FROM caselle WHERE id = ?", (casella_id,)).fetchone()
    if c is None:
        raise non_trovato("Casella non trovata.")
    return c


def _errore_casella(e: ErroreCasella) -> ErroreApi:
    if e.tipo == "credenziali":
        return ErroreApi(422, e.messaggio)
    return ErroreApi(502, e.messaggio)


@router.get("/caselle")
def elenco(conn: sqlite3.Connection = Depends(get_conn)):
    return [forme.casella(c) for c in conn.execute("SELECT * FROM caselle ORDER BY id")]


@router.post("/caselle")
def aggiungi(corpo: CasellaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    ora = clock.now()
    if corpo.tipo == "finta":
        cur = conn.execute(
            "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il) VALUES ('finta', ?, ?, 'collegata', ?, ?)",
            (finta.INDIRIZZO, corpo.nome.strip() or "Prova", clock.iso(finta.attiva_dal(ora)), clock.iso(ora)),
        )
        s.fabbrica.in_sottofondo(s.lavoro.controlla_casella, cur.lastrowid)
        return JSONResponse(status_code=201, content=forme.casella(_casella(conn, cur.lastrowid)))

    if corpo.tipo == "gmail":
        indirizzo = (corpo.indirizzo or "").strip().lower()
        if not INDIRIZZO_VALIDO.match(indirizzo):
            raise non_valido("Scrivi l'indirizzo Gmail completo, per esempio studio@esempio.it.")
        if not (corpo.password_app or "").strip():
            raise non_valido("Manca la password per le app di Google.")
        if conn.execute("SELECT 1 FROM caselle WHERE indirizzo = ? AND tipo != 'finta'", (indirizzo,)).fetchone():
            raise conflitto("Questa casella è già collegata a Sol.")
        try:
            s.fabbrica.gmail(indirizzo, corpo.password_app).verifica()
        except ErroreCasella as e:
            raise _errore_casella(e) from e
        cur = conn.execute(
            "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il, segreto_cifrato)"
            " VALUES ('gmail', ?, ?, 'collegata', ?, ?, ?)",
            (indirizzo, corpo.nome.strip() or indirizzo, clock.iso(ora), clock.iso(ora),
             s.cifrario.cifra(corpo.password_app.strip())),
        )
        s.fabbrica.in_sottofondo(s.lavoro.controlla_casella, cur.lastrowid)
        return JSONResponse(status_code=201, content=forme.casella(_casella(conn, cur.lastrowid)))

    # Outlook: il collegamento si completa in sottofondo quando l'utente ha fatto l'accesso.
    if not s.settings.ms_client_id:
        raise non_valido("Il collegamento con Outlook non è ancora configurato sul server.")
    cur = conn.execute(
        "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il, in_collegamento_dal)"
        " VALUES ('outlook', '', ?, 'in_collegamento', ?, ?, ?)",
        (corpo.nome.strip() or "Outlook", clock.iso(ora), clock.iso(ora), clock.iso(ora)),
    )
    casella_id = cur.lastrowid
    try:
        flusso = s.fabbrica.avvia_outlook(casella_id)
    except ErroreCasella as e:
        conn.execute("DELETE FROM caselle WHERE id = ?", (casella_id,))
        raise ErroreApi(502, e.messaggio) from e
    return _risposta_collegamento(conn, casella_id, flusso, ora)


def _risposta_collegamento(conn: sqlite3.Connection, casella_id: int, flusso: dict, ora) -> JSONResponse:
    scade = ora + timedelta(seconds=int(flusso.get("expires_in") or 900))
    return JSONResponse(
        status_code=202,
        content={
            "casella": forme.casella(_casella(conn, casella_id)),
            "codice_utente": flusso["user_code"],
            "url_verifica": flusso.get("verification_uri") or "https://microsoft.com/devicelogin",
            "scade_il": clock.per_utente(clock.iso(scade)),
        },
    )


@router.get("/caselle/{casella_id}")
def dettaglio(casella_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return forme.casella(_casella(conn, casella_id))


@router.patch("/caselle/{casella_id}")
def rinomina(casella_id: int, corpo: CasellaPatch, conn: sqlite3.Connection = Depends(get_conn)):
    _casella(conn, casella_id)
    conn.execute("UPDATE caselle SET nome = ? WHERE id = ?", (corpo.nome.strip(), casella_id))
    return forme.casella(_casella(conn, casella_id))


@router.delete("/caselle/{casella_id}")
def elimina(casella_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    """Scollega e cancella dal SERVER le mail di questa casella. Nella casella vera non
    si tocca niente: Sol non cancella mai mail."""
    _casella(conn, casella_id)
    conn.execute("DELETE FROM caselle WHERE id = ?", (casella_id,))
    finta.REGISTRO.pop(casella_id, None)
    s.fabbrica.dimentica(casella_id)
    return {"ok": True}


@router.post("/caselle/{casella_id}/controlla", status_code=202)
def controlla(casella_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    c = _casella(conn, casella_id)
    if c["stato"] != "in_collegamento":
        s.fabbrica.in_sottofondo(s.lavoro.controlla_casella, casella_id)
    return forme.casella(_casella(conn, casella_id))


@router.post("/caselle/{casella_id}/ricollega")
def ricollega(casella_id: int, corpo: RicollegaIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
              s: Servizi = Depends(servizi)):
    """Contratto 15.3: si rimette a posto l'accesso senza perdere niente. Mail, bozze,
    scadenze, regole, attiva_dal e il punto da cui riprendere restano: le mail arrivate
    mentre la casella era in errore entrano al prossimo controllo."""
    c = _casella(conn, casella_id)
    ora = clock.now()
    if c["tipo"] == "finta":
        conn.execute(
            "UPDATE caselle SET stato = 'collegata', errore = NULL, errore_tipo = NULL WHERE id = ?", (casella_id,)
        )
        s.fabbrica.in_sottofondo(s.lavoro.controlla_casella, casella_id)
        return forme.casella(_casella(conn, casella_id))
    if c["tipo"] == "gmail":
        password = ((corpo.password_app if corpo else None) or "").strip()
        if not password:
            raise non_valido("Manca la nuova password per le app di Google.", campo="password_app")
        try:
            s.fabbrica.gmail(c["indirizzo"], password).verifica()
        except ErroreCasella as e:
            raise _errore_casella(e) from e
        conn.execute(
            "UPDATE caselle SET stato = 'collegata', errore = NULL, errore_tipo = NULL, segreto_cifrato = ? WHERE id = ?",
            (s.cifrario.cifra(password), casella_id),
        )
        s.fabbrica.in_sottofondo(s.lavoro.controlla_casella, casella_id)
        return forme.casella(_casella(conn, casella_id))
    # Outlook: un nuovo accesso con il codice; il resto lo completa il server da solo.
    if not s.settings.ms_client_id:
        raise non_valido("Il collegamento con Outlook non è ancora configurato sul server.")
    if c["stato"] == "in_collegamento":
        raise conflitto("Il collegamento di questa casella è già in corso.")
    conn.execute(
        "UPDATE caselle SET stato = 'in_collegamento', errore = NULL, errore_tipo = NULL, in_collegamento_dal = ?"
        " WHERE id = ?",
        (clock.iso(ora), casella_id),
    )
    s.fabbrica.dimentica(casella_id)
    try:
        flusso = s.fabbrica.avvia_outlook(casella_id, ricollega=True)
    except ErroreCasella as e:
        conn.execute(
            "UPDATE caselle SET stato = 'errore', errore = ?, errore_tipo = 'credenziali', in_collegamento_dal = NULL"
            " WHERE id = ?",
            (e.messaggio, casella_id),
        )
        raise ErroreApi(502, e.messaggio) from e
    return _risposta_collegamento(conn, casella_id, flusso, ora)
