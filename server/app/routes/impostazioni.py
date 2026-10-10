"""Impostazioni (sezione 11), novità per le notifiche (12, 15.6), arretrato da spostare
(15.2) e conteggi per i filtri (15.4)."""

import sqlite3
from datetime import timedelta

from fastapi import APIRouter, Depends

from .. import clock, db, forme
from ..auth import richiede_token
from ..db import get_conn
from ..errori import conflitto, non_valido
from ..schemi import ConfermaIn, ImpostazioniPatch
from ..servizi import Servizi, servizi

router = APIRouter(dependencies=[Depends(richiede_token)])

CHIAVI = list(db.IMPOSTAZIONI_INIZIALI)

# L'arretrato: mail già smistate (o corrette) prima che la prova si spegnesse, mai
# spostate nella casella e non ancora chieste dall'utente.
ARRETRATO = (
    " FROM mail WHERE smistamento IN ('smistata', 'corretta') AND applicata_in_casella = 0 AND applica = 0"
    " AND gruppo_id IS NOT NULL"
)


def _uscita(conn: sqlite3.Connection) -> dict:
    imp = db.impostazioni(conn)
    return {k: imp[k] for k in CHIAVI}


@router.get("/impostazioni")
def leggi(conn: sqlite3.Connection = Depends(get_conn)):
    return _uscita(conn)


@router.patch("/impostazioni")
def cambia(corpo: ImpostazioniPatch, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cambi = corpo.model_dump(exclude_none=True)
    if "scrittori" in cambi:
        cambi["scrittori"] = list(dict.fromkeys(cambi["scrittori"]))
    if "firma" in cambi:
        cambi["firma"] = cambi["firma"].strip()
    if "tono" in cambi:
        cambi["tono"] = cambi["tono"].strip()
        if not cambi["tono"]:
            raise non_valido("Il tono non può essere vuoto né più lungo di 100 caratteri.", campo="tono")
    with db.transazione(conn):
        for chiave, valore in cambi.items():
            db.salva_impostazione(conn, chiave, valore)
    # Spegnere la prova NON sposta l'arretrato (contratto 15.2): da qui in poi si spostano
    # solo le mail smistate dopo; le altre solo con POST /api/arretrato/applica.
    if "intervallo_controllo_minuti" in cambi:
        s.lavoro.sveglia("posta")
    return _uscita(conn)


@router.get("/arretrato")
def arretrato(conn: sqlite3.Connection = Depends(get_conn)):
    return {"da_applicare": conn.execute("SELECT COUNT(*)" + ARRETRATO).fetchone()[0]}


@router.post("/arretrato/applica", status_code=202)
def applica_arretrato(corpo: ConfermaIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
                      s: Servizi = Depends(servizi)):
    if corpo is None or corpo.conferma is not True:
        raise non_valido("Per spostare le mail già smistate serve la conferma esplicita.", campo="conferma")
    if db.impostazioni(conn)["modalita_prova"]:
        raise conflitto("Prima spegni la modalità prova: in prova Sol non sposta niente.")
    cur = conn.execute("UPDATE mail SET applica = 1 WHERE id IN (SELECT id" + ARRETRATO + ")")
    # Anche qui: ogni mail si sposta solo se è ancora in Posta in arrivo.
    s.fabbrica.in_sottofondo(s.lavoro.applica_in_caselle)
    return {"da_applicare": cur.rowcount}


@router.get("/conteggi")
def conteggi(conn: sqlite3.Connection = Depends(get_conn)):
    """Contratto 15.4 (le mail tolte dalla pulizia non ci sono più nel database)."""

    def numero(sql: str) -> int:
        return conn.execute(sql).fetchone()[0]

    gruppi = {
        str(r["id"]): {"da_leggere": r["da_leggere"], "totale": r["totale"]}
        for r in conn.execute(
            "SELECT g.id, (SELECT COUNT(*) FROM mail m WHERE m.gruppo_id = g.id AND m.letta = 0) AS da_leggere,"
            " (SELECT COUNT(*) FROM mail m WHERE m.gruppo_id = g.id) AS totale FROM gruppi g ORDER BY g.ordine, g.id"
        )
    }
    return {
        "da_rispondere": numero(
            "SELECT COUNT(*) FROM mail WHERE da_rispondere = 1 AND risposta_data = 0 AND sospetta = 0"
        ),
        "urgenti": numero(
            "SELECT COUNT(*) FROM mail WHERE urgenza = 'alta' AND sospetta = 0"
            " AND (letta = 0 OR (da_rispondere = 1 AND risposta_data = 0))"
        ),
        "sospette": numero("SELECT COUNT(*) FROM mail WHERE sospetta = 1"),  # 16.2: non ancora chiarite
        "da_controllare": numero("SELECT COUNT(*) FROM mail WHERE smistamento = 'da_controllare'"),
        "da_leggere": numero("SELECT COUNT(*) FROM mail WHERE letta = 0"),
        "bozze_pronte": numero("SELECT COUNT(*) FROM bozze WHERE stato = 'pronta'"),
        "gruppi": gruppi,
    }


@router.get("/novita")
def novita(dal: str | None = None, conn: sqlite3.Connection = Depends(get_conn)):
    cursore = db.seq_attuale(conn)
    vuoto = {"cursore": str(cursore), "urgenti": [], "da_rispondere_nuove": 0, "bozze_pronte": 0,
             "bozze_nuove": [], "scadenze_domani": []}
    if dal is None or dal == "":
        return vuoto
    if not dal.isdigit() or len(dal) > 15:
        raise non_valido("Il cursore delle novità non va bene: richiedi le novità senza cursore.", campo="dal")
    dal_n = int(dal)
    urgenti = conn.execute(
        forme.SELECT_MAIL + " WHERE m.seq_valutata > ? AND m.urgenza = 'alta' AND m.sospetta = 0"
        " ORDER BY m.ricevuta_il DESC LIMIT 20",
        (dal_n,),
    ).fetchall()
    domani = (clock.oggi() + timedelta(days=1)).isoformat()
    return {
        "cursore": str(cursore),
        "urgenti": forme.mail_lista(conn, urgenti),
        "da_rispondere_nuove": conn.execute(
            "SELECT COUNT(*) FROM mail WHERE seq_valutata > ? AND da_rispondere = 1 AND sospetta = 0", (dal_n,)
        ).fetchone()[0],
        # 15.6 e 15.13: bozze_pronte è il totale; per notificare si usa solo bozze_nuove.
        "bozze_pronte": conn.execute("SELECT COUNT(*) FROM bozze WHERE stato = 'pronta'").fetchone()[0],
        "bozze_nuove": [
            {"id": b["id"], "mail_id": b["mail_id"], "oggetto": b["oggetto"]}
            for b in conn.execute(
                "SELECT id, mail_id, oggetto FROM bozze WHERE seq_pronta > ? AND stato = 'pronta' ORDER BY id", (dal_n,)
            )
        ],
        "scadenze_domani": [
            forme.scadenza(r)
            for r in conn.execute("SELECT * FROM scadenze WHERE stato = 'aperta' AND quando = ? ORDER BY ora, id", (domani,))
        ],
    }
