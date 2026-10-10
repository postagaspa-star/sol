"""Mail (contratto, sezioni 6, 15.2, 15.8, 15.13)."""

import sqlite3
from typing import Literal

from fastapi import APIRouter, Depends, Query

from .. import db, forme, smista
from ..auth import richiede_token
from ..caselle import ErroreCasella
from ..db import get_conn
from ..errori import ErroreApi, conflitto, non_trovato
from ..lavoro import LUCCHETTO_SPOSTA, riferimento
from ..schemi import LettaIn, RispondereIn, SpostaIn
from ..servizi import Servizi, servizi
from .comuni import connettore_della_casella
from .gruppi import salva_regola

router = APIRouter(dependencies=[Depends(richiede_token)])


def _mail(conn: sqlite3.Connection, mail_id: int) -> sqlite3.Row:
    m = forme.leggi_mail(conn, mail_id)
    if m is None:
        raise non_trovato("Mail non trovata.")
    return m


@router.get("/mail")
def elenco(
    gruppo_id: int | None = None,
    smistamento: Literal["in_attesa", "smistata", "da_controllare", "corretta"] | None = None,
    da_rispondere: bool | None = None,
    urgenza: Literal["alta", "normale", "bassa"] | None = None,
    letta: bool | None = None,
    casella_id: int | None = None,
    cerca: str | None = Query(default=None, max_length=200),
    limite: int = Query(default=50, ge=1, le=200),
    prima_di: int | None = None,
    conn: sqlite3.Connection = Depends(get_conn),
):
    condizioni, valori = [], []
    if gruppo_id is not None:
        condizioni.append("m.gruppo_id = ?")
        valori.append(gruppo_id)
    if smistamento is not None:
        condizioni.append("m.smistamento = ?")
        valori.append(smistamento)
    if da_rispondere is not None:
        # Contratto 15.13: "da rispondere" = da rispondere E non ancora risposta.
        condizione = "(COALESCE(m.da_rispondere, 0) = 1 AND m.risposta_data = 0)"
        condizioni.append(condizione if da_rispondere else f"NOT {condizione}")
    if urgenza is not None:
        condizioni.append("COALESCE(m.urgenza, 'normale') = ?")
        valori.append(urgenza)
    if letta is not None:
        condizioni.append("m.letta = ?")
        valori.append(1 if letta else 0)
    if casella_id is not None:
        condizioni.append("m.casella_id = ?")
        valori.append(casella_id)
    if cerca and cerca.strip():
        like = "%" + cerca.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        condizioni.append(
            "(m.oggetto LIKE ? ESCAPE '\\' OR m.mittente_nome LIKE ? ESCAPE '\\' OR m.mittente_indirizzo LIKE ? ESCAPE '\\'"
            " OR m.riassunto LIKE ? ESCAPE '\\' OR m.corpo LIKE ? ESCAPE '\\')"
        )
        valori.extend([like] * 5)
    if prima_di is not None:
        rif = conn.execute("SELECT ricevuta_il, id FROM mail WHERE id = ?", (prima_di,)).fetchone()
        if rif is None:
            raise non_trovato("La mail da cui continuare l'elenco non c'è più. Ricarica l'elenco.")
        condizioni.append("(m.ricevuta_il < ? OR (m.ricevuta_il = ? AND m.id < ?))")
        valori.extend([rif["ricevuta_il"], rif["ricevuta_il"], rif["id"]])
    dove = (" WHERE " + " AND ".join(condizioni)) if condizioni else ""
    righe = conn.execute(
        forme.SELECT_MAIL + dove + " ORDER BY m.ricevuta_il DESC, m.id DESC LIMIT ?", (*valori, limite + 1)
    ).fetchall()
    return {"mail": forme.mail_lista(conn, righe[:limite]), "altre": len(righe) > limite}


