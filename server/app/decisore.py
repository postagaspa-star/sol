"""Client del decisore (contratto, sezione 13): il container sol-decisore sul NAS.

Il decisore non scrive testo libero per smistare: sceglie tra opzioni fisse e dà le
probabilità. È lento (decine di secondi a domanda) e può non esserci: chi lo usa
deve sopportarlo, e /api/salute lo dice.

Modi di andare male, trattati diversamente (contratto 15.14):
- non risponde, è in avvio o è occupato con una /genera (503 decisore_non_disponibile,
  tempo scaduto) → DecisoreNonDisponibile: si aspetta e si riprova, la mail non c'entra;
- 422 mail_non_valida → MailNonValida: il problema è QUELLA mail, che va subito
  all'utente (da_controllare), senza altri tentativi e senza contarla come guasto;
- 422 domande_non_valide (configurazione) o 500, o una scelta fuori dalle opzioni →
  RispostaNonValida: si contano i tentativi della mail e poi la si lascia all'utente.
Se rifiuta tutto di fila, /api/salute lo dice invece di mostrare "pronto"."""

import logging
import re
import threading
import time

import httpx

log = logging.getLogger("uvicorn.error")

RIFIUTI_PER_ALLARME = 5
TIMEOUT_DECIDI = 300.0  # ~18 s a mail sul NAS, ma può esserci coda (contratto 15.14: almeno 3 minuti)
TIMEOUT_GENERA = 600.0  # 4,8 token al secondo (contratto 15.14: almeno 5 minuti)
DECISORE_RIFIUTA = (
    "Il decisore rifiuta le domande di Sol: le mail nuove restano da smistare a mano. "
    "Controlla che i gruppi siano da 2 a 16."
)


class DecisoreNonDisponibile(Exception):
    pass


class RispostaNonValida(Exception):
    """Il decisore ha risposto, ma ha rifiutato la richiesta o ha scelto fuori dalle
    opzioni. Si conta, e dopo qualche tentativo la mail si lascia all'utente."""


class MailNonValida(RispostaNonValida):
    """Il decisore dice che il problema è proprio questa mail: niente altri tentativi."""


def _percentuale(dati: dict) -> int | None:
    """Durante il download del modello il decisore dice a che punto è (contratto 15.14):
    "percentuale", oppure dentro il "dettaglio" ("scarico il modello: 37%")."""
    valore = dati.get("percentuale")
    if isinstance(valore, (int, float)) and not isinstance(valore, bool):
        return max(0, min(100, int(valore)))
    m = re.search(r"(\d{1,3})\s*%", str(dati.get("dettaglio") or ""))
    return min(100, int(m.group(1))) if m else None


def _codice(r: httpx.Response) -> str:
    try:
        dati = r.json()
    except ValueError:
        return ""
    return str(dati.get("codice") or "") if isinstance(dati, dict) else ""


