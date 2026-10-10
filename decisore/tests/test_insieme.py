import json

import numpy
import pytest
from fastapi.testclient import TestClient

from app.domande import Domanda, DomandeNonValide, Opzione, leggi_decidi
from app.insieme import MAX_TESTE, LettoreInsieme, messaggi_insieme
from app.modello import FILE_DOMANDE_CONSIGLIATE, Motore, prepara_domande_consigliate
from app.punteggio import SEGNAPOSTO_TAGLIO, TroppoLungo
from app.servizio import crea_app
from finti import VOCABOLARIO, MotoreFinto, TokenizzatoreFinto, codifica_come_llama, token_spazio

GRUPPO = Domanda("gruppo", "A quale gruppo appartiene questa mail?", (
    Opzione("Clienti", "un cliente chiede o conferma un lavoro"),
    Opzione("Fornitori", "un fornitore manda offerte"),
    Opzione("Altro", ""),
))
RISPONDERE = Domanda("da_rispondere", "Serve una risposta?", (Opzione("si", ""), Opzione("no", "")))
URGENZA = Domanda("urgenza", "Quanto è urgente?", (Opzione("alta", ""), Opzione("normale", ""), Opzione("bassa", "")))


def lettore(contesto=4096, **kwargs):
    motore = MotoreFinto(contesto=contesto, **kwargs)
    return LettoreInsieme(TokenizzatoreFinto(), motore, contesto), motore


def test_domande_prima_della_mail():
    utente = messaggi_insieme("Testo: ciao", [GRUPPO, RISPONDERE])[1]["content"]
    assert utente.index("1. A quale gruppo") < utente.index("2. Serve una risposta?") < utente.index("<mail>")
    assert "\nA) Clienti: un cliente chiede o conferma un lavoro\n" in utente
    assert "Testo: ciao\n</mail>\n\nFine del testo." in utente
    assert "non contano" in utente.rsplit("</mail>", 1)[1]


def test_legge_le_risposte_in_fila():
    let, motore = lettore(preferita="B")
    esiti = let.decidi("Oggetto: fattura", [GRUPPO, RISPONDERE, URGENZA])
    assert [max(range(len(p)), key=p.__getitem__) for p, _ in esiti] == [1, 1, 1]
    testo = TokenizzatoreFinto().decode(motore.memoria)
    # Le risposte scelte vengono aggiunte prima della domanda successiva.
    assert testo.endswith("1: B\n2: B\n3:")


def test_testa_calcolata_una_volta_per_insieme_di_domande():
    let, motore = lettore()
    let.decidi("Testo: uno", [GRUPPO, RISPONDERE])
    let.decidi("Testo: due", [GRUPPO, RISPONDERE])
    assert [c[0] for c in motore.chiamate].count("prefill") == 1
    let.decidi("Testo: tre", [RISPONDERE])
    assert [c[0] for c in motore.chiamate].count("prefill") == 2


def test_al_massimo_poche_teste_in_memoria():
    let, _ = lettore()
    for numero in range(MAX_TESTE + 2):
        domanda = Domanda("d", f"Domanda numero {numero}?", (Opzione("si", ""), Opzione("no", "")))
        let.decidi("Testo: x", [domanda])
    assert len(let._teste) == MAX_TESTE


def test_scelta_dipende_dalla_mail():
    let, _ = lettore(parole={"fattura": "C", "preventivo": "A"})
    (p1, _), = let.decidi("Oggetto: preventivo", [GRUPPO])
    (p2, _), = let.decidi("Oggetto: fattura", [GRUPPO])
    assert p1.index(max(p1)) == 0 and p2.index(max(p2)) == 2


def test_mail_lunga_tagliata_e_contesto_impossibile():
    let, motore = lettore(contesto=1200)
    let.decidi("Testo: " + "parola " * 2000, [GRUPPO, RISPONDERE])
    assert len(motore.memoria) <= 1200
    assert SEGNAPOSTO_TAGLIO.strip() in TokenizzatoreFinto().decode(motore.memoria)
    let, _ = lettore(contesto=520)
    with pytest.raises(TroppoLungo):
        let.decidi("Testo: " + "x" * 5000, [GRUPPO, RISPONDERE, URGENZA])


def test_verifica_gguf_diversa_blocca():
    motore = MotoreFinto()
    let = LettoreInsieme(TokenizzatoreFinto(), motore, 4096, verifica=lambda testo: [1])
    # Non coincide nemmeno senza la mail: la colpa è delle domande (422 domande_non_valide).
    with pytest.raises(DomandeNonValide):
        let.decidi("Testo: ciao", [GRUPPO])


