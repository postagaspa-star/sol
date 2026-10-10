import json

import pytest

from app.domande import Domanda, DomandeNonValide, Opzione, leggi_decidi
from app.insieme import MAX_TESTE, LettoreInsieme, messaggi_insieme
from app.modello import FILE_DOMANDE_CONSIGLIATE, prepara_domande_consigliate
from app.punteggio import SEGNAPOSTO_TAGLIO, TroppoLungo
from finti import MotoreFinto, TokenizzatoreFinto

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


def test_domande_consigliate_preparate_in_anticipo():
    let, motore = lettore()
    prepara_domande_consigliate(let)
    assert len(let._teste) == 1
    dati = json.loads(FILE_DOMANDE_CONSIGLIATE.read_text(encoding="utf-8"))
    _, domande = leggi_decidi({"stato": "x", "domande": dati["domande"]})
    assert [d.id for d in domande] == ["gruppo", "da_rispondere", "urgenza"]
    let.decidi("Testo: preventivo", domande)
    # la testa c'era già: nessun nuovo prefill
    assert [c[0] for c in motore.chiamate].count("prefill") == 1
