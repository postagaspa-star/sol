"""Bozze (contratto, sezioni 7, 15.5, 15.7, 15.11, 15.13).

Qui c'è l'UNICO punto del server che invia mail: POST /api/bozze/{id}/invia con
{"conferma": true, "versione": N}. Nessun altro percorso (lavoro in sottofondo,
scrittori, decisore) chiama invia_risposta.

Niente doppioni: prima di spedire la bozza riceve un Message-ID fisso, salvato nel
database insieme al segno "in fase di invio". Se l'invio fallisce in modo ambiguo (o il
server si riavvia a metà), al tentativo dopo si cerca prima quel Message-ID in Posta
inviata. Mentre una bozza è in fase di invio, modificarla, rifarla o scartarla dà 409.
Si invia solo la versione che l'utente ha visto: se nel frattempo è cambiata, 409."""

import json
import logging
import sqlite3
from typing import Literal

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from .. import clock, db, forme
from ..auth import richiede_token
from ..caselle import ErroreCasella, Invio
from ..caselle.testo import oggetto_risposta, riga
from ..db import get_conn
from ..errori import ErroreApi, conflitto, non_trovato, non_valido
from ..lavoro import riferimento
from ..schemi import BozzaPut, InviaIn, IstruzioniIn
from ..servizi import Servizi, servizi
from .comuni import LUCCHETTO_BOZZE, connettore_della_casella, indirizzi_validi, richiedi_scrittore

log = logging.getLogger("uvicorn.error")

router = APIRouter(dependencies=[Depends(richiede_token)])

GIA_INVIATA = "Questa bozza è già stata inviata."
IN_INVIO = "Questa bozza è in fase di invio: aspetta qualche secondo."
CAMBIATA = "La risposta è cambiata dopo che l'hai controllata: ricontrollala prima di inviarla."
NON_SO_SE_PARTITA = "Non riesco a capire se la mail è già partita. Guarda in Posta inviata prima di riprovare."
MODIFICATA_SENZA_ISTRUZIONI = (
    "Hai modificato questa bozza a mano: scrivi cosa cambiare, oppure chiedi di rifarla da zero."
)
INVIO_INTERROTTO = (
    "L'invio si è interrotto per un problema di Sol. Prima di riprovare Sol controlla in Posta inviata "
    "se la mail è partita."
)


def _bozza(conn: sqlite3.Connection, bozza_id: int) -> sqlite3.Row:
    b = conn.execute("SELECT * FROM bozze WHERE id = ?", (bozza_id,)).fetchone()
    if b is None:
        raise non_trovato("Bozza non trovata.")
    return b


def _iniziali(conn: sqlite3.Connection, b: sqlite3.Row) -> tuple[list[str], str]:
    """Destinatari e oggetto di partenza, come li prepara Sol."""
    if b["tipo"] == "risposta":
        m = conn.execute("SELECT mittente_indirizzo, oggetto FROM mail WHERE id = ?", (b["mail_id"],)).fetchone()
        if m is None:
            return json.loads(b["a"] or "[]"), b["oggetto"]
        return ([m["mittente_indirizzo"]] if m["mittente_indirizzo"] else []), oggetto_risposta(m["oggetto"])
    i = conn.execute("SELECT a, oggetto FROM inviate WHERE id = ?", (b["inviata_id"],)).fetchone()
    if i is None:
        return json.loads(b["a"] or "[]"), b["oggetto"]
    return json.loads(i["a"] or "[]"), oggetto_risposta(i["oggetto"])


