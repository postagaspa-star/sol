import json
import threading
import time
from dataclasses import replace

import httpx
import pytest

from app.config import carica_settings
from app.scrittore import Scrittore, ScrittoreNonDisponibile

MESSAGGI = [{"ruolo": "sistema", "testo": "Sei un assistente."}, {"ruolo": "utente", "testo": "Scrivi."}]
ORDINE = ["groq", "cerebras", "locale"]


class LocaleFinto:
    def __init__(self, giu=False):
        self.giu = giu
        self.chiamate = []

    def genera(self, messaggi, max_token):
        self.chiamate.append((messaggi, max_token))
        if self.giu:
            raise httpx.ConnectError("giù")
        return "testo locale"


def _impostazioni(monkeypatch, groq=True, cerebras=True):
    monkeypatch.delenv("SOL_GROQ_KEY", raising=False)
    monkeypatch.delenv("SOL_CEREBRAS_KEY", raising=False)
    s = carica_settings()
    return replace(s, groq_key="chiave-groq" if groq else None, cerebras_key="chiave-cerebras" if cerebras else None,
                   groq_modello="modello-groq", cerebras_modello="modello-cerebras")


class Fornitori:
    """Risposte programmate per host: lista di (status, corpo, intestazioni)."""

    def __init__(self, **programma):
        self.programma = {k: list(v) for k, v in programma.items()}
        self.richieste: list[tuple[str, dict, dict]] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        host = "groq" if "groq" in request.url.host else "cerebras"
        self.richieste.append((host, json.loads(request.content), dict(request.headers)))
        status, corpo, intest = self.programma[host].pop(0)
        if status == "timeout":
            raise httpx.ReadTimeout("lento")
        return httpx.Response(status, json=corpo, headers=intest)


def ok(testo):
    return (200, {"choices": [{"message": {"content": testo}}]}, {})


def _scrittore(monkeypatch, fornitori, locale=None, **kw):
    attese = []
    s = Scrittore(_impostazioni(monkeypatch, **kw), locale or LocaleFinto(), httpx.MockTransport(fornitori), dormi=attese.append)
    s.pausa_minima = 0
    return s, attese


def test_groq_risponde(monkeypatch):
    f = Fornitori(groq=[ok("Ciao <think>ragionamento</think>Manuel")])
    s, _ = _scrittore(monkeypatch, f)
    t = s.scrivi(MESSAGGI, ORDINE, max_token=300)
    assert (t.testo, t.fornitore) == ("Ciao Manuel", "groq")
    host, corpo, intest = f.richieste[0]
    assert corpo["model"] == "modello-groq" and corpo["max_tokens"] == 300
    assert corpo["messages"] == [{"role": "system", "content": "Sei un assistente."}, {"role": "user", "content": "Scrivi."}]
    assert intest["authorization"] == "Bearer chiave-groq"


def test_429_breve_aspetta_e_riprova(monkeypatch):
    f = Fornitori(groq=[(429, {}, {"retry-after": "3"}), ok("dopo l'attesa")])
    s, attese = _scrittore(monkeypatch, f)
    assert s.scrivi(MESSAGGI, ORDINE).fornitore == "groq"
    assert attese == [3.0]


def test_429_lungo_passa_al_successivo(monkeypatch):
    f = Fornitori(groq=[(429, {}, {"retry-after": "3600"})], cerebras=[ok("da cerebras")])
    s, attese = _scrittore(monkeypatch, f)
    t = s.scrivi(MESSAGGI, ORDINE)
    assert t.fornitore == "cerebras" and t.testo == "da cerebras"
    assert attese == []
    assert f.richieste[1][1]["model"] == "modello-cerebras"


@pytest.mark.parametrize("guasto", [(500, {}, {}), (503, {}, {}), ("timeout", None, None), (401, {}, {}), (200, {"strano": 1}, {})])
def test_guasti_passano_al_successivo(monkeypatch, guasto):
    f = Fornitori(groq=[guasto], cerebras=[ok("riserva")])
    s, _ = _scrittore(monkeypatch, f)
    assert s.scrivi(MESSAGGI, ORDINE).fornitore == "cerebras"


