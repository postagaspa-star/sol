import threading
import time

import pytest
from fastapi.testclient import TestClient

from app.modello import NON_DISPONIBILE, PRONTO, Motore
from app.punteggio import LettoreSol
from app.scrittore import Scrittore
from app.servizio import crea_app
from finti import MotoreFinto, TokenizzatoreFinto

DOMANDE = [
    {"id": "gruppo", "domanda": "A quale gruppo appartiene questa mail?",
     "opzioni": [{"id": "Clienti", "descrizione": "un cliente chiede o conferma un lavoro"},
                 {"id": "Fornitori", "descrizione": "un fornitore manda offerte o listini"},
                 {"id": "Amministrazione e fatture", "descrizione": "fatture, pagamenti, banca"}]},
    {"id": "da_rispondere", "domanda": "Bisogna rispondere?",
     "opzioni": [{"id": "si", "descrizione": "sì"}, {"id": "no", "descrizione": "no"}]},
    {"id": "urgenza", "domanda": "Quanto è urgente?",
     "opzioni": [{"id": "alta"}, {"id": "normale"}, {"id": "bassa"}]},
]


def motore_pronto(preferita="B"):
    finto = MotoreFinto(preferita=preferita)
    tok = TokenizzatoreFinto()
    lettore = LettoreSol(tok, finto, 4096)
    scrittore = Scrittore(tok, finto, lambda t: t == 3, finto.context_tokens, seme=1)
    motore = Motore(None, carica=lambda: (lettore.decidi, scrittore, finto))
    motore._avvia()
    assert motore.stato == PRONTO
    return motore


@pytest.fixture
def client():
    return TestClient(crea_app(motore_pronto()), raise_server_exceptions=False)


def test_salute_pronto(client):
    assert client.get("/salute").json() == {"stato": "pronto", "modello": "finto"}


def test_salute_in_avvio_e_decidi_rifiutato():
    motore = Motore(None, carica=lambda: (_ for _ in ()).throw(RuntimeError("mai")))
    motore.dettaglio = "scarico il modello: 42%"
    client = TestClient(crea_app(motore))
    salute = client.get("/salute").json()
    assert salute["stato"] == "in_avvio" and "42%" in salute["dettaglio"]
    risposta = client.post("/decidi", json={"stato": "x", "domande": DOMANDE})
    assert risposta.status_code == 503
    assert risposta.json()["codice"] == "decisore_non_disponibile"
    assert client.post("/genera", json={"messaggi": [{"ruolo": "utente", "testo": "x"}]}).status_code == 503


def test_decidi_forma_del_contratto(client):
    risposta = client.post("/decidi", json={"stato": "Mittente: Rossi\nOggetto: offerta\nTesto: ...",
                                            "domande": DOMANDE})
    assert risposta.status_code == 200
    corpo = risposta.json()
    assert set(corpo) == {"risposte", "millisecondi"}
    assert isinstance(corpo["millisecondi"], int)
    gruppo = corpo["risposte"]["gruppo"]
    assert gruppo["scelta"] == "Fornitori"  # il motore finto preferisce la lettera B
    assert set(gruppo["probabilita"]) == {"Clienti", "Fornitori", "Amministrazione e fatture"}
    assert corpo["risposte"]["da_rispondere"]["scelta"] == "no"
    assert corpo["risposte"]["urgenza"]["scelta"] == "normale"
    assert 0 < gruppo["massa_opzioni"] <= 1


def test_decidi_errori_in_italiano(client):
    risposta = client.post("/decidi", json={"stato": "x", "domande": []})
    assert risposta.status_code == 422
    assert risposta.json() == {"errore": "Serve almeno una domanda.", "codice": "domande_non_valide"}
    risposta = client.post("/decidi", content=b"{non json", headers={"content-type": "application/json"})
    assert risposta.status_code == 422 and risposta.json()["codice"] == "non_valido"


