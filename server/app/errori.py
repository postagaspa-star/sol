"""Errori nel formato del contratto: status HTTP + {"errore": frase, "codice": slug}.

Il campo `errore` è sempre una frase in italiano semplice; quando serve alle app, il nome
tecnico del campo sbagliato va a parte in "campo" (contratto, sezione 15.13). Anche gli
errori inattesi escono in JSON (500 errore_interno), mai in testo semplice."""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("uvicorn.error")

CODICI = {
    400: "non_valido",
    401: "non_autorizzato",
    403: "non_autorizzato",
    404: "non_trovato",
    405: "non_trovato",
    409: "conflitto",
    422: "non_valido",
    429: "troppi_tentativi",
    500: "errore_interno",
    502: "casella_non_raggiungibile",
    503: "scrittore_non_disponibile",
}

FRASI = {
    400: "La richiesta non va bene.",
    401: "Questo dispositivo non è abbinato o il suo accesso è stato revocato.",
    403: "Questo dispositivo non è abbinato o il suo accesso è stato revocato.",
    404: "Non trovato.",
    405: "Non trovato.",
    409: "Non si può fare adesso.",
    422: "Alcuni dati della richiesta non vanno bene.",
    429: "Troppi tentativi. Aspetta qualche minuto e riprova.",
    500: "Sol ha avuto un problema interno. Riprova tra poco.",
}

# Frasi per i campi che le app mandano: nessun nome tecnico nella frase.
FRASI_CAMPI = {
    "limite": "Si possono chiedere da 1 a 200 mail per volta.",
    "prima_di": "Il punto da cui continuare l'elenco non va bene.",
    "giorni_risposta": "I giorni per rispondere devono essere un numero intero tra 1 e 60.",
    "giorni_sollecito": "I giorni prima del sollecito devono essere un numero intero tra 1 e 90.",
    "soglia_sicurezza": "La soglia di sicurezza deve essere un numero tra 0 e 1.",
    "intervallo_controllo_minuti": "L'intervallo dei controlli deve essere tra 1 e 1440 minuti.",
    "conserva_giorni": "Le mail si possono conservare da 7 a 3650 giorni.",
    "scrittori": "Scegli almeno uno scrittore tra Groq, Cerebras e quello locale.",
    "modalita_prova": "La modalità prova va accesa o spenta (sì o no).",
    "tono": "Il tono non può essere vuoto né più lungo di 100 caratteri.",
    "firma": "La firma può essere lunga al massimo 500 caratteri.",
    "conferma": "Per inviare serve la conferma esplicita.",
    "versione": "Manca la versione della risposta che hai controllato.",
    "codice": "Il codice di abbinamento non va bene.",
    "nome": "Il nome non può essere vuoto né troppo lungo.",
    "tipo": "Il tipo indicato non è tra quelli previsti.",
    "indirizzo": "L'indirizzo non va bene.",
    "password_app": "La password per le app non va bene.",
    "gruppo_id": "Il gruppo indicato non va bene.",
    "valore": "Il valore della regola non va bene.",
    "impara": "La scelta «impara» va indicata con sì o no.",
    "impara_dominio": "La scelta «impara anche il dominio» va indicata con sì o no.",
    "letta": "Va indicato se la mail è letta o no.",
    "da_rispondere": "Va indicato se la mail è da rispondere o no.",
    "istruzioni": "Le indicazioni per la bozza possono essere lunghe al massimo 2000 caratteri.",
    "da_zero": "La scelta «rifai da zero» va indicata con sì o no.",
    "a": "Controlla i destinatari.",
    "cc": "Controlla i destinatari in copia.",
    "oggetto": "L'oggetto è troppo lungo.",
    "testo": "Il testo è troppo lungo.",
    "richiesta": "La richiesta di riassunto deve essere tra 2 e 500 caratteri.",
    "descrizione": "La descrizione non può essere vuota né più lunga di 200 caratteri.",
    "quando": "La data va scritta come AAAA-MM-GG, per esempio 2026-10-16.",
    "ora": "L'ora va scritta come HH:MM, per esempio 09:30.",
    "stato": "Lo stato indicato non è tra quelli previsti.",
    "smistamento": "Lo stato di smistamento indicato non è tra quelli previsti.",
    "urgenza": "L'urgenza va indicata come alta, normale o bassa.",
    "cerca": "Il testo da cercare è troppo lungo.",
    "dal": "Il cursore delle novità non va bene: richiedi le novità senza cursore.",
    "ordine": "La posizione del gruppo deve essere un numero tra 0 e 1000.",
    "mail_id": "La mail indicata non va bene.",
}


class ErroreApi(Exception):
    def __init__(self, status: int, errore: str, codice: str | None = None, campo: str | None = None,
                 extra: dict | None = None):
        super().__init__(errore)
        self.status = status
        self.errore = errore
        self.codice = codice or CODICI.get(status, "errore")
        self.campo = campo
        self.extra = extra or {}


def non_trovato(cosa: str = "Non trovato.") -> ErroreApi:
    return ErroreApi(404, cosa)


def non_valido(frase: str, campo: str | None = None) -> ErroreApi:
    return ErroreApi(422, frase, campo=campo)


def conflitto(frase: str, extra: dict | None = None) -> ErroreApi:
    return ErroreApi(409, frase, extra=extra)


def risposta(status: int, errore: str, codice: str | None = None, campo: str | None = None,
             extra: dict | None = None) -> JSONResponse:
    contenuto = {"errore": errore, "codice": codice or CODICI.get(status, "errore")}
    if campo:
        contenuto["campo"] = campo
    contenuto.update(extra or {})
    return JSONResponse(status_code=status, content=contenuto)


def _campo(loc) -> str:
    parti = [str(p) for p in loc if p not in ("body", "query", "path") and not isinstance(p, int)]
    return parti[0] if parti else ""


async def gestore_inatteso(request: Request, e: Exception) -> JSONResponse:
    log.exception("errore inatteso su %s %s", request.method, request.url.path)
    return risposta(500, FRASI[500])


def registra(app: FastAPI) -> None:
    @app.exception_handler(ErroreApi)
    async def _errore_api(request: Request, e: ErroreApi):
        return risposta(e.status, e.errore, e.codice, e.campo, e.extra)

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, e: StarletteHTTPException):
        frase = e.detail if isinstance(e.detail, str) and e.status_code not in (404, 405) else None
        return risposta(e.status_code, frase or FRASI.get(e.status_code, "Qualcosa non è andato."))

    @app.exception_handler(RequestValidationError)
    async def _validazione(request: Request, e: RequestValidationError):
        campi = [c for c in (_campo(err.get("loc", ())) for err in e.errors()) if c]
        campo = campi[0] if campi else None
        frase = FRASI_CAMPI.get(campo, FRASI[422]) if campo else FRASI[422]
        return risposta(422, frase, campo=campo)

    @app.exception_handler(OverflowError)
    async def _numero(request: Request, e: OverflowError):
        return risposta(422, "Un numero della richiesta è troppo grande.")

    # Ultima rete: qualsiasi errore non previsto diventa un 500 in JSON leggibile.
    app.add_exception_handler(Exception, gestore_inatteso)
