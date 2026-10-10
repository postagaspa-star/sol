import numpy
import pytest

from app.domande import ErroreRichiesta
from app.scrittore import Scrittore, campiona
from finti import VOCABOLARIO, TokenizzatoreFinto


class MotoreCheScrive:
    """Restituisce logit che "dettano" un testo fisso, poi il token di fine (3)."""

    def __init__(self, testo: str, contesto: int = 4096):
        self.da_dire = [ord(c) for c in testo] + [3]
        self.context_tokens = contesto
        self.memoria: list[int] = []
        self.salvataggi = 0

    def clear(self):
        self.memoria = []

    def _decode(self, ids, inizio, sequenza, vuoi_logit):
        assert inizio == len(self.memoria)
        self.memoria += list(ids)
        if not vuoi_logit:  # un pezzo intermedio del prompt
            return None
        # Il prompt finisce alla prima richiesta di logit (può arrivare a pezzi).
        if not hasattr(self, "lunghezza_prompt"):
            self.lunghezza_prompt = len(self.memoria)
        detti = len(self.memoria) - self.lunghezza_prompt
        logit = numpy.zeros(VOCABOLARIO, dtype=numpy.float32)
        logit[self.da_dire[min(detti, len(self.da_dire) - 1)]] = 50.0
        return logit

    def save_state(self):
        self.salvataggi += 1
        return list(self.memoria)

    def restore_state(self, stato):
        self.memoria = list(stato)


def scrittore(testo, contesto=4096):
    motore = MotoreCheScrive(testo, contesto)
    return Scrittore(TokenizzatoreFinto(), motore, lambda t: t == 3, contesto, temperatura=0.5, seme=1), motore


MESSAGGI = [{"role": "user", "content": "Scrivi una bozza breve."}]


def test_scrive_fino_al_token_di_fine():
    s, _ = scrittore("Buongiorno Rossi, va bene giovedì.")
    esito = s.genera(MESSAGGI, 200)
    assert esito["testo"] == "Buongiorno Rossi, va bene giovedì."
    assert esito["token"] == len("Buongiorno Rossi, va bene giovedì.")
    assert esito["millisecondi"] >= 0 and esito["token_al_secondo"] > 0


def test_si_ferma_a_max_token():
    s, _ = scrittore("abcdefghij")
    assert s.genera(MESSAGGI, 4)["testo"] == "abcd"


def test_chiama_la_pausa_tra_i_token():
    s, _ = scrittore("ciao")
    chiamate = []
    s.genera(MESSAGGI, 50, pausa=lambda token: chiamate.append(token))
    # Prompt corto (un solo pezzo): nessuna pausa lì; poi una dopo ogni token scritto.
    assert chiamate == [1, 1, 1, 1]


def test_prompt_lungo_letto_a_pezzi_con_pausa_tra_un_pezzo_e_l_altro():
    s, motore = scrittore("ok")
    chiamate = []
    s.genera([{"role": "user", "content": "x" * 300}], 50, pausa=lambda token: chiamate.append(token))
    # ~340 token di prompt = 6 pezzi da 64: 5 pause tra un pezzo e l'altro, poi i token.
    assert chiamate[:5] == [64] * 5 and chiamate[5:] == [1, 1]


def test_richiesta_troppo_lunga():
    s, _ = scrittore("x", contesto=100)
    with pytest.raises(ErroreRichiesta, match="troppo lunga"):
        s.genera([{"role": "user", "content": "y" * 80}], 50)


def test_campiona_greedy_e_penalita():
    logit = numpy.zeros(10)
    logit[7] = 5.0
    logit[2] = 4.9
    rng = numpy.random.default_rng(0)
    assert campiona(logit, 0, rng, []) == 7
    # 7 appena usato: la penalità lo abbassa sotto il 2
    assert campiona(logit, 0, rng, [7]) == 2
    # con temperatura resta tra i candidati più probabili
    assert campiona(logit, 0.5, rng, []) in {7, 2}