def test_tutti_giu_poi_locale(monkeypatch):
    f = Fornitori(groq=[(500, {}, {})], cerebras=[(429, {}, {"retry-after": "999"})])
    locale = LocaleFinto()
    s, _ = _scrittore(monkeypatch, f, locale)
    t = s.scrivi(MESSAGGI, ORDINE, max_token=444)
    assert t.fornitore == "locale" and locale.chiamate == [(MESSAGGI, 444)]


def test_nessuno_disponibile(monkeypatch):
    f = Fornitori(groq=[(500, {}, {})], cerebras=[(500, {}, {})])
    s, _ = _scrittore(monkeypatch, f, LocaleFinto(giu=True))
    with pytest.raises(ScrittoreNonDisponibile):
        s.scrivi(MESSAGGI, ORDINE)
    assert s.stato(ORDINE, decisore_pronto=True)["stato"] == "non_disponibile"


def test_senza_chiavi_solo_locale(monkeypatch):
    f = Fornitori()
    s, _ = _scrittore(monkeypatch, f, groq=False, cerebras=False)
    assert s.configurati(ORDINE) == ["locale"]
    assert s.scrivi(MESSAGGI, ORDINE).fornitore == "locale"
    assert f.richieste == []
    assert s.stato(ORDINE, True) == {"stato": "solo_locale", "fornitore": "locale", "riserve": []}
    assert s.stato(ORDINE, False)["stato"] == "non_disponibile"


def test_ordine_dalle_impostazioni(monkeypatch):
    f = Fornitori(cerebras=[ok("primo cerebras")])
    s, _ = _scrittore(monkeypatch, f)
    assert s.scrivi(MESSAGGI, ["cerebras", "groq"]).fornitore == "cerebras"
    assert s.stato(["cerebras", "groq", "locale"], True) == {"stato": "pronto", "fornitore": "cerebras", "riserve": ["groq", "locale"]}
    with pytest.raises(ScrittoreNonDisponibile):
        _scrittore(monkeypatch, Fornitori(), groq=False, cerebras=False)[0].scrivi(MESSAGGI, ["groq"])


def test_guasto_recente_si_vede_nello_stato(monkeypatch):
    f = Fornitori(groq=[(500, {}, {})], cerebras=[ok("ok")])
    s, _ = _scrittore(monkeypatch, f)
    s.scrivi(MESSAGGI, ORDINE)
    assert s.stato(ORDINE, True) == {"stato": "pronto", "fornitore": "cerebras", "riserve": ["locale"]}


def test_pausa_minima_tra_chiamate(monkeypatch):
    f = Fornitori(groq=[ok("a"), ok("b")])
    s, attese = _scrittore(monkeypatch, f)
    s.pausa_minima = 5.0
    s.scrivi(MESSAGGI, ORDINE)
    s.scrivi(MESSAGGI, ORDINE)
    assert len(attese) == 1 and 4.0 < attese[0] <= 5.0


def test_una_richiesta_alla_volta(monkeypatch):
    dentro, massimo = [0], [0]
    lock = threading.Lock()

    class Lento(LocaleFinto):
        def genera(self, messaggi, max_token):
            with lock:
                dentro[0] += 1
                massimo[0] = max(massimo[0], dentro[0])
            time.sleep(0.05)
            with lock:
                dentro[0] -= 1
            return "fatto"

    s, _ = _scrittore(monkeypatch, Fornitori(), Lento(), groq=False, cerebras=False)
    fili = [threading.Thread(target=s.scrivi, args=(MESSAGGI, ORDINE)) for _ in range(4)]
    for t in fili:
        t.start()
    for t in fili:
        t.join()
    assert massimo[0] == 1