def test_indirizzo_sconosciuto(client):
    risposta = client.get("/niente")
    assert risposta.status_code == 404 and risposta.json()["codice"] == "non_trovato"


def test_genera(client):
    risposta = client.post("/genera", json={"messaggi": [{"ruolo": "sistema", "testo": "Sei breve."},
                                                         {"ruolo": "utente", "testo": "Bozza?"}],
                                            "max_token": 5})
    assert risposta.status_code == 200
    corpo = risposta.json()
    # il finto detta sempre "B" (o " B", token singolo come in Qwen)
    assert set(corpo["testo"].replace(" ", "")) == {"B"} and corpo["token"] == 5


def test_errore_interno_leggibile():
    motore = motore_pronto()

    def rotto(*_):
        raise RuntimeError("llama_decode failed")
    motore._decidi_fn = rotto
    client = TestClient(crea_app(motore), raise_server_exceptions=False)
    risposta = client.post("/decidi", json={"stato": "x", "domande": DOMANDE})
    assert risposta.status_code == 500
    assert risposta.json()["codice"] == "errore_interno"


def test_avvio_riprova_con_attese_crescenti_poi_si_ferma(monkeypatch):
    import app.modello as modulo

    attese, stati = [], []
    monkeypatch.setattr(modulo, "_dormi", lambda secondi: attese.append(secondi) or stati.append(motore.stato))
    tentativi = []

    def carica():
        tentativi.append(1)
        raise ConnectionError("[Errno 101] Network is unreachable (dettaglio tecnico)")
    motore = Motore(None, carica=carica)
    motore._avvia()
    assert attese == list(modulo.ATTESE_RIPROVA)  # 1, 2, 4, 8, 16, 32 minuti
    assert len(tentativi) == len(modulo.ATTESE_RIPROVA) + 1  # poi basta: niente giri infiniti
    assert set(stati) == {NON_DISPONIBILE}
    salute = motore.salute()
    assert salute["stato"] == NON_DISPONIBILE
    # Frase semplice, senza il testo tecnico dell'eccezione (quello va nel registro).
    assert "Errno" not in salute["errore"] and "connessione" in salute["errore"]
    assert "smesso di riprovare" in salute["errore"]


def test_avvio_riuscito_dopo_qualche_tentativo(monkeypatch):
    import app.modello as modulo

    monkeypatch.setattr(modulo, "_dormi", lambda secondi: None)
    tentativi = []

    def carica():
        tentativi.append(1)
        if len(tentativi) < 3:
            raise ConnectionError("rete assente")
        return (lambda *a: [], None, None)
    motore = Motore(None, carica=carica)
    motore._avvia()
    assert motore.stato == PRONTO and motore.errore is None and len(tentativi) == 3


def test_errore_definitivo_non_si_riprova(monkeypatch):
    import app.modello as modulo
    from app.scaricatore import ErroreScaricamento

    attese = []
    monkeypatch.setattr(modulo, "_dormi", attese.append)
    tentativi = []

    def carica():
        tentativi.append(1)
        raise ErroreScaricamento("Il file del modello sul server non ha la dimensione attesa.", definitivo=True)
    motore = Motore(None, carica=carica)
    motore._avvia()
    assert tentativi == [1] and attese == []
    assert motore.salute()["stato"] == NON_DISPONIBILE
    assert "dimensione attesa" in motore.salute()["errore"]


def test_salute_in_avvio_ha_la_percentuale_come_numero():
    motore = Motore(None, carica=lambda: None)
    motore._avanzamento(753, 3013)
    salute = TestClient(crea_app(motore)).get("/salute").json()
    assert salute["stato"] == "in_avvio"
    assert salute["percentuale"] == 24 and isinstance(salute["percentuale"], int)