def rifai_bozza(conn: sqlite3.Connection, bozza_id: int, istruzioni: str | None, da_zero: bool) -> sqlite3.Row:
    """Contratto 15.5 e 15.13: si riparte dalla bozza ATTUALE (con le modifiche fatte a mano)
    e si applicano le istruzioni. Senza istruzioni, una bozza modificata a mano si rifà da
    zero solo con da_zero; altrimenti 409 (non si butta via il lavoro dell'utente)."""
    istruzioni = (istruzioni or "").strip() or None
    with LUCCHETTO_BOZZE:
        b = _bozza(conn, bozza_id)
        if b["stato"] == "inviata":
            raise conflitto(GIA_INVIATA)
        if b["in_invio_dal"]:
            raise conflitto(IN_INVIO)
        if not istruzioni and b["modificata"] and not da_zero:
            raise conflitto(MODIFICATA_SENZA_ISTRUZIONI)
        dall_attuale = bool(istruzioni) and not da_zero and bool((b["testo"] or "").strip())
        campi = {
            "stato": "in_preparazione", "istruzioni": istruzioni, "richiesta_utente": 1, "errore": None,
            "tentativi": 0, "prossimo_tentativo": None, "rifai_da": "attuale" if dall_attuale else "zero",
        }
        if da_zero:
            a, oggetto = _iniziali(conn, b)
            campi.update(a=json.dumps(a), cc="[]", oggetto=oggetto, modificata=0)
        assegnazioni = ", ".join(f"{k} = ?" for k in campi)
        cur = conn.execute(
            f"UPDATE bozze SET {assegnazioni}, versione = versione + 1"
            " WHERE id = ? AND stato != 'inviata' AND in_invio_dal IS NULL",
            (*campi.values(), bozza_id),
        )
        if cur.rowcount == 0:
            raise conflitto(IN_INVIO)
    return _bozza(conn, bozza_id)


@router.post("/mail/{mail_id}/bozza", status_code=202)
def rifai_della_mail(mail_id: int, corpo: IstruzioniIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
                     s: Servizi = Depends(servizi)):
    m = conn.execute("SELECT * FROM mail WHERE id = ?", (mail_id,)).fetchone()
    if m is None:
        raise non_trovato("Mail non trovata.")
    richiedi_scrittore(conn, s)
    corpo = corpo or IstruzioniIn()
    aperta = conn.execute(
        "SELECT id FROM bozze WHERE mail_id = ? AND tipo = 'risposta' AND stato IN ('in_preparazione', 'pronta', 'errore')"
        " ORDER BY id DESC LIMIT 1",
        (mail_id,),
    ).fetchone()
    if aperta is not None:
        b = rifai_bozza(conn, aperta["id"], corpo.istruzioni, corpo.da_zero)
    else:
        istruzioni = (corpo.istruzioni or "").strip() or None
        cur = conn.execute(
            "INSERT INTO bozze (mail_id, tipo, stato, a, oggetto, istruzioni, richiesta_utente, rifai_da, creata_il)"
            " VALUES (?, 'risposta', 'in_preparazione', ?, ?, ?, 1, 'zero', ?)",
            (mail_id, json.dumps([m["mittente_indirizzo"]] if m["mittente_indirizzo"] else []),
             oggetto_risposta(m["oggetto"]), istruzioni, clock.adesso()),
        )
        b = _bozza(conn, cur.lastrowid)
    s.lavoro.sveglia("scrittore")
    return forme.bozza(b)


@router.post("/bozze/{bozza_id}/rifai", status_code=202)
def rifai(bozza_id: int, corpo: IstruzioniIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
          s: Servizi = Depends(servizi)):
    _bozza(conn, bozza_id)
    richiedi_scrittore(conn, s)
    corpo = corpo or IstruzioniIn()
    b = rifai_bozza(conn, bozza_id, corpo.istruzioni, corpo.da_zero)
    s.lavoro.sveglia("scrittore")
    return forme.bozza(b)


@router.get("/bozze")
def elenco(stato: Literal["in_preparazione", "pronta", "inviata", "scartata", "errore"] | None = None,
           conn: sqlite3.Connection = Depends(get_conn)):
    if stato:
        righe = conn.execute("SELECT * FROM bozze WHERE stato = ? ORDER BY id DESC LIMIT 200", (stato,))
    else:
        righe = conn.execute("SELECT * FROM bozze ORDER BY id DESC LIMIT 200")
    return [forme.bozza(b) for b in righe]