@router.get("/mail/{mail_id}")
def dettaglio(mail_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return forme.mail_dettaglio(conn, _mail(conn, mail_id))


@router.post("/mail/{mail_id}/sposta")
def sposta(mail_id: int, corpo: SpostaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    _mail(conn, mail_id)
    g = conn.execute("SELECT * FROM gruppi WHERE id = ?", (corpo.gruppo_id,)).fetchone()
    if g is None:
        raise non_trovato("Gruppo non trovato.")
    cartella = forme.cartella_di(g["nome"])
    # Lo stesso lucchetto del giro di posta: una correzione dell'utente non si mescola con
    # uno spostamento in sottofondo della stessa mail.
    with LUCCHETTO_SPOSTA:
        m = _mail(conn, mail_id)
        prova = db.impostazioni(conn)["modalita_prova"]
        spostata = False
        if not prova:
            # Prima la casella vera: se non riesce, in Sol non cambia niente e l'utente lo vede.
            # Anche qui vale la regola: si sposta solo se è ancora in Posta in arrivo (o dove
            # l'aveva messa Sol); se l'utente l'ha già sistemata altrove, non si tocca.
            connettore = connettore_della_casella(conn, s, m["casella_id"])
            try:
                spostata = connettore.sposta(riferimento(m), cartella, m["cartella_applicata"])
            except ErroreCasella as e:
                raise ErroreApi(502, e.messaggio) from e
        with db.transazione(conn):
            if prova:
                conn.execute(
                    "UPDATE mail SET gruppo_id = ?, smistamento = 'corretta', origine_smistamento = 'utente',"
                    " applicata_in_casella = 0, applica = 0 WHERE id = ?",
                    (g["id"], mail_id),
                )
            else:
                conn.execute(
                    "UPDATE mail SET gruppo_id = ?, smistamento = 'corretta', origine_smistamento = 'utente',"
                    " applicata_in_casella = 1, applica = 0, cartella_applicata = ? WHERE id = ?",
                    (g["id"], cartella if spostata else None, mail_id),
                )
            # Per una PEC vera il mittente interno lo scrive chi manda la PEC, e quello della
            # busta è il gestore (vale per tutte le sue PEC): nessuna regola (contratto 15.8).
            if not m["pec_mittente_busta"]:
                mittente = m["mittente_indirizzo"]
                if corpo.impara and mittente:
                    salva_regola(conn, "mittente", mittente, g["id"], "imparata")
                dominio = smista.dominio(mittente)
                if corpo.impara_dominio and dominio and not smista.dominio_pubblico(dominio):
                    salva_regola(conn, "dominio", dominio, g["id"], "imparata")
    return forme.mail_lista(conn, [_mail(conn, mail_id)])[0]


@router.post("/mail/{mail_id}/conferma")
def conferma(mail_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    m = _mail(conn, mail_id)
    if m["smistamento"] == "in_attesa":
        raise conflitto("Sol non ha ancora proposto un gruppo per questa mail.")
    if m["smistamento"] == "da_controllare":
        prova = db.impostazioni(conn)["modalita_prova"]
        conn.execute(
            "UPDATE mail SET smistamento = 'smistata', applica = ? WHERE id = ?", (0 if prova else 1, mail_id)
        )
        if not prova:
            s.fabbrica.in_sottofondo(s.lavoro.applica_in_caselle)
    return forme.mail_lista(conn, [_mail(conn, mail_id)])[0]


@router.post("/mail/{mail_id}/letta")
def letta(mail_id: int, corpo: LettaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    m = _mail(conn, mail_id)
    # Anche in modalità prova: segnare letto è un gesto dell'utente, non una scelta di Sol.
    connettore = connettore_della_casella(conn, s, m["casella_id"])
    try:
        connettore.segna_letta(riferimento(m), corpo.letta)
    except ErroreCasella as e:
        raise ErroreApi(502, e.messaggio) from e
    conn.execute("UPDATE mail SET letta = ? WHERE id = ?", (1 if corpo.letta else 0, mail_id))
    return forme.mail_lista(conn, [_mail(conn, mail_id)])[0]


@router.post("/mail/{mail_id}/rispondere")
def rispondere(mail_id: int, corpo: RispondereIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    m = _mail(conn, mail_id)
    with db.transazione(conn):
        conn.execute("UPDATE mail SET da_rispondere = ? WHERE id = ?", (1 if corpo.da_rispondere else 0, mail_id))
        prenotata = corpo.da_rispondere and not m["risposta_data"] and smista.crea_bozza_automatica(conn, mail_id)
    if prenotata:
        s.lavoro.sveglia("scrittore")
    return forme.mail_lista(conn, [_mail(conn, mail_id)])[0]
