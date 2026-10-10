"""Scrittori (contratto, sezione 14): chi scrive i testi (riassunti, bozze, scadenze).

Catena nell'ordine dell'impostazione `scrittori`: groq → cerebras → locale (il /genera
del decisore). Su errore o limite (429, 5xx, tempo scaduto) si passa al successivo.
Senza chiavi resta solo `locale`.

Ogni fornitore ha un contesto (token di testo + risposta): il locale del NAS solo 4096.
Chi chiede un testo passa una funzione che costruisce il prompt dato un numero massimo
di caratteri, e la catena la chiama con il limite del fornitore di turno: per il locale
l'elenco delle mail si accorcia invece di sforare.

Due modi di fallire, tenuti distinti:
- il fornitore non risponde (rete, 5xx, 429, chiave sbagliata) → "giù": segnato come
  guasto per un paio di minuti, e se sono giù tutti → ScrittoreNonDisponibile;
- il fornitore rifiuta QUESTA richiesta (troppo lunga, non valida) → non è un guasto del
  fornitore: se tutti rifiutano → RichiestaRifiutata, e conta solo per quel lavoro.

Una sola coda: una richiesta alla volta per tutto il server, con una pausa minima tra
due chiamate allo stesso fornitore (i piani gratuiti contano le richieste al minuto).

I modelli non hanno strumenti: ricevono testo e restituiscono testo, niente altro."""

import logging
import re
import threading
import time
from dataclasses import dataclass
from datetime import timedelta

import httpx

from . import clock
from .config import CEREBRAS_URL, CONTESTO_ESTERNI, GROQ_URL, Settings

log = logging.getLogger("uvicorn.error")

FORNITORI = ("groq", "cerebras", "locale")
GUASTO_RICORDATO_SECONDI = 120
# Stima prudente: in italiano un token è circa 3,5 caratteri; contando 3 si sta larghi.
CARATTERI_PER_TOKEN = 3.0
MARGINE_TOKEN = 200  # modello di chat, ruoli, segni di inizio e fine
# I modelli "che ragionano" (gpt-oss) spendono token prima della risposta: se ne
# lasciano in più, e si chiede di ragionare poco.
RISERVA_RAGIONAMENTO = 1024
# Il locale del NAS scrive a ~4,8 token al secondo e legge il prompt a ~10: prompt corti e
# risposte brevi, o una bozza costerebbe dieci minuti (contratto 15.14).
PROMPT_LOCALE_TOKEN = 1500
# Dopo un fallimento o un tempo scaduto del locale non lo si richiama subito: il decisore
# potrebbe essere ancora occupato a liberare il modello (attese crescenti).
PAUSE_LOCALE_SECONDI = (30, 60, 120, 300)


class ScrittoreNonDisponibile(Exception):
    pass


class ScrittoreInPausa(ScrittoreNonDisponibile):
    """Nessuno scrittore provato: il locale è in pausa dopo un fallimento, oppure è stato
    escluso perché il decisore ha un arretrato di mail da smistare. Non è un tentativo
    fallito del lavoro: si rimanda e basta."""


class RichiestaRifiutata(Exception):
    """Chi ha risposto ha rifiutato questa richiesta (troppo lunga o non valida)."""


class _Giu(Exception):
    pass


class _Rifiutata(Exception):
    pass


@dataclass
class Testo:
    testo: str
    fornitore: str


def pulisci_uscita(testo: str) -> str:
    # Alcuni modelli open-source "pensano ad alta voce" tra <think> e </think>.
    testo = re.sub(r"<think>.*?</think>", "", testo or "", flags=re.DOTALL)
    return testo.strip()


def stima_token(messaggi: list[dict]) -> int:
    return int(sum(len(m["testo"]) for m in messaggi) / CARATTERI_PER_TOKEN) + 10 * len(messaggi)


