"""I corpi delle richieste. Campi in più vengono ignorati (le app più nuove possono
mandarne); i tipi sbagliati diventano un 422 in italiano (errori.py)."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt


class Corpo(BaseModel):
    model_config = ConfigDict(extra="ignore")


class AbbinaIn(Corpo):
    codice: str = Field(min_length=1, max_length=20)
    nome: str = Field(min_length=1, max_length=60)
    tipo: Literal["android", "pc"]


class CasellaIn(Corpo):
    tipo: Literal["gmail", "outlook", "finta"]
    nome: str = Field(default="", max_length=60)
    indirizzo: str | None = Field(default=None, max_length=254)
    password_app: str | None = Field(default=None, max_length=100)


class CasellaPatch(Corpo):
    nome: str = Field(min_length=1, max_length=60)


Colore = Literal["blu", "verde", "ambra", "viola", "grigio", "ardesia", "petrolio", "rosa", "indaco", "oliva",
                 "marrone", "ciano"]  # contratto 17 (lo stesso elenco di db.COLORI)


class GruppoIn(Corpo):
    nome: str = Field(min_length=1, max_length=40)
    descrizione: str | None = Field(default=None, max_length=200)
    colore: Colore | None = None
    raccolta: StrictBool = False


class GruppoPatch(Corpo):
    nome: str | None = Field(default=None, min_length=1, max_length=40)
    ordine: int | None = Field(default=None, ge=0, le=1000)
    descrizione: str | None = Field(default=None, max_length=200)
    colore: Colore | None = None
    raccolta: StrictBool | None = None


class RegolaIn(Corpo):
    tipo: Literal["mittente", "dominio"]
    valore: str = Field(min_length=3, max_length=254)
    gruppo_id: int


class SpostaIn(Corpo):
    gruppo_id: int
    impara: StrictBool = False
    impara_dominio: StrictBool = False


class LettaIn(Corpo):
    letta: StrictBool = True


class RispondereIn(Corpo):
    da_rispondere: StrictBool


class SospettaIn(Corpo):
    sospetta: StrictBool


class IstruzioniIn(Corpo):
    istruzioni: str | None = Field(default=None, max_length=2000)
    # Contratto 15.13: senza istruzioni, una bozza modificata a mano si rifà da zero solo
    # se l'app lo chiede esplicitamente.
    da_zero: StrictBool = False


class BozzaPut(Corpo):
    a: list[str] | None = Field(default=None, max_length=50)
    cc: list[str] | None = Field(default=None, max_length=50)
    oggetto: str | None = Field(default=None, max_length=300)
    testo: str | None = Field(default=None, max_length=50000)


class InviaIn(Corpo):
    # Deve essere proprio true: niente "si", 1 o stringhe (contratto, sezione 0).
    conferma: StrictBool | None = None
    # La versione della bozza che l'utente ha visto nella conferma (contratto 15.11).
    versione: StrictInt | None = None


class ConfermaIn(Corpo):
    conferma: StrictBool | None = None


class RicollegaIn(Corpo):
    password_app: str | None = Field(default=None, max_length=100)


class RiassuntoIn(Corpo):
    richiesta: str = Field(min_length=2, max_length=500)


class ScadenzaIn(Corpo):
    descrizione: str = Field(min_length=1, max_length=200)
    quando: str
    ora: str | None = None
    mail_id: int | None = None


class ScadenzaPatch(Corpo):
    stato: Literal["aperta", "fatta", "scartata"] | None = None
    quando: str | None = None
    descrizione: str | None = Field(default=None, min_length=1, max_length=200)


class ImpostazioniPatch(Corpo):
    modalita_prova: StrictBool | None = None
    soglia_sicurezza: float | None = Field(default=None, ge=0.0, le=1.0)
    giorni_risposta: int | None = Field(default=None, ge=1, le=60)
    giorni_sollecito: int | None = Field(default=None, ge=1, le=90)
    intervallo_controllo_minuti: int | None = Field(default=None, ge=1, le=1440)
    firma: str | None = Field(default=None, max_length=500)
    tono: str | None = Field(default=None, min_length=1, max_length=100)
    scrittori: list[Literal["groq", "cerebras", "locale"]] | None = Field(default=None, min_length=1, max_length=3)
    conserva_giorni: int | None = Field(default=None, ge=7, le=3650)
    soglia_truffa: float | None = Field(default=None, ge=0.2, le=0.9)
    nome: str | None = None  # massimo 40 caratteri, controllato nella rotta (può essere vuoto)
