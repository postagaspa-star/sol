"""Versione, salute, download, abbinamento e dispositivi (contratto, sezioni 2 e 3)."""

import hashlib
import json
import sqlite3
import threading
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, Header, Request
from fastapi.responses import FileResponse

from .. import abbina, clock, db
from ..auth import Dispositivo, dispositivo_del_token, richiede_token, token_da_header
from ..config import VERSIONE
from ..db import get_conn
from ..errori import ErroreApi, non_trovato
from ..schemi import AbbinaIn
from ..servizi import Servizi, servizi

pubblico = APIRouter()
protetto = APIRouter(dependencies=[Depends(richiede_token)])
scarica_router = APIRouter()

VERSIONI_DEFAULT = {
    "server": VERSIONE,
    "android": {"codice": 1, "nome": "1.0.0"},
    "pc": {"codice": 1, "nome": "1.0.0"},
    "nota": None,
}
FILE_SCARICABILI = {"sol.apk": "application/vnd.android.package-archive", "sol-pc.zip": "application/zip"}
SENZA_CACHE = {"Cache-Control": "no-store, max-age=0"}


def carica_versioni(percorso: str) -> dict:
    """Letto a ogni richiesta: si aggiorna il file senza riavviare. Se manca o è rotto,
    i valori di base (le app si appoggiano a questa risposta per aggiornarsi)."""
    try:
        with open(percorso, encoding="utf-8") as f:
            dati = json.load(f)
    except (OSError, ValueError):
        return json.loads(json.dumps(VERSIONI_DEFAULT))
    return dati if isinstance(dati, dict) else json.loads(json.dumps(VERSIONI_DEFAULT))


_impronte: dict[str, tuple[float, int, str]] = {}
_impronte_lock = threading.Lock()


def impronta(percorso: Path) -> tuple[str | None, int | None]:
    """sha256 e dimensione del file scaricabile (contratto 15.10). Ricalcolata solo se il
    file è cambiato: l'APK pesa 12 MB e /api/versione si chiede spesso."""
    try:
        info = percorso.stat()
    except OSError:
        return None, None
    chiave = str(percorso)
    with _impronte_lock:
        nota = _impronte.get(chiave)
    if nota and nota[0] == info.st_mtime and nota[1] == info.st_size:
        return nota[2], info.st_size
    h = hashlib.sha256()
    with open(percorso, "rb") as f:
        for blocco in iter(lambda: f.read(1024 * 1024), b""):
            h.update(blocco)
    with _impronte_lock:
        _impronte[chiave] = (info.st_mtime, info.st_size, h.hexdigest())
    return h.hexdigest(), info.st_size


FILE_PER_APP = {"android": "sol.apk", "pc": "sol-pc.zip"}


@pubblico.get("/versione")
def versione(request: Request):
    dati = carica_versioni(request.app.state.settings.versioni_path)
    cartella = Path(request.app.state.settings.apk_dir)
    for app, nome_file in FILE_PER_APP.items():
        voce = dati.get(app)
        if isinstance(voce, dict):
            sha256, byte = impronta(cartella / nome_file)
            voce["sha256"], voce["byte"] = sha256, byte
    return dati


@pubblico.get("/salute")
def salute(
    authorization: Annotated[str | None, Header()] = None,
    conn: sqlite3.Connection = Depends(get_conn),
    s: Servizi = Depends(servizi),
):
    # 15.12: un 200 da solo non dice che l'accesso funziona; lo dice "autenticato".
    if dispositivo_del_token(conn, token_da_header(authorization)) is None:
        return {"ok": True, "versione": VERSIONE, "autenticato": False}
    imp = db.impostazioni(conn)
    decisore = s.decisore.stato_pubblico()
    scrittore = s.scrittore.stato(imp["scrittori"], s.decisore.salute()["stato"] == "pronto")
    caselle = [
        {"id": c["id"], "indirizzo": c["indirizzo"], "stato": c["stato"],
         "ultimo_controllo": clock.per_utente(c["ultimo_controllo"]), "errore": c["errore"]}
        for c in conn.execute("SELECT * FROM caselle ORDER BY id")
    ]
    coda = {
        "da_smistare": conn.execute("SELECT COUNT(*) FROM mail WHERE smistamento = 'in_attesa'").fetchone()[0],
        "bozze_in_preparazione": conn.execute(
            "SELECT COUNT(*) FROM bozze WHERE stato = 'in_preparazione'"
        ).fetchone()[0],
    }
    return {
        "ok": True,
        "versione": VERSIONE,
        "autenticato": True,
        "decisore": decisore,
        "scrittore": scrittore,
        "caselle": caselle,
        "modalita_prova": imp["modalita_prova"],
        "coda": coda,
    }


@scarica_router.get("/scarica/{nome_file}")
def scarica(nome_file: str, request: Request):
    tipo = FILE_SCARICABILI.get(nome_file)
    percorso = Path(request.app.state.settings.apk_dir) / nome_file
    if tipo is None or not percorso.is_file():
        raise non_trovato("Questo file non è ancora disponibile.")
    return FileResponse(percorso, media_type=tipo, filename=nome_file, headers=SENZA_CACHE)


# --- abbinamento -----------------------------------------------------------------------


@pubblico.post("/abbina")
def abbina_dispositivo(corpo: AbbinaIn, request: Request, conn: sqlite3.Connection = Depends(get_conn)):
    ip = request.client.host if request.client else "sconosciuto"
    esito = abbina.abbina(conn, corpo.codice.strip(), corpo.nome.strip(), corpo.tipo, clock.now(), ip)
    if esito == abbina.BLOCCATO:
        raise ErroreApi(429, "Troppi codici sbagliati. Aspetta 15 minuti e chiedi un codice nuovo.")
    if esito is None:
        raise ErroreApi(401, "Il codice è sbagliato o scaduto. Chiedine uno nuovo.")
    token, dispositivo_id = esito
    return {"token": token, "dispositivo_id": dispositivo_id}


@protetto.get("/dispositivi")
def dispositivi(conn: sqlite3.Connection = Depends(get_conn)):
    return [
        {
            "id": d["id"],
            "nome": d["nome"],
            "tipo": d["tipo"],
            "abbinato_il": clock.per_utente(d["abbinato_il"]),
            "ultimo_accesso": clock.per_utente(d["ultimo_accesso"]),
        }
        for d in conn.execute("SELECT * FROM dispositivi ORDER BY id")
    ]


@protetto.delete("/dispositivi/{dispositivo_id}")
def revoca(dispositivo_id: int, conn: sqlite3.Connection = Depends(get_conn), chi: Dispositivo = Depends(richiede_token)):
    cur = conn.execute("DELETE FROM dispositivi WHERE id = ?", (dispositivo_id,))
    if cur.rowcount == 0:
        raise non_trovato("Dispositivo non trovato.")
    return {"ok": True}