class ClientDecisore:
    def __init__(self, url: str, transport: httpx.BaseTransport | None = None):
        self.url = url.rstrip("/")
        self.transport = transport
        self._lock = threading.Lock()
        self._salute: dict = {"stato": "non_disponibile", "modello": None}
        self._salute_il = 0.0
        self._rifiuti_di_fila = 0
        # Al massimo una /genera in volo verso il decisore (ha un solo modello in memoria).
        self._genera_lock = threading.Lock()

    def _client(self, timeout: float) -> httpx.Client:
        return httpx.Client(base_url=self.url, transport=self.transport, timeout=timeout)

    def salute(self, massima_eta: float = 30.0) -> dict:
        """Stato del container, ricordato per 30 s: /api/salute non deve aspettare la rete
        a ogni ricaricamento delle app."""
        with self._lock:
            if time.monotonic() - self._salute_il < massima_eta and self._salute_il:
                return dict(self._salute)
        try:
            with self._client(3.0) as c:
                r = c.get("/salute")
            dati = r.json() if r.status_code == 200 else {}
            stato = dati.get("stato") if dati.get("stato") in ("pronto", "in_avvio") else "non_disponibile"
            salute = {"stato": stato, "modello": dati.get("modello")}
            if stato == "in_avvio":
                salute["percentuale"] = _percentuale(dati)
        except (httpx.HTTPError, ValueError, AttributeError):
            salute = {"stato": "non_disponibile", "modello": None}
        with self._lock:
            self._salute, self._salute_il = salute, time.monotonic()
        return dict(salute)

    def stato_pubblico(self) -> dict:
        """Quello che mostra /api/salute: se il decisore c'è ma rifiuta tutto, non è "pronto"."""
        salute = self.salute()
        with self._lock:
            rifiuta = self._rifiuti_di_fila >= RIFIUTI_PER_ALLARME
        uscita = {"stato": salute["stato"], "modello": salute.get("modello"),
                  "percentuale": salute.get("percentuale"), "errore": None}
        if rifiuta and salute["stato"] == "pronto":
            uscita.update(stato="non_disponibile", errore=DECISORE_RIFIUTA)
        return uscita

    def segna_non_disponibile(self) -> None:
        with self._lock:
            self._salute = {"stato": "non_disponibile", "modello": self._salute.get("modello")}
            self._salute_il = time.monotonic()

    def _rifiuto(self) -> None:
        with self._lock:
            self._rifiuti_di_fila += 1

    def decidi(self, stato: str, domande: list[dict]) -> dict:
        """→ {"id_domanda": {"scelta": ..., "probabilita": {...}}}."""
        try:
            with self._client(TIMEOUT_DECIDI) as c:
                r = c.post("/decidi", json={"stato": stato, "domande": domande})
        except httpx.HTTPError as e:
            self.segna_non_disponibile()
            raise DecisoreNonDisponibile(type(e).__name__) from e
        # 503 = modello in avvio o non caricato: si aspetta e si riprova, la mail non c'entra.
        # 500 = errore interno su QUESTA mail (per esempio troppo lunga anche accorciata): si
        # conta come tentativo, altrimenti la stessa mail bloccherebbe la coda per sempre.
        if r.status_code in (502, 503, 504, 404, 408, 429):
            if r.status_code != 503:
                self.segna_non_disponibile()
            raise DecisoreNonDisponibile(f"HTTP {r.status_code}")
        if r.status_code != 200:
            codice = _codice(r)
            if r.status_code == 422 and codice == "mail_non_valida":
                log.info("decisore: questa mail non si può valutare, la decide l'utente")
                raise MailNonValida(codice)
            self._rifiuto()
            log.warning("decisore: richiesta rifiutata (HTTP %s %s)", r.status_code, codice)
            raise RispostaNonValida(f"HTTP {r.status_code} {codice}")
        try:
            risposte = r.json()["risposte"]
        except (ValueError, KeyError, TypeError) as e:
            self._rifiuto()
            raise RispostaNonValida("risposta illeggibile") from e
        valide = {}
        for domanda in domande:
            risposta = risposte.get(domanda["id"]) if isinstance(risposte, dict) else None
            opzioni = [o["id"] for o in domanda["opzioni"]]
            if not isinstance(risposta, dict) or risposta.get("scelta") not in opzioni:
                self._rifiuto()
                raise RispostaNonValida(f"scelta fuori dalle opzioni per {domanda['id']}")
            probabilita = risposta.get("probabilita") or {}
            valide[domanda["id"]] = {
                "scelta": risposta["scelta"],
                "probabilita": {o: float(probabilita.get(o, 0.0) or 0.0) for o in opzioni},
            }
        with self._lock:
            self._rifiuti_di_fila = 0
            self._salute = {"stato": "pronto", "modello": self._salute.get("modello")}
            self._salute_il = time.monotonic()
        return valide

    def genera(self, messaggi: list[dict], max_token: int) -> str:
        """Lo scrittore di riserva locale (lento). Gli errori HTTP escono come
        httpx.HTTPStatusError: lo scrittore distingue "rifiutata" (4xx) da "giù".
        `scadenza_secondi`: oltre quel tempo il decisore smette di scrivere e libera il
        modello, così non lavora per una risposta che nessuno aspetta più."""
        with self._genera_lock:
            with self._client(TIMEOUT_GENERA) as c:
                r = c.post("/genera", json={"messaggi": messaggi, "max_token": max_token,
                                            "scadenza_secondi": int(TIMEOUT_GENERA - 10)})
        r.raise_for_status()
        return r.json()["testo"]
