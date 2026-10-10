"""I pezzi del server che parlano con l'esterno, raccolti in un posto: le route li
trovano in request.app.state.servizi, i test li sostituiscono con dei finti."""

from dataclasses import dataclass

from fastapi import Request

from .caselle import FabbricaConnettori
from .cifratura import Cifrario
from .config import Settings
from .decisore import ClientDecisore
from .lavoro import Lavoro
from .scrittore import Scrittore


@dataclass
class Servizi:
    settings: Settings
    cifrario: Cifrario
    fabbrica: FabbricaConnettori
    decisore: ClientDecisore
    scrittore: Scrittore
    lavoro: Lavoro


def crea_servizi(settings: Settings, cifrario: Cifrario) -> Servizi:
    fabbrica = FabbricaConnettori(settings.db_path, cifrario, settings.ms_client_id, settings.ms_tenant)
    decisore = ClientDecisore(settings.decisore_url)
    scrittore = Scrittore(settings, decisore)
    lavoro = Lavoro(settings.db_path, fabbrica, decisore, scrittore)

    def dopo_collegamento(casella_id: int) -> None:
        lavoro.controlla_casella(casella_id)
        lavoro.sveglia("posta")

    fabbrica.dopo_collegamento = dopo_collegamento
    return Servizi(settings, cifrario, fabbrica, decisore, scrittore, lavoro)


def servizi(request: Request) -> Servizi:
    return request.app.state.servizi
