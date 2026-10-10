"""Punti 2 e 4 della revisione: richieste abbandonate, una sola /genera, turni equi."""

import asyncio
import threading
import time

import pytest
from fastapi.testclient import TestClient

from app.arbitro import Interrotta, Segnale
from app.domande import leggi_decidi
from app.insieme import LettoreInsieme
from app.modello import Motore, Occupato
from app.scrittore import Scrittore
from app.servizio import crea_app, esegui_sorvegliato
from finti import MotoreFinto, TokenizzatoreFinto

MESSAGGI = [{"role": "user", "content": "Scrivi una bozza lunga."}]
STATO, DOMANDE = leggi_decidi({"stato": "Testo: preventivo", "domande": [
    {"id": "gruppo", "domanda": "Gruppo?", "opzioni": [{"id": "Clienti"}, {"id": "Altro"}]},
    {"id": "urgenza", "domanda": "Urgenza?", "opzioni": [{"id": "alta"}, {"id": "bassa"}]},
]})


def motore_lento(ritardo: float = 0.002):
    finto = MotoreFinto(ritardo_token=ritardo)
    tok = TokenizzatoreFinto()
    lettore = LettoreInsieme(tok, finto, 4096)
    scrittore = Scrittore(tok, finto, lambda t: False, finto.context_tokens, seme=1)
    motore = Motore(None, carica=lambda: (lettore.decidi, scrittore, finto))
    motore._avvia()
    return motore, finto


def in_sottofondo(funzione):
    esito = {}

    def corri():
        try:
            esito["valore"] = funzione()
        except BaseException as errore:  # noqa: BLE001
            esito["errore"] = errore
    filo = threading.Thread(target=corri, daemon=True)
    filo.start()
    return filo, esito


# --- stop: scadenza e client scollegato -----------------------------------------------------

def test_scadenza_ferma_la_scrittura_e_libera_il_modello():
    motore, finto = motore_lento(0.01)
    inizio = time.monotonic()
    with pytest.raises(Interrotta) as errore:
        motore.genera(MESSAGGI, 2000, Segnale(scadenza_secondi=0.3))
    assert errore.value.scaduta
    assert time.monotonic() - inizio < 1.5  # 2000 token sarebbero 20 s
    # Il modello è libero subito: nessuno lo tiene, e si può scrivere o decidere di nuovo.
    assert motore._arbitro._proprietario is None
    finto.ritardo_token = 0
    inizio = time.monotonic()
    motore.decidi(STATO, DOMANDE)
    assert motore.genera(MESSAGGI, 2)["token"] == 2
    assert time.monotonic() - inizio < 2


def test_client_scollegato_ferma_la_scrittura():
    motore, _ = motore_lento(0.01)
    segnale = Segnale()
    threading.Timer(0.3, segnale.ferma).start()
    inizio = time.monotonic()
    with pytest.raises(Interrotta) as errore:
        motore.genera(MESSAGGI, 2000, segnale)
    assert not errore.value.scaduta
    assert time.monotonic() - inizio < 1.5


def test_una_sola_genera_alla_volta():
    motore, _ = motore_lento(0.01)
    segnale = Segnale()
    filo, esito = in_sottofondo(lambda: motore.genera(MESSAGGI, 2000, segnale))
    time.sleep(0.2)
    with pytest.raises(Occupato):
        motore.genera(MESSAGGI, 5)
    segnale.ferma()
    filo.join(5)
    assert isinstance(esito.get("errore"), Interrotta)
    assert motore.genera(MESSAGGI, 2)["token"] == 2  # finita la prima, si può di nuovo


def test_decidi_gia_scaduta_non_viene_calcolata():
    motore, _ = motore_lento()
    chiamate = []
    originale = motore._decidi_fn
    motore._decidi_fn = lambda *a, **k: chiamate.append(1) or originale(*a, **k)
    segnale = Segnale(scadenza_secondi=0.01)
    time.sleep(0.05)
    with pytest.raises(Interrotta):
        motore.decidi(STATO, DOMANDE, segnale)
    assert chiamate == []


