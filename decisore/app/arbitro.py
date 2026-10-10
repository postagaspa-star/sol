"""Chi usa il modello, e quando: decisioni in fila, scrittura a turni, stop delle richieste.

Il contesto llama.cpp è uno solo, quindi una cosa per volta. Le regole:
- le decisioni passano nell'ordine in cui arrivano;
- lo scrittore (al massimo uno, lo controlla il Motore) cede il modello tra un token e
  l'altro se c'è una decisione che aspetta, ma solo dopo aver fatto un po' di strada
  (QUANTO_TOKEN token oppure QUANTO_SECONDI secondi da quando l'ha ripreso): senza questa
  garanzia, con tante mail in coda, scriverebbe un token ogni 18 secondi;
- quando cede, passa UNA decisione e poi il modello torna a lui;
- chi aspetta il proprio turno controlla il suo Segnale: se il client se n'è andato o la
  scadenza è passata, esce dalla fila senza aver usato il modello.
"""

from __future__ import annotations

import threading
import time
from collections import deque

QUANTO_TOKEN = 8
QUANTO_SECONDI = 2.0
# Ogni quanto chi aspetta ricontrolla il suo segnale (oltre alle notifiche).
CONTROLLO_SECONDI = 0.1

SCRITTORE = object()  # "biglietto" fisso dello scrittore


class Interrotta(Exception):
    """La richiesta va lasciata perdere: client scollegato oppure scadenza superata."""

    def __init__(self, scaduta: bool = False):
        self.scaduta = scaduta
        super().__init__("scaduta" if scaduta else "client scollegato")


class Segnale:
    """Stop di UNA richiesta: lo alza il servizio (client scollegato) o il tempo (scadenza)."""

    def __init__(self, scadenza_secondi: float | None = None):
        self._fermo = threading.Event()
        self._scade = time.monotonic() + scadenza_secondi if scadenza_secondi else None

    def ferma(self) -> None:
        self._fermo.set()

    @property
    def scaduto(self) -> bool:
        return self._scade is not None and time.monotonic() >= self._scade

    @property
    def fermato(self) -> bool:
        return self._fermo.is_set() or self.scaduto

    def controlla(self) -> None:
        if self._fermo.is_set():
            raise Interrotta(scaduta=False)
        if self.scaduto:
            raise Interrotta(scaduta=True)


class Arbitro:
    def __init__(self):
        self._cv = threading.Condition()
        self._proprietario = None          # None = modello libero
        self._coda: deque = deque()         # biglietti delle decisioni in attesa
        self._scrittore_aspetta = False     # lo scrittore vuole il modello (ha la precedenza)
        self._cedi_uno = False              # lo scrittore ha appena ceduto: prima passa UNA decisione

    def decisioni_in_attesa(self) -> int:
        return len(self._coda)

    def _aspetta(self, condizione, segnale: Segnale | None) -> None:
        while not condizione():
            if segnale is not None:
                segnale.controlla()
            self._cv.wait(CONTROLLO_SECONDI)

    def entra_decisione(self, segnale: Segnale | None = None) -> object:
        biglietto = object()
        with self._cv:
            self._coda.append(biglietto)
            try:
                self._aspetta(lambda: self._proprietario is None and self._coda[0] is biglietto
                              and not (self._scrittore_aspetta and not self._cedi_uno), segnale)
            except BaseException:
                self._coda.remove(biglietto)
                self._cv.notify_all()
                raise
            self._coda.popleft()
            self._proprietario = biglietto
            self._cedi_uno = False
            return biglietto

    def entra_scrittore(self, segnale: Segnale | None = None) -> None:
        with self._cv:
            self._scrittore_aspetta = True
            try:
                self._aspetta(lambda: self._proprietario is None and (not self._cedi_uno or not self._coda),
                              segnale)
            except BaseException:
                self._scrittore_aspetta = False
                self._cedi_uno = False
                self._cv.notify_all()
                raise
            self._scrittore_aspetta = False
            self._cedi_uno = False
            self._proprietario = SCRITTORE

    def cedi(self, segnale: Segnale | None = None) -> None:
        """Lo scrittore lascia passare una decisione e poi riprende il modello."""
        with self._cv:
            if self._proprietario is not SCRITTORE:
                raise RuntimeError("cedi() senza avere il modello")
            self._proprietario = None
            self._cedi_uno = True
            self._scrittore_aspetta = True
            self._cv.notify_all()
        self.entra_scrittore(segnale)

    def esci(self, chi) -> None:
        with self._cv:
            if self._proprietario is chi:
                self._proprietario = None
                self._cv.notify_all()


class Quanto:
    """Strada fatta dallo scrittore da quando ha (ri)preso il modello."""

    def __init__(self):
        self.ricomincia()

    def ricomincia(self) -> None:
        self.token = 0
        self.dal = time.monotonic()

    def aggiungi(self, token: int) -> None:
        self.token += token

    @property
    def fatto(self) -> bool:
        return self.token >= QUANTO_TOKEN or time.monotonic() - self.dal >= QUANTO_SECONDI
