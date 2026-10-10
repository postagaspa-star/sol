"""Gruppi e regole (contratto, sezione 5)."""

import re
import sqlite3

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from .. import clock, db, forme
from ..auth import richiede_token
from ..caselle.testo import riga
from ..config import GRUPPI_MASSIMI, GRUPPI_MINIMI
from ..db import get_conn
from ..errori import conflitto, non_trovato, non_valido
from ..schemi import GruppoIn, GruppoPatch, RegolaIn

router = APIRouter(dependencies=[Depends(richiede_token)])

SELECT_GRUPPI = (
    "SELECT g.*, (SELECT COUNT(*) FROM mail m WHERE m.gruppo_id = g.id AND m.letta = 0) AS da_leggere,"
    " (SELECT COUNT(*) FROM mail m WHERE m.gruppo_id = g.id) AS totale FROM gruppi g"
)


def _gruppo(conn: sqlite3.Connection, gruppo_id: int) -> sqlite3.Row:
    g = conn.execute(SELECT_GRUPPI + " WHERE g.id = ?", (gruppo_id,)).fetchone()
    if g is None:
        raise non_trovato("Gruppo non trovato.")
    return g


def _nome_libero(conn: sqlite3.Connection, nome: str, tranne: int | None = None) -> str:
    nome = re.sub(r"\s+", " ", nome).strip()
    if not nome or "/" in nome:
        raise non_valido("Il nome del gruppo non può essere vuoto né contenere la barra «/».")
    riga = conn.execute("SELECT id FROM gruppi WHERE nome = ? COLLATE NOCASE", (nome,)).fetchone()
    if riga and riga["id"] != tranne:
        raise conflitto("Esiste già un gruppo con questo nome.")
    return nome


@router.get("/gruppi")
def elenco(conn: sqlite3.Connection = Depends(get_conn)):
    return [forme.gruppo(g) for g in conn.execute(SELECT_GRUPPI + " ORDER BY g.ordine, g.id")]


TROPPI_GRUPPI = "Puoi avere al massimo 16 gruppi: togline uno prima di aggiungerne un altro."
ULTIMO_GRUPPO = "Servono almeno due gruppi: non puoi cancellare l'ultimo oltre ad Altro."


@router.post("/gruppi")
def crea(corpo: GruppoIn, conn: sqlite3.Connection = Depends(get_conn)):
    # Il decisore sceglie tra 2 e 16 opzioni (contratto 15.1): oltre, lo smistamento si fermerebbe.
    if conn.execute("SELECT COUNT(*) FROM gruppi").fetchone()[0] >= GRUPPI_MASSIMI:
        raise non_valido(TROPPI_GRUPPI)
    nome = _nome_libero(conn, corpo.nome)
    # La descrizione dice al decisore che mail ci vanno (contratto 15.14); se manca, il nome.
    descrizione = riga(corpo.descrizione or "", 200) or nome
    ordine = conn.execute("SELECT COALESCE(MAX(ordine), 0) + 1 FROM gruppi").fetchone()[0]
    colore = corpo.colore or db.colore_libero(conn)  # contratto 17
    cur = conn.execute(
        "INSERT INTO gruppi (nome, ordine, descrizione, colore) VALUES (?, ?, ?, ?)", (nome, ordine, descrizione, colore)
    )
    return JSONResponse(status_code=201, content=forme.gruppo(_gruppo(conn, cur.lastrowid)))


