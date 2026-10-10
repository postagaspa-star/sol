"""Le forme JSON del contratto: da righe del database a oggetti per le app.
Qui non passa mai un segreto (password, token): le colonne cifrate non si leggono."""

import json
import sqlite3

from . import clock
from .caselle.testo import anteprima

SELECT_MAIL = (
    "SELECT m.*, c.indirizzo AS casella_indirizzo, g.nome AS gruppo_nome"
    " FROM mail m JOIN caselle c ON c.id = m.casella_id LEFT JOIN gruppi g ON g.id = m.gruppo_id"
)


def casella(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "tipo": r["tipo"],
        "indirizzo": r["indirizzo"],
        "nome": r["nome"],
        "stato": r["stato"],
        "ultimo_controllo": clock.per_utente(r["ultimo_controllo"]),
        "errore": r["errore"],
        "attiva_dal": clock.per_utente(r["attiva_dal"]),
    }


def gruppo(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "nome": r["nome"],
        "ordine": r["ordine"],
        "cartella": cartella_di(r["nome"]),
        "descrizione": r["descrizione"],
        "da_leggere": r["da_leggere"] if "da_leggere" in r.keys() else 0,
        "totale": r["totale"] if "totale" in r.keys() else 0,
    }


def cartella_di(nome_gruppo: str) -> str:
    return f"Sol/{nome_gruppo}"


def regola(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "tipo": r["tipo"],
        "valore": r["valore"],
        "gruppo_id": r["gruppo_id"],
        "origine": r["origine"],
        "creata_il": clock.per_utente(r["creata_il"]),
    }


def _bozze_per_mail(conn: sqlite3.Connection, ids: list[int]) -> dict[int, dict]:
    if not ids:
        return {}
    segnaposto = ",".join("?" * len(ids))
    uscita: dict[int, dict] = {}
    # L'ultima bozza di risposta non scartata di ogni mail.
    for b in conn.execute(
        f"SELECT id, mail_id, stato FROM bozze WHERE tipo = 'risposta' AND stato != 'scartata'"
        f" AND mail_id IN ({segnaposto}) ORDER BY id",
        ids,
    ):
        uscita[b["mail_id"]] = {"id": b["id"], "stato": b["stato"]}
    return uscita


def mail_lista(conn: sqlite3.Connection, righe: list[sqlite3.Row]) -> list[dict]:
    bozze = _bozze_per_mail(conn, [r["id"] for r in righe])
    return [_mail(r, bozze.get(r["id"])) for r in righe]


def _mail(r: sqlite3.Row, bozza: dict | None) -> dict:
    return {
        "id": r["id"],
        "casella_id": r["casella_id"],
        "casella": r["casella_indirizzo"],
        "mittente": {"nome": r["mittente_nome"], "indirizzo": r["mittente_indirizzo"]},
        "oggetto": r["oggetto"],
        "anteprima": anteprima(r["corpo"]),
        "riassunto": r["riassunto"],
        "ricevuta_il": clock.per_utente(r["ricevuta_il"]),
        "letta": bool(r["letta"]),
        "risposta_data": bool(r["risposta_data"]),
        "gruppo_id": r["gruppo_id"],
        "gruppo": r["gruppo_nome"],
        "da_rispondere": bool(r["da_rispondere"]),
        "urgenza": r["urgenza"] or "normale",
        "sicurezza": r["sicurezza"],
        "smistamento": r["smistamento"],
        "origine_smistamento": r["origine_smistamento"],
        "applicata_in_casella": bool(r["applicata_in_casella"]),
        "bozza": bozza,
        "allegati": json.loads(r["allegati"] or "[]"),
        "pec": pec(r),
        "incerti": json.loads(r["incerti"] or "[]"),
        "sospetta": bool(r["sospetta"]),  # contratto 16.2: possibile truffa
    }


def pec(r: sqlite3.Row) -> dict | None:
    """Contratto 15.8: per una PEC vera, chi ha consegnato la busta (il gestore)."""
    busta = r["pec_mittente_busta"]
    if not busta:
        return None
    return {"mittente_busta": busta, "gestore": busta.rsplit("@", 1)[-1]}


def mail_dettaglio(conn: sqlite3.Connection, r: sqlite3.Row) -> dict:
    base = mail_lista(conn, [r])[0]
    probabilita = json.loads(r["probabilita"]) if r["probabilita"] else {}
    base.update(
        destinatari=json.loads(r["destinatari"] or "[]"),
        cc=json.loads(r["cc"] or "[]"),
        corpo=r["corpo"],
        probabilita={
            "gruppo": probabilita.get("gruppo", {}),
            "da_rispondere": probabilita.get("da_rispondere", {}),
            "urgenza": probabilita.get("urgenza", {}),
            "truffa": probabilita.get("truffa", {}),
        },
    )
    return base


def leggi_mail(conn: sqlite3.Connection, mail_id: int) -> sqlite3.Row | None:
    return conn.execute(SELECT_MAIL + " WHERE m.id = ?", (mail_id,)).fetchone()


def bozza(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "mail_id": r["mail_id"],
        "inviata_id": r["inviata_id"],
        "tipo": r["tipo"],
        "stato": r["stato"],
        "a": json.loads(r["a"] or "[]"),
        "cc": json.loads(r["cc"] or "[]"),
        "oggetto": r["oggetto"],
        "testo": r["testo"],
        "istruzioni": r["istruzioni"],
        "fornitore": r["fornitore"],
        "modificata": bool(r["modificata"]),
        "creata_il": clock.per_utente(r["creata_il"]),
        "inviata_il": clock.per_utente(r["inviata_il"]),
        "errore": r["errore"],
        "versione": r["versione"],
    }


def scadenza(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "mail_id": r["mail_id"],
        "descrizione": r["descrizione"],
        "quando": r["quando"],
        "ora": r["ora"],
        "stato": r["stato"],
        "creata_il": clock.per_utente(r["creata_il"]),
    }


def riassunto(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "richiesta": r["richiesta"],
        "stato": r["stato"],
        "testo_parziale": r["testo_parziale"],
        "testo": r["testo"],
        "mail_citate": json.loads(r["mail_citate"] or "[]"),
        "errore": r["errore"],
        "creato_il": clock.per_utente(r["creato_il"]),
    }


def inviata(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"],
        "casella_id": r["casella_id"],
        "a": json.loads(r["a"] or "[]"),
        "oggetto": r["oggetto"],
        "inviata_il": clock.per_utente(r["inviata_il"]),
        "anteprima": anteprima(r["corpo"]),
    }
