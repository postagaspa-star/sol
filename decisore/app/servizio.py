"""Servizio HTTP interno (porta 8300, solo rete Docker `sol-rete`).

POST /decidi, POST /genera, GET /salute: forma nel contratto, sezioni 13 e 15.14.
Gli errori hanno sempre la forma {"errore": "frase in italiano", "codice": "slug"}:
- 422 `mail_non_valida` / `domande_non_valide` / `non_valido` (vedi domande.py);
- 503 `decisore_non_disponibile`: modello in avvio o fermo, un'altra /genera in corso,
  richiesta lasciata perdere (scadenza superata o client scollegato);
- 500 `errore_interno`.

Ogni richiesta ha un Segnale: lo alza `scadenza_secondi` (se c'è) oppure il client che
chiude la connessione. Il modello controlla il segnale prima di cominciare e tra un pezzo
di lavoro e l'altro, così non lavora per risposte che nessuno aspetta più.
"""

from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import VERSIONE
from .arbitro import Interrotta, Segnale
from .domande import ErroreRichiesta, MailNonValida, leggi_decidi, leggi_genera, leggi_scadenza
from .modello import Motore, NonPronto, Occupato

log = logging.getLogger("decisore.servizio")

# Una mail vera, anche con allegati citati, sta molto sotto: oltre è un errore a monte.
LIMITE_CORPO = 1_000_000
FRASE_TROPPO_GRANDE = "La richiesta è troppo grande (oltre 1 MB)."


def errore(status: int, frase: str, codice: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"errore": frase, "codice": codice})


async def leggi_corpo(richiesta: Request, troppo_grande: type[ErroreRichiesta]):
    """Legge il JSON senza mai tenere in memoria più di LIMITE_CORPO byte."""
    dichiarata = (richiesta.headers.get("content-length") or "").strip()
    if dichiarata.isdigit() and int(dichiarata) > LIMITE_CORPO:
        raise troppo_grande(FRASE_TROPPO_GRANDE)
    pezzi, totale = [], 0
    async for pezzo in richiesta.stream():
        totale += len(pezzo)
        if totale > LIMITE_CORPO:
            raise troppo_grande(FRASE_TROPPO_GRANDE)
        pezzi.append(pezzo)
    try:
        return json.loads(b"".join(pezzi) or b"null")
    except (json.JSONDecodeError, UnicodeDecodeError) as sbaglio:
        raise ErroreRichiesta("La richiesta non è un JSON valido.") from sbaglio


async def esegui_sorvegliato(richiesta, segnale: Segnale, funzione, *argomenti, intervallo: float = 0.25):
    """Esegue `funzione` in un thread; intanto, se il client chiude la connessione, alza il segnale."""
    async def sorveglia():
        while not segnale.fermato:
            if await richiesta.is_disconnected():
                log.info("client scollegato: la richiesta si ferma")
                segnale.ferma()
                return
            await asyncio.sleep(intervallo)

    compito = asyncio.create_task(sorveglia())
    try:
        return await run_in_threadpool(funzione, *argomenti)
    finally:
        compito.cancel()


def crea_app(motore: Motore | None = None) -> FastAPI:
    @asynccontextmanager
    async def ciclo_vita(app: FastAPI):
        if app.state.motore is None:
            # Avvio vero: configurazione dall'ambiente e caricamento in sottofondo, così
            # /salute risponde "in_avvio" mentre il modello si scarica o si carica.
            from .configurazione import ErroreConfigurazione, leggi

            try:
                app.state.motore = Motore(leggi())
            except ErroreConfigurazione as sbaglio:
                log.error("configurazione sbagliata: %s", sbaglio)
                app.state.motore = Motore.fermo(str(sbaglio))
            else:
                app.state.motore.avvia_in_sottofondo()
        yield

    app = FastAPI(title="Sol decisore", version=VERSIONE, lifespan=ciclo_vita,
                  docs_url=None, redoc_url=None, openapi_url=None)
    app.state.motore = motore

    @app.exception_handler(ErroreRichiesta)
    async def _richiesta_sbagliata(_, sbaglio: ErroreRichiesta):
        return errore(422, str(sbaglio), sbaglio.codice)

    @app.exception_handler(NonPronto)
    async def _non_pronto(_, sbaglio: NonPronto):
        return errore(503, f"Il decisore non è ancora pronto ({sbaglio}). Riprova tra qualche minuto.",
                      "decisore_non_disponibile")

    @app.exception_handler(Occupato)
    async def _occupato(_, sbaglio: Occupato):
        return errore(503, str(sbaglio), "decisore_non_disponibile")

    @app.exception_handler(Interrotta)
    async def _interrotta(_, sbaglio: Interrotta):
        if sbaglio.scaduta:
            frase = "Il decisore non ha finito entro il tempo chiesto: lavoro lasciato perdere."
        else:
            frase = "La richiesta è stata abbandonata: lavoro lasciato perdere."
        log.info("richiesta interrotta (%s)", sbaglio)
        return errore(503, frase, "decisore_non_disponibile")

    @app.exception_handler(StarletteHTTPException)
    async def _http(_, sbaglio: StarletteHTTPException):
        if sbaglio.status_code == 404:
            return errore(404, "Questo indirizzo non esiste.", "non_trovato")
        if sbaglio.status_code == 405:
            return errore(405, "Metodo non permesso su questo indirizzo.", "non_valido")
        return errore(sbaglio.status_code, "Richiesta non accettata.", "non_valido")

    @app.exception_handler(Exception)
    async def _imprevisto(_, sbaglio: Exception):
        log.exception("errore imprevisto")
        return errore(500, "Il decisore ha avuto un problema interno. Riprova.", "errore_interno")

    @app.get("/salute")
    async def salute(richiesta: Request):
        return richiesta.app.state.motore.salute()

    @app.post("/decidi")
    async def decidi(richiesta: Request):
        corpo = await leggi_corpo(richiesta, MailNonValida)
        stato, domande = leggi_decidi(corpo)
        segnale = Segnale(leggi_scadenza(corpo))
        return await esegui_sorvegliato(richiesta, segnale, richiesta.app.state.motore.decidi,
                                        stato, domande, segnale)

    @app.post("/genera")
    async def genera(richiesta: Request):
        corpo = await leggi_corpo(richiesta, ErroreRichiesta)
        messaggi, max_token = leggi_genera(corpo)
        segnale = Segnale(leggi_scadenza(corpo))
        return await esegui_sorvegliato(richiesta, segnale, richiesta.app.state.motore.genera,
                                        messaggi, max_token, segnale)

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
# Le richieste a Hugging Face (solo al primo avvio) non servono nel registro.
logging.getLogger("httpx").setLevel(logging.WARNING)
app = crea_app()