def test_decidi_abbandonata_esce_dalla_coda_senza_aspettare():
    motore, _ = motore_lento(0.004)
    lunga, _ = leggi_decidi({"stato": "Testo: " + "x" * 900, "domande": [
        {"id": "g", "domanda": "Gruppo?", "opzioni": [{"id": "a"}, {"id": "b"}]}]})
    prima, _ = in_sottofondo(lambda: motore.decidi(lunga, DOMANDE))  # occupa il modello ~4 s
    time.sleep(0.2)
    segnale = Segnale()
    seconda, esito = in_sottofondo(lambda: motore.decidi(STATO, DOMANDE, segnale))
    time.sleep(0.2)
    inizio = time.monotonic()
    segnale.ferma()
    seconda.join(2)
    assert isinstance(esito.get("errore"), Interrotta)
    assert time.monotonic() - inizio < 1
    prima.join(10)


def test_sorveglianza_si_accorge_del_client_scollegato():
    class RichiestaFinta:
        def __init__(self):
            self.chiamate = 0

        async def is_disconnected(self):
            self.chiamate += 1
            return self.chiamate > 2

    segnale = Segnale()

    def lavoro():
        fine = time.monotonic() + 5
        while not segnale.fermato and time.monotonic() < fine:
            time.sleep(0.01)
        return segnale.fermato
    assert asyncio.run(esegui_sorvegliato(RichiestaFinta(), segnale, lavoro, intervallo=0.05)) is True


# --- turni equi -----------------------------------------------------------------------------

def test_scrittore_ha_token_garantiti_tra_una_decisione_e_l_altra():
    motore, finto = motore_lento(0.002)
    motore.decidi(STATO, DOMANDE)  # testa delle domande già pronta
    finestre = []
    originale = motore._decidi_fn

    def registra(*a, **k):
        inizio = time.monotonic()
        try:
            return originale(*a, **k)
        finally:
            finestre.append((inizio, time.monotonic()))
    motore._decidi_fn = registra
    scrittura, esito = in_sottofondo(lambda: motore.genera(MESSAGGI, 400))
    time.sleep(0.15)
    decisioni = [in_sottofondo(lambda: motore.decidi(STATO, DOMANDE))[0] for _ in range(4)]
    for filo in decisioni:
        filo.join(20)
    scrittura.join(20)
    assert "errore" not in esito
    finestre.sort()
    token_singoli = [t for t, n in finto.decodifiche if n == 1]
    tra = [sum(1 for t in token_singoli if a_fine < t < b_inizio)
           for (_, a_fine), (b_inizio, _) in zip(finestre, finestre[1:])]
    # Tra due decisioni di fila lo scrittore ha scritto i suoi token (prima: 0).
    assert tra and min(tra) >= 8, tra


def test_decidi_aspetta_al_massimo_un_blocco_del_prompt():
    motore, _ = motore_lento(0.002)  # 64 token = 0,13 s; prompt di 1200 token = 2,4 s
    motore.decidi(STATO, DOMANDE)  # testa delle domande già pronta
    lungo = [{"role": "user", "content": "x" * 1200}]
    scrittura, esito = in_sottofondo(lambda: motore.genera(lungo, 3))
    time.sleep(0.3)  # lo scrittore sta leggendo il prompt
    inizio = time.monotonic()
    motore.decidi(STATO, DOMANDE)
    attesa = time.monotonic() - inizio
    scrittura.join(20)
    assert "errore" not in esito
    assert attesa < 1.0, attesa  # prima aspettava tutta la lettura (~2,1 s)


# --- HTTP ----------------------------------------------------------------------------------

class MotoreStub:
    def __init__(self, errore):
        self.errore = errore

    def salute(self):
        return {"stato": "pronto", "modello": "finto"}

    def genera(self, *a, **k):
        raise self.errore

    def decidi(self, *a, **k):
        raise self.errore


@pytest.mark.parametrize("errore", [Occupato("c'è già una scrittura in corso"), Interrotta(scaduta=True)])
def test_http_occupato_e_interrotta_sono_503(errore):
    client = TestClient(crea_app(MotoreStub(errore)))
    risposta = client.post("/genera", json={"messaggi": [{"ruolo": "utente", "testo": "x"}],
                                            "scadenza_secondi": 20})
    assert risposta.status_code == 503
    assert risposta.json()["codice"] == "decisore_non_disponibile"


def test_http_scadenza_secondi_scritta_male():
    client = TestClient(crea_app(MotoreStub(RuntimeError())))
    for valore in (0, -3, "venti", True, 10**7):
        risposta = client.post("/genera", json={"messaggi": [{"ruolo": "utente", "testo": "x"}],
                                                "scadenza_secondi": valore})
        assert risposta.status_code == 422, valore