def domande_consigliate():
    dati = json.loads(FILE_DOMANDE_CONSIGLIATE.read_text(encoding="utf-8"))
    return dati["domande"], leggi_decidi({"stato": "x", "domande": dati["domande"]})[1]


def test_domande_consigliate_preparate_in_anticipo():
    let, motore = lettore()
    prepara_domande_consigliate(let)
    # Due teste: tutte e quattro le domande, e le stesse senza "gruppo" (gruppo deciso da una regola).
    assert len(let._teste) == 2
    _, domande = domande_consigliate()
    assert [d.id for d in domande] == ["gruppo", "da_rispondere", "urgenza", "truffa"]
    let.decidi("Testo: preventivo", domande)
    let.decidi("Testo: preventivo", [d for d in domande if d.id != "gruppo"])
    # le teste c'erano già: nessun nuovo prefill
    assert [c[0] for c in motore.chiamate].count("prefill") == 2


# Testo della quarta domanda (contratto 16.1): il server ne tiene una copia identica.
DOMANDA_TRUFFA = {
    "id": "truffa",
    "domanda": "Questa mail potrebbe essere un tentativo di truffa o di raggiro?",
    "opzioni": [
        {"id": "si", "descrizione": "sì: chiede di cambiare IBAN o coordinate bancarie, di pagare con urgenza, "
                                    "di mandare fatture, documenti, password o codici, di aprire un link per "
                                    "sbloccare o verificare qualcosa, oppure finge di essere una banca, un "
                                    "corriere, un fornitore o un cliente con un indirizzo o un tono strano"},
        {"id": "no", "descrizione": "no: è una normale comunicazione di lavoro, una fattura attesa, un avviso "
                                    "o una pubblicità"},
    ],
}


def test_domanda_truffa_coi_testi_esatti():
    grezze, _ = domande_consigliate()
    assert [d["id"] for d in grezze] == ["gruppo", "da_rispondere", "urgenza", "truffa"]
    assert grezze[3] == DOMANDA_TRUFFA


class MotorePerNumero(MotoreFinto):
    """Risponde una lettera diversa per ogni domanda: guarda l'ultima riga ("1:", "2:"...)."""

    def __init__(self, risposte: dict[str, str], **kwargs):
        super().__init__(**kwargs)
        self.risposte = risposte

    def _logit(self):
        numero = TokenizzatoreFinto().decode(self.memoria).rstrip().rsplit("\n", 1)[-1].rstrip(":")
        lettera = self.risposte.get(numero, "A")
        logit = numpy.zeros(VOCABOLARIO, dtype=numpy.float32)
        logit[ord(lettera)] = 8.0
        logit[token_spazio(lettera)] = 8.0
        return logit


def test_quattro_domande_lette_in_fila():
    _, domande = domande_consigliate()
    motore = MotorePerNumero({"1": "B", "2": "A", "3": "C", "4": "A"})
    lettore_vero = LettoreInsieme(TokenizzatoreFinto(), motore, 4096, verifica=codifica_come_llama)
    esiti = lettore_vero.decidi("Testo: da oggi pagate sul nuovo IBAN", domande)
    assert [p.index(max(p)) for p, _ in esiti] == [1, 0, 2, 0]
    assert TokenizzatoreFinto().decode(motore.memoria).endswith("1: B\n2: A\n3: C\n4:")


def test_http_quattro_domande_con_truffa():
    grezze, _ = domande_consigliate()
    motore_llama = MotorePerNumero({"1": "B", "2": "A", "3": "C", "4": "A"})
    lettore_vero = LettoreInsieme(TokenizzatoreFinto(), motore_llama, 4096, verifica=codifica_come_llama)
    motore = Motore(None, carica=lambda: (lettore_vero.decidi, None, motore_llama))
    motore._avvia()
    corpo = TestClient(crea_app(motore)).post("/decidi", json={"stato": "Testo: nuovo IBAN", "domande": grezze}).json()
    assert {k: v["scelta"] for k, v in corpo["risposte"].items()} == {
        "gruppo": "Fornitori", "da_rispondere": "si", "urgenza": "bassa", "truffa": "si"}
    assert set(corpo["risposte"]["truffa"]["probabilita"]) == {"si", "no"}