@router.get("/bozze/{bozza_id}")
def dettaglio(bozza_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return forme.bozza(_bozza(conn, bozza_id))


@router.put("/bozze/{bozza_id}")
def modifica(bozza_id: int, corpo: BozzaPut, conn: sqlite3.Connection = Depends(get_conn)):
    campi: dict = {}
    if corpo.a is not None:
        campi["a"] = json.dumps(indirizzi_validi(corpo.a, "a"))
    if corpo.cc is not None:
        campi["cc"] = json.dumps(indirizzi_validi(corpo.cc, "cc"))
    if corpo.oggetto is not None:
        campi["oggetto"] = riga(corpo.oggetto)  # un oggetto non va mai a capo
    if corpo.testo is not None:
        campi["testo"] = corpo.testo
    with LUCCHETTO_BOZZE:
        b = _bozza(conn, bozza_id)
        if b["stato"] == "inviata":
            raise conflitto(GIA_INVIATA)
        if b["in_invio_dal"]:
            raise conflitto(IN_INVIO)
        if b["stato"] == "in_preparazione":
            raise conflitto("La bozza è ancora in preparazione. Aspetta qualche secondo.")
        if b["stato"] == "scartata":
            raise conflitto("Questa bozza è stata scartata.")
        # Una bozza in errore (scrittura o invio non riusciti) torna pronta quando la si corregge.
        campi.update(modificata=1, stato="pronta", errore=None)
        assegnazioni = ", ".join(f"{k} = ?" for k in campi)
        cur = conn.execute(
            f"UPDATE bozze SET {assegnazioni}, versione = versione + 1"
            " WHERE id = ? AND in_invio_dal IS NULL AND stato IN ('pronta', 'errore')",
            (*campi.values(), bozza_id),
        )
        if cur.rowcount == 0:
            raise conflitto(IN_INVIO)
    return forme.bozza(_bozza(conn, bozza_id))


def _segna_inviata(conn: sqlite3.Connection, b: sqlite3.Row) -> None:
    with db.transazione(conn):
        conn.execute(
            "UPDATE bozze SET stato = 'inviata', inviata_il = ?, errore = NULL, in_invio_dal = NULL, invio_ambiguo = 0"
            " WHERE id = ?",
            (clock.adesso(), b["id"]),
        )
        if b["tipo"] == "risposta":
            conn.execute("UPDATE mail SET risposta_data = 1 WHERE id = ?", (b["mail_id"],))


def _errore_invio(conn: sqlite3.Connection, bozza_id: int, frase: str) -> JSONResponse:
    return JSONResponse(
        status_code=502,
        content={"errore": frase, "codice": "casella_non_raggiungibile", "bozza": forme.bozza(_bozza(conn, bozza_id))},
    )


@router.post("/bozze/{bozza_id}/invia")
def invia(bozza_id: int, corpo: InviaIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
          s: Servizi = Depends(servizi)):
    if corpo is None or corpo.conferma is not True:
        raise non_valido("Per inviare serve la conferma esplicita.", campo="conferma")
    if corpo.versione is None:
        raise non_valido("Manca la versione della risposta che hai controllato.", campo="versione")
    with LUCCHETTO_BOZZE:
        b = _bozza(conn, bozza_id)
        if b["stato"] == "inviata":
            raise conflitto(GIA_INVIATA)
        if b["in_invio_dal"]:
            raise conflitto(IN_INVIO)
        if b["stato"] in ("in_preparazione", "scartata"):
            raise conflitto("Questa bozza non si può inviare adesso.")
        if b["versione"] != corpo.versione:
            raise conflitto(CAMBIATA, extra={"bozza": forme.bozza(b)})
        a, cc = json.loads(b["a"] or "[]"), json.loads(b["cc"] or "[]")
        if not a:
            raise non_valido("Manca il destinatario.", campo="a")
        if not (b["testo"] or "").strip():
            raise non_valido("Il testo della bozza è vuoto.", campo="testo")
        if b["tipo"] == "risposta":
            origine = conn.execute("SELECT * FROM mail WHERE id = ?", (b["mail_id"],)).fetchone()
        else:
            origine = conn.execute("SELECT * FROM inviate WHERE id = ?", (b["inviata_id"],)).fetchone()
        if origine is None:
            raise non_trovato("La mail a cui risponde questa bozza non c'è più.")
        connettore = connettore_della_casella(conn, s, origine["casella_id"])
        if b["invio_ambiguo"]:
            # Il tentativo di prima può essere partito: prima si guarda in Posta inviata.
            if not b["id_messaggio"]:
                raise ErroreApi(502, NON_SO_SE_PARTITA)
            try:
                gia = connettore.gia_inviata(b["id_messaggio"])
            except ErroreCasella as e:
                raise ErroreApi(502, NON_SO_SE_PARTITA) from e
            if gia:
                _segna_inviata(conn, b)
                return {**forme.bozza(_bozza(conn, bozza_id)), "gia_inviata": True}
        id_messaggio = b["id_messaggio"] or connettore.nuovo_id_messaggio()
        cur = conn.execute(
            "UPDATE bozze SET in_invio_dal = ?, id_messaggio = ? WHERE id = ? AND in_invio_dal IS NULL"
            " AND stato IN ('pronta', 'errore') AND versione = ?",
            (clock.adesso(), id_messaggio, bozza_id, corpo.versione),
        )
        if cur.rowcount == 0:
            raise conflitto(IN_INVIO)

    def salva(nuovo_id_messaggio, esterno):
        conn.execute(
            "UPDATE bozze SET id_messaggio = ?, bozza_esterna = ? WHERE id = ?",
            (nuovo_id_messaggio, esterno, bozza_id),
        )

    # La rete fuori dal lucchetto: la bozza è segnata "in fase di invio", nessuno la tocca.
    try:
        connettore.invia_risposta(
            riferimento(origine), a, cc, riga(b["oggetto"]) or "Re:", b["testo"],
            Invio(id_messaggio, b["bozza_esterna"], salva),
        )
    except ErroreCasella as e:
        conn.execute(
            "UPDATE bozze SET stato = 'errore', errore = ?, invio_ambiguo = ?, in_invio_dal = NULL WHERE id = ?",
            (e.messaggio, 1 if e.ambiguo else 0, bozza_id),
        )
        return _errore_invio(conn, bozza_id, e.messaggio)
    except Exception:
        log.exception("invio della bozza %s interrotto", bozza_id)
        conn.execute(
            "UPDATE bozze SET stato = 'errore', errore = ?, invio_ambiguo = 1, in_invio_dal = NULL WHERE id = ?",
            (INVIO_INTERROTTO, bozza_id),
        )
        return _errore_invio(conn, bozza_id, INVIO_INTERROTTO)
    with LUCCHETTO_BOZZE:
        _segna_inviata(conn, b)
    s.fabbrica.in_sottofondo(s.lavoro.controlla_casella, origine["casella_id"])
    return {**forme.bozza(_bozza(conn, bozza_id)), "gia_inviata": False}


@router.post("/bozze/{bozza_id}/scarta")
def scarta(bozza_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    with LUCCHETTO_BOZZE:
        b = _bozza(conn, bozza_id)
        if b["stato"] == "inviata":
            raise conflitto(GIA_INVIATA)
        if b["in_invio_dal"]:
            raise conflitto(IN_INVIO)
        cur = conn.execute(
            "UPDATE bozze SET stato = 'scartata', versione = versione + 1"
            " WHERE id = ? AND in_invio_dal IS NULL AND stato != 'inviata'",
            (bozza_id,),
        )
        if cur.rowcount == 0:
            raise conflitto(IN_INVIO)
    if b["bozza_esterna"]:
        # In Outlook la risposta preparata da Sol non deve restare orfana in Bozze.
        origine = conn.execute(
            "SELECT casella_id FROM mail WHERE id = ?" if b["tipo"] == "risposta" else "SELECT casella_id FROM inviate WHERE id = ?",
            (b["mail_id"] if b["tipo"] == "risposta" else b["inviata_id"],),
        ).fetchone()
        if origine is not None:
            try:
                connettore_della_casella(conn, s, origine["casella_id"]).ripulisci_bozza_sol(b["bozza_esterna"])
            except (ErroreApi, ErroreCasella):
                log.warning("bozza %s: non sono riuscito a togliere la bozza dalla casella", bozza_id)
    return forme.bozza(_bozza(conn, bozza_id))