def test_domande_troppo_lunghe_sono_422_domande_non_valide():
    finto = MotoreFinto(contesto=700)
    lettore = LettoreSol(TokenizzatoreFinto(), finto, 700)
    motore = Motore(None, carica=lambda: (lettore.decidi, None, finto))
    motore._avvia()
    client = TestClient(crea_app(motore), raise_server_exceptions=False)
    domande = [{"id": "g", "domanda": "x" * 900, "opzioni": [{"id": "a"}, {"id": "b"}]}]
    risposta = client.post("/decidi", json={"stato": "Testo: ciao", "domande": domande})
    assert risposta.status_code == 422
    assert risposta.json()["codice"] == "domande_non_valide"


def test_corpo_oltre_un_mega_rifiutato(client):
    enorme = b'{"stato": "' + b"x" * 1_100_000 + b'", "domande": []}'
    risposta = client.post("/decidi", content=enorme, headers={"content-type": "application/json"})
    assert risposta.status_code == 422 and risposta.json()["codice"] == "mail_non_valida"
    enorme = b'{"messaggi": [{"ruolo": "utente", "testo": "' + b"x" * 1_100_000 + b'"}]}'
    risposta = client.post("/genera", content=enorme, headers={"content-type": "application/json"})
    assert risposta.status_code == 422 and risposta.json()["codice"] == "non_valido"


@pytest.mark.parametrize("guasto", [
    lambda d: d[0].update(domanda="x" * 5000),
    lambda d: d[0]["opzioni"][0].update(descrizione="x" * 5000),
    lambda d: d[0]["opzioni"][0].update(id="x" * 500),
    lambda d: d[0].update(id="x" * 500),
])
def test_testi_troppo_lunghi_nelle_domande(client, guasto):
    import copy

    domande = copy.deepcopy(DOMANDE)
    guasto(domande)
    risposta = client.post("/decidi", json={"stato": "Testo: ciao", "domande": domande})
    assert risposta.status_code == 422 and risposta.json()["codice"] == "domande_non_valide"


def test_messaggi_troppi_o_troppo_lunghi(client):
    lungo = {"messaggi": [{"ruolo": "utente", "testo": "x" * 60_000}]}
    assert client.post("/genera", json=lungo).status_code == 422
    troppi = {"messaggi": [{"ruolo": "utente", "testo": "x"}] * 25}
    assert client.post("/genera", json=troppi).status_code == 422


def test_mail_vuota_e_mail_non_valida(client):
    risposta = client.post("/decidi", json={"stato": "   ", "domande": DOMANDE})
    assert risposta.status_code == 422 and risposta.json()["codice"] == "mail_non_valida"


def test_decisione_passa_davanti_alla_scrittura():
    """Una /decidi che arriva durante una /genera lunga non aspetta la fine della bozza."""
    motore = motore_pronto()
    finto = motore._llama
    originale = finto._decode
    lento = threading.Event()

    def decode_lento(ids, inizio, seq, vuoi):
        if len(ids) == 1 and lento.is_set():
            time.sleep(0.01)  # ogni token scritto costa 10 ms
        return originale(ids, inizio, seq, vuoi)
    finto._decode = decode_lento
    lento.set()
    esito_genera = {}
    filo = threading.Thread(target=lambda: esito_genera.update(
        motore.genera([{"role": "user", "content": "lunga"}], 300)))
    filo.start()
    time.sleep(0.2)
    from app.domande import leggi_decidi
    stato, domande = leggi_decidi({"stato": "Testo: ciao", "domande": DOMANDE})
    inizio = time.perf_counter()
    esito = motore.decidi(stato, domande)
    attesa = time.perf_counter() - inizio
    assert filo.is_alive(), "la scrittura doveva essere ancora in corso"
    filo.join()
    assert attesa < 1.5  # la bozza intera dura ~3 s
    assert esito["risposte"]["gruppo"]["scelta"] == "Fornitori"
    assert esito_genera["token"] == 300 and set(esito_genera["testo"].replace(" ", "")) == {"B"}
