import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import errori
from .cifratura import prepara_cifrario
from .config import VERSIONE, assicura_cartella_db, carica_settings, riassunto_config
from .db import init_db, ripristina_dopo_riavvio
from .routes import bozze, caselle, gruppi, impostazioni, indietro, mail, oggi, riassunti, scadenze, sistema
from .servizi import crea_servizi

# uvicorn.error ha già un gestore a livello INFO: le righe finiscono nel log del container.
log = logging.getLogger("uvicorn.error")


@asynccontextmanager
async def _ciclo_di_vita(app: FastAPI):
    compito, ferma = None, asyncio.Event()
    if app.state.settings.sottofondo:
        compito = asyncio.create_task(app.state.servizi.lavoro.gira(ferma))
    try:
        yield
    finally:
        ferma.set()
        if compito is not None:
            await compito


def create_app() -> FastAPI:
    settings = carica_settings()
    log.info("Sol avvio: %s", riassunto_config(settings))
    cifrario = prepara_cifrario(settings.chiave, settings.env)
    assicura_cartella_db(settings.db_path)
    init_db(settings.db_path)
    # Quello che risulta "in corso" all'avvio è rimasto a metà: lo si rimette in coda o in errore.
    ripristina_dopo_riavvio(settings.db_path)

    # Niente CORS: le app non sono pagine web, e un sito qualsiasi aperto nel browser
    # non deve poter parlare col server (il browser blocca le richieste di altre origini).
    app = FastAPI(title="Sol", version=VERSIONE, lifespan=_ciclo_di_vita, docs_url=None, redoc_url=None,
                  openapi_url=None)
    app.state.settings = settings
    app.state.servizi = crea_servizi(settings, cifrario)
    errori.registra(app)

    app.include_router(sistema.pubblico, prefix="/api")
    app.include_router(sistema.protetto, prefix="/api")
    app.include_router(sistema.scarica_router)
    app.include_router(caselle.router, prefix="/api")
    app.include_router(gruppi.router, prefix="/api")
    app.include_router(mail.router, prefix="/api")
    app.include_router(bozze.router, prefix="/api")
    app.include_router(riassunti.router, prefix="/api")
    app.include_router(scadenze.router, prefix="/api")
    app.include_router(indietro.router, prefix="/api")
    app.include_router(impostazioni.router, prefix="/api")
    app.include_router(oggi.router, prefix="/api")
    return app