class Scrittore:
    def __init__(self, settings: Settings, decisore, transport: httpx.BaseTransport | None = None, dormi=time.sleep):
        self._esterni = {
            "groq": (GROQ_URL, settings.groq_key, settings.groq_modello),
            "cerebras": (CEREBRAS_URL, settings.cerebras_key, settings.cerebras_modello),
        }
        self._contesto = {"groq": CONTESTO_ESTERNI, "cerebras": CONTESTO_ESTERNI, "locale": settings.contesto_locale}
        self._decisore = decisore
        self._transport = transport
        self._dormi = dormi
        self._coda = threading.Lock()
        self._stato_lock = threading.Lock()
        self._ultima_chiamata: dict[str, float] = {}
        self._guasti: dict[str, float] = {}
        self._locale_fallimenti = 0
        self._locale_pausa_fino = None
        self.pausa_minima = 2.1  # ~28 richieste al minuto: sotto i limiti gratuiti
        self.attesa_429_massima = 30.0
        self.tentativi_429 = 2
        self.timeout = 60.0

    # --- stato ----------------------------------------------------------------------

    def configurati(self, ordine: list[str]) -> list[str]:
        uscita = []
        for f in ordine:
            if f in uscita or f not in FORNITORI:
                continue
            if f == "locale" or self._esterni[f][1]:
                uscita.append(f)
        return uscita

    def _guasto_recente(self, fornitore: str) -> bool:
        with self._stato_lock:
            quando = self._guasti.get(fornitore)
        return quando is not None and time.monotonic() - quando < GUASTO_RICORDATO_SECONDI

    def stato(self, ordine: list[str], decisore_pronto: bool) -> dict:
        """Per /api/salute: chi scrive adesso, tenendo conto dei guasti recenti."""
        utilizzabili = [
            f for f in self.configurati(ordine)
            if not self._guasto_recente(f) and (f != "locale" or decisore_pronto)
        ]
        if not utilizzabili:
            return {"stato": "non_disponibile", "fornitore": None, "riserve": []}
        stato = "solo_locale" if utilizzabili == ["locale"] else "pronto"
        return {"stato": stato, "fornitore": utilizzabili[0], "riserve": utilizzabili[1:]}

    def puo_scrivere(self, ordine: list[str], decisore_pronto: bool) -> bool:
        """Per decidere il 503 delle richieste dell'utente: c'è almeno uno scrittore
        configurato e raggiungibile. I fallimenti dei lavori in sottofondo NON contano:
        una richiesta dell'utente si accetta, passa davanti in coda, e se poi non si
        riesce a scriverla finisce in errore con una frase leggibile."""
        return any(f != "locale" or decisore_pronto for f in self.configurati(ordine))

    def locale_in_pausa(self) -> bool:
        with self._stato_lock:
            fino = self._locale_pausa_fino
        return fino is not None and clock.now() < fino

    def _locale_fallito(self) -> None:
        with self._stato_lock:
            self._locale_fallimenti += 1
            secondi = PAUSE_LOCALE_SECONDI[min(self._locale_fallimenti, len(PAUSE_LOCALE_SECONDI)) - 1]
            self._locale_pausa_fino = clock.now() + timedelta(seconds=secondi)

    def _locale_riuscito(self) -> None:
        with self._stato_lock:
            self._locale_fallimenti = 0
            self._locale_pausa_fino = None

    def _riserva(self, fornitore: str) -> int:
        return RISERVA_RAGIONAMENTO if fornitore in self._esterni and "gpt-oss" in self._esterni[fornitore][2] else 0

    def limite_caratteri(self, fornitore: str, max_token: int) -> int:
        token = self._contesto[fornitore] - max_token - self._riserva(fornitore) - MARGINE_TOKEN
        if fornitore == "locale":
            token = min(token, PROMPT_LOCALE_TOKEN)
        return max(500, int(token * CARATTERI_PER_TOKEN))

    # --- scrittura ------------------------------------------------------------------------

    def scrivi(self, costruisci, ordine: list[str], max_token: int = 600, max_token_locale: int | None = None,
               escludi_locale: bool = False) -> Testo:
        """costruisci: lista di messaggi [{"ruolo", "testo"}] oppure una funzione che,
        dato il numero massimo di caratteri del fornitore di turno, li prepara.
        max_token_locale: risposta più corta per lo scrittore locale (lento).
        escludi_locale: per i lavori in sottofondo mentre il decisore ha mail da smistare
        (lo smistamento viene prima di una bozza automatica)."""
        catena = self.configurati(ordine)
        if not catena:
            raise ScrittoreNonDisponibile("nessuno scrittore configurato")
        rifiutata, provati = False, 0
        with self._coda:
            for fornitore in catena:
                if fornitore == "locale" and (escludi_locale or self.locale_in_pausa()):
                    continue
                provati += 1
                token = min(max_token, max_token_locale or max_token) if fornitore == "locale" else max_token
                messaggi = costruisci(self.limite_caratteri(fornitore, token)) if callable(costruisci) else costruisci
                try:
                    if stima_token(messaggi) + token + self._riserva(fornitore) > self._contesto[fornitore]:
                        raise _Rifiutata("troppo lunga per questo fornitore")
                    if fornitore == "locale":
                        testo = self._locale(messaggi, token)
                    else:
                        testo = self._esterno(fornitore, messaggi, token)
                    testo = pulisci_uscita(testo)
                    if not testo:
                        raise _Rifiutata("testo vuoto")
                except _Rifiutata as e:
                    log.warning("scrittore %s: richiesta rifiutata (%s)", fornitore, e)
                    rifiutata = True
                    continue
                except Exception as e:  # qualunque altro guasto del fornitore: si passa al prossimo
                    log.warning("scrittore %s non disponibile: %s", fornitore, type(e).__name__)
                    with self._stato_lock:
                        self._guasti[fornitore] = time.monotonic()
                    if fornitore == "locale":
                        self._locale_fallito()
                    continue
                with self._stato_lock:
                    self._guasti.pop(fornitore, None)
                if fornitore == "locale":
                    self._locale_riuscito()
                return Testo(testo, fornitore)
        if not provati:
            raise ScrittoreInPausa("locale in pausa o escluso")
        if rifiutata:
            raise RichiestaRifiutata("richiesta rifiutata dagli scrittori disponibili")
        raise ScrittoreNonDisponibile("tutti gli scrittori hanno fallito")

    def _locale(self, messaggi: list[dict], max_token: int) -> str:
        try:
            return self._decisore.genera(messaggi, max_token)
        except httpx.HTTPStatusError as e:
            if e.response.status_code in (400, 413, 422):
                raise _Rifiutata(f"HTTP {e.response.status_code}") from e
            raise _Giu(f"HTTP {e.response.status_code}") from e

    def _rispetta_pausa(self, fornitore: str) -> None:
        ultima = self._ultima_chiamata.get(fornitore)
        if ultima is not None:
            mancano = self.pausa_minima - (time.monotonic() - ultima)
            if mancano > 0:
                self._dormi(mancano)
        self._ultima_chiamata[fornitore] = time.monotonic()

    def _esterno(self, fornitore: str, messaggi: list[dict], max_token: int) -> str:
        url, chiave, modello = self._esterni[fornitore]
        corpo = {
            "model": modello,
            "messages": [
                {"role": "system" if m["ruolo"] == "sistema" else "user", "content": m["testo"]} for m in messaggi
            ],
            "max_tokens": max_token,
            "temperature": 0.3,
        }
        if self._riserva(fornitore):
            corpo["reasoning_effort"] = "low"
            corpo["max_tokens"] = max_token + RISERVA_RAGIONAMENTO
        for tentativo in range(self.tentativi_429 + 1):
            self._rispetta_pausa(fornitore)
            try:
                with httpx.Client(base_url=url, transport=self._transport, timeout=self.timeout) as c:
                    r = c.post("/chat/completions", json=corpo, headers={"Authorization": f"Bearer {chiave}"})
            except httpx.HTTPError as e:
                raise _Giu(type(e).__name__) from e
            if r.status_code == 429:
                attesa = self._attesa_da(r)
                if tentativo < self.tentativi_429 and attesa <= self.attesa_429_massima:
                    self._dormi(attesa)
                    continue
                raise _Giu("limite raggiunto")
            if r.status_code in (400, 413, 422):
                raise _Rifiutata(f"HTTP {r.status_code}")
            if r.status_code != 200:
                raise _Giu(f"HTTP {r.status_code}")
            try:
                return r.json()["choices"][0]["message"]["content"] or ""
            except (ValueError, KeyError, IndexError, TypeError) as e:
                raise _Giu("risposta illeggibile") from e
        raise _Giu("limite raggiunto")

    @staticmethod
    def _attesa_da(r: httpx.Response) -> float:
        valore = r.headers.get("retry-after")
        try:
            return max(0.0, float(valore)) if valore is not None else 5.0
        except ValueError:
            return 5.0