@router.patch("/gruppi/{gruppo_id}")
def modifica(gruppo_id: int, corpo: GruppoPatch, conn: sqlite3.Connection = Depends(get_conn)):
    g = _gruppo(conn, gruppo_id)
    if corpo.nome is not None:
        nome = _nome_libero(conn, corpo.nome, tranne=gruppo_id)
        conn.execute("UPDATE gruppi SET nome = ? WHERE id = ?", (nome, gruppo_id))
        # Se la descrizione era solo il nome, segue il nome nuovo.
        if g["descrizione"] == g["nome"] and corpo.descrizione is None:
            conn.execute("UPDATE gruppi SET descrizione = ? WHERE id = ?", (nome, gruppo_id))
    if corpo.descrizione is not None:
        descrizione = riga(corpo.descrizione, 200) or _gruppo(conn, gruppo_id)["nome"]
        conn.execute("UPDATE gruppi SET descrizione = ? WHERE id = ?", (descrizione, gruppo_id))
    if corpo.ordine is not None:
        conn.execute("UPDATE gruppi SET ordine = ? WHERE id = ?", (corpo.ordine, gruppo_id))
    if corpo.colore is not None:
        conn.execute("UPDATE gruppi SET colore = ? WHERE id = ?", (corpo.colore, gruppo_id))
    return forme.gruppo(_gruppo(conn, gruppo_id))


@router.delete("/gruppi/{gruppo_id}")
def elimina(gruppo_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    g = _gruppo(conn, gruppo_id)
    if g["chiave"] == "altro":
        raise conflitto("Il gruppo Altro non si può cancellare.")
    if conn.execute("SELECT COUNT(*) FROM gruppi").fetchone()[0] <= GRUPPI_MINIMI:
        raise non_valido(ULTIMO_GRUPPO)
    altro = db.gruppo_per_chiave(conn, "altro")
    with db.transazione(conn):
        # Le mail passano ad Altro; nelle caselle vere restano dove sono finché non le
        # si sposta di nuovo (cancellare un gruppo non è un gesto sulle mail).
        conn.execute("UPDATE mail SET gruppo_id = ? WHERE gruppo_id = ?", (altro["id"], gruppo_id))
        conn.execute("DELETE FROM regole WHERE gruppo_id = ?", (gruppo_id,))
        conn.execute("DELETE FROM gruppi WHERE id = ?", (gruppo_id,))
    return {"ok": True}


# --- regole ------------------------------------------------------------------------------


def normalizza_regola(tipo: str, valore: str) -> str:
    valore = valore.strip().lower()
    if tipo == "mittente":
        if not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", valore):
            raise non_valido("Per una regola sul mittente serve un indirizzo completo, per esempio rossi@esempio.it.")
    else:
        valore = valore.lstrip("@")
        if "@" in valore or not re.match(r"^[a-z0-9.-]+\.[a-z]{2,}$", valore):
            raise non_valido("Per una regola sul dominio scrivi solo la parte dopo la chiocciola, per esempio esempio.it.")
    return valore


def salva_regola(conn: sqlite3.Connection, tipo: str, valore: str, gruppo_id: int, origine: str) -> int:
    conn.execute(
        "INSERT INTO regole (tipo, valore, gruppo_id, origine, creata_il) VALUES (?, ?, ?, ?, ?)"
        " ON CONFLICT (tipo, valore) DO UPDATE SET gruppo_id = excluded.gruppo_id, origine = excluded.origine",
        (tipo, valore, gruppo_id, origine, clock.adesso()),
    )
    return conn.execute("SELECT id FROM regole WHERE tipo = ? AND valore = ?", (tipo, valore)).fetchone()["id"]


@router.get("/regole")
def regole(conn: sqlite3.Connection = Depends(get_conn)):
    return [forme.regola(r) for r in conn.execute("SELECT * FROM regole ORDER BY id")]


@router.post("/regole")
def crea_regola(corpo: RegolaIn, conn: sqlite3.Connection = Depends(get_conn)):
    _gruppo(conn, corpo.gruppo_id)
    valore = normalizza_regola(corpo.tipo, corpo.valore)
    regola_id = salva_regola(conn, corpo.tipo, valore, corpo.gruppo_id, "manuale")
    r = conn.execute("SELECT * FROM regole WHERE id = ?", (regola_id,)).fetchone()
    return JSONResponse(status_code=201, content=forme.regola(r))


@router.delete("/regole/{regola_id}")
def elimina_regola(regola_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    if conn.execute("DELETE FROM regole WHERE id = ?", (regola_id,)).rowcount == 0:
        raise non_trovato("Regola non trovata.")
    return {"ok": True}
