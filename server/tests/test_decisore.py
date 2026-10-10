import json

import httpx
import pytest

from app.decisore import ClientDecisore, DecisoreNonDisponibile, RispostaNonValida

DOMANDE = [{"id": "urgenza", "domanda": "Quanto è urgente?", "opzioni": [{"id": "alta", "descrizione": "a"}, {"id": "bassa", "descrizione": "b"}]}]


def _client(gestore):
    return ClientDecisore("http://sol-decisore:8300", transport=httpx.MockTransport(gestore))


def test_decidi():
    visti = []

    def gestore(request):
        visti.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"risposte": {"urgenza": {"scelta": "alta", "probabilita": {"alta": 0.8, "bassa": 0.2}}}, "millisecondi": 41000})

    r = _client(gestore).decidi("Mittente: x\nOggetto: y\nTesto: z", DOMANDE)
    assert r == {"urgenza": {"scelta": "alta", "probabilita": {"alta": 0.8, "bassa": 0.2}}}
    assert visti[0][0] == "/decidi"
    assert visti[0][1] == {"stato": "Mittente: x\nOggetto: y\nTesto: z", "domande": DOMANDE}


def test_scelta_fuori_dalle_opzioni():
    c = _client(lambda r: httpx.Response(200, json={"risposte": {"urgenza": {"scelta": "media"}}}))
    with pytest.raises(RispostaNonValida):
        c.decidi("x", DOMANDE)
    c = _client(lambda r: httpx.Response(200, text="non json"))
    with pytest.raises(RispostaNonValida):
        c.decidi("x", DOMANDE)


def test_probabilita_mancanti_diventano_zero():
    c = _client(lambda r: httpx.Response(200, json={"risposte": {"urgenza": {"scelta": "bassa"}}}))
    assert c.decidi("x", DOMANDE)["urgenza"]["probabilita"] == {"alta": 0.0, "bassa": 0.0}


def test_decisore_irraggiungibile():
    def gestore(request):
        raise httpx.ConnectError("rifiutata")

    c = _client(gestore)
    with pytest.raises(DecisoreNonDisponibile):
        c.decidi("x", DOMANDE)
    assert c.salute()["stato"] == "non_disponibile"


def test_decisore_in_avvio_503():
    c = _client(lambda r: httpx.Response(503, json={}))
    with pytest.raises(DecisoreNonDisponibile):
        c.decidi("x", DOMANDE)


def test_salute_ricordata():
    chiamate = []

    def gestore(request):
        chiamate.append(request.url.path)
        return httpx.Response(200, json={"stato": "pronto", "modello": "Qwen3.5-4B Q4_K_M"})

    c = _client(gestore)
    assert c.salute() == {"stato": "pronto", "modello": "Qwen3.5-4B Q4_K_M"}
    c.salute()
    assert chiamate == ["/salute"]
    assert c.salute(massima_eta=0)["stato"] == "pronto"
    assert len(chiamate) == 2


def test_salute_stato_strano():
    c = _client(lambda r: httpx.Response(200, json={"stato": "boh"}))
    assert c.salute()["stato"] == "non_disponibile"
    c = _client(lambda r: httpx.Response(200, json={"stato": "in_avvio", "modello": "m"}))
    assert c.salute() == {"stato": "in_avvio", "modello": "m", "percentuale": None}
    c = _client(lambda r: httpx.Response(200, json={"stato": "in_avvio", "modello": "m", "percentuale": 37}))
    assert c.stato_pubblico() == {"stato": "in_avvio", "modello": "m", "percentuale": 37, "errore": None}
    c = _client(lambda r: httpx.Response(200, json={"stato": "in_avvio", "dettaglio": "scarico il modello: 42%"}))
    assert c.salute()["percentuale"] == 42


def test_genera():
    def gestore(request):
        assert request.url.path == "/genera"
        # scadenza_secondi: il decisore smette di scrivere se nessuno aspetta più (contratto 15.14).
        assert json.loads(request.content) == {"messaggi": [{"ruolo": "utente", "testo": "ciao"}], "max_token": 600,
                                               "scadenza_secondi": 590}
        return httpx.Response(200, json={"testo": "risposta"})

    assert _client(gestore).genera([{"ruolo": "utente", "testo": "ciao"}], 600) == "risposta"
