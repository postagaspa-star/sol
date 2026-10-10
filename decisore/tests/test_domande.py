import pytest

from app.domande import (
    MAX_DOMANDE, Domanda, ErroreRichiesta, Opzione, leggi_decidi, leggi_genera,
    righe_semif, risposta_domanda,
)


def richiesta_base():
    return {
        "stato": "Mittente: Mario Rossi <rossi@esempio.it>\nOggetto: Preventivo\nTesto: ci serve un'offerta",
        "domande": [
            {"id": "gruppo", "domanda": "A quale gruppo appartiene questa mail?",
             "opzioni": [{"id": "Clienti", "descrizione": "un cliente chiede o conferma un lavoro"},
                         {"id": "Fornitori", "descrizione": "un fornitore manda offerte o listini"},
                         {"id": "Altro", "descrizione": ""}]},
            {"id": "da_rispondere", "domanda": "Serve una risposta?",
             "opzioni": [{"id": "si", "descrizione": "sì"}, {"id": "no"}]},
        ],
    }


def test_legge_una_richiesta_valida():
    stato, domande = leggi_decidi(richiesta_base())
    assert stato.startswith("Mittente: Mario Rossi")
    assert [d.id for d in domande] == ["gruppo", "da_rispondere"]
    assert domande[0].opzioni[0] == Opzione("Clienti", "un cliente chiede o conferma un lavoro")
    assert domande[1].opzioni[1].descrizione == ""


def test_descrizione_per_il_modello_porta_il_nome_davanti():
    assert Opzione("Clienti", "un cliente chiede").per_modello == "Clienti: un cliente chiede"
    assert Opzione("Altro", "").per_modello == "Altro"
    assert Opzione("si", "si").per_modello == "si"


@pytest.mark.parametrize("guasto, frase", [
    (lambda r: r.pop("stato"), "stato"),
    (lambda r: r.update(stato="   "), "stato"),
    (lambda r: r.update(domande=[]), "almeno una domanda"),
    (lambda r: r.update(domande=r["domande"] * (MAX_DOMANDE + 1)), "Troppe domande"),
    (lambda r: r["domande"].append(dict(r["domande"][0])), "stesso id"),
    (lambda r: r["domande"][0].update(opzioni=[{"id": "Clienti"}]), "da 2 a 16"),
    (lambda r: r["domande"][0]["opzioni"].append({"id": "Clienti"}), "stesso id"),
    (lambda r: r["domande"][0]["opzioni"].append({"id": "X", "descrizione": 3}), "testo"),
    (lambda r: r["domande"][1].pop("domanda"), "testo della domanda"),
])
def test_errori_leggibili(guasto, frase):
    richiesta = richiesta_base()
    guasto(richiesta)
    with pytest.raises(ErroreRichiesta, match=frase):
        leggi_decidi(richiesta)


def test_richiesta_non_oggetto():
    with pytest.raises(ErroreRichiesta, match="oggetto JSON"):
        leggi_decidi([1, 2])


def test_righe_semif_hanno_lo_stesso_stato():
    stato, domande = leggi_decidi(richiesta_base())
    righe = righe_semif(stato, domande)
    assert len(righe) == 2 and all(r["state"] == stato for r in righe)
    assert righe[0]["options"][0] == {"id": "Clienti", "description": "Clienti: un cliente chiede o conferma un lavoro"}


def test_risposta_sceglie_la_piu_probabile_e_arrotonda():
    domanda = Domanda("urgenza", "?", (Opzione("alta", ""), Opzione("normale", ""), Opzione("bassa", "")))
    esito = risposta_domanda(domanda, [0.1, 0.723456, 0.176544], 0.98765)
    assert esito == {"scelta": "normale", "probabilita": {"alta": 0.1, "normale": 0.7235, "bassa": 0.1765},
                     "massa_opzioni": 0.9877}


def test_a_parita_vince_la_prima():
    domanda = Domanda("x", "?", (Opzione("si", ""), Opzione("no", "")))
    assert risposta_domanda(domanda, [0.5, 0.5])["scelta"] == "si"


def test_genera_valida_ruoli_e_max_token():
    messaggi, max_token = leggi_genera({"messaggi": [{"ruolo": "sistema", "testo": "Sei cortese."},
                                                     {"ruolo": "utente", "testo": "Scrivi due righe."}]})
    assert messaggi == [{"role": "system", "content": "Sei cortese."},
                        {"role": "user", "content": "Scrivi due righe."}]
    assert max_token == 600
    with pytest.raises(ErroreRichiesta, match="ruolo"):
        leggi_genera({"messaggi": [{"ruolo": "capo", "testo": "x"}]})
    with pytest.raises(ErroreRichiesta, match="utente"):
        leggi_genera({"messaggi": [{"ruolo": "sistema", "testo": "x"}]})
    with pytest.raises(ErroreRichiesta, match="max_token"):
        leggi_genera({"messaggi": [{"ruolo": "utente", "testo": "x"}], "max_token": 0})
    with pytest.raises(ErroreRichiesta, match="max_token"):
        leggi_genera({"messaggi": [{"ruolo": "utente", "testo": "x"}], "max_token": True})
