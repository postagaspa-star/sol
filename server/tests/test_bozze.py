import pytest

from app.caselle import finta as modulo_finta
from conftest import invia, mail_per_oggetto, scrivi_tutto, smista_tutto


@pytest.fixture
def bozza(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["bozza"]["stato"] == "pronta"
    return client.get(f"/api/bozze/{m['bozza']['id']}", headers=auth).json()


def test_bozza_automatica(bozza, decisore):
    assert bozza["tipo"] == "risposta"
    assert bozza["a"] == ["laura.bianchi@esempio.it"]
    assert bozza["oggetto"] == "Re: Sopralluogo urgente quadro elettrico"
    # Il <think> del modello sparisce, la firma la aggiunge Sol.
    assert bozza["testo"] == "Buongiorno,\ngrazie per la mail. Le rispondo appena possibile.\n\nCordiali saluti\nManuel"
    assert bozza["fornitore"] == "locale"
    assert bozza["modificata"] is False and bozza["inviata_il"] is None and bozza["errore"] is None
    prompt_bozza = next(m for m in decisore.generati if "Prepara la bozza" in m[-1]["testo"])
    assert "tono cortese e diretto" in prompt_bozza[0]["testo"]


def test_tono_e_firma_dalle_impostazioni(client, auth, finta, servizi, decisore):
    client.patch("/api/impostazioni", json={"tono": "formale", "firma": "Distinti saluti\nPer. Ind. Neri"}, headers=auth)
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    b = client.get(f"/api/bozze/{m['bozza']['id']}", headers=auth).json()
    assert b["testo"].endswith("Distinti saluti\nPer. Ind. Neri")
    assert any("tono formale" in m[0]["testo"] for m in decisore.generati)


def test_elenco_bozze(client, auth, bozza):
    pronte = client.get("/api/bozze?stato=pronta", headers=auth).json()
    assert bozza["id"] in [b["id"] for b in pronte]
    assert client.get("/api/bozze?stato=strana", headers=auth).status_code == 422
    assert client.get("/api/bozze/9999", headers=auth).status_code == 404


def test_modifica(client, auth, bozza):
    r = client.put(f"/api/bozze/{bozza['id']}", json={"testo": "Va bene domani alle 9.", "cc": ["Neri@Esempio.it"]}, headers=auth)
    assert r.status_code == 200
    assert r.json()["testo"] == "Va bene domani alle 9."
    assert r.json()["cc"] == ["neri@esempio.it"]
    assert r.json()["modificata"] is True
    assert r.json()["a"] == ["laura.bianchi@esempio.it"]
    r = client.put(f"/api/bozze/{bozza['id']}", json={"a": ["non-un-indirizzo"]}, headers=auth)
    assert r.status_code == 422


@pytest.mark.parametrize("corpo", [None, {}, {"conferma": False}, {"conferma": "true"}, {"conferma": 1}, {"conferma": "si"},
                                   {"conferma": True}, {"conferma": True, "versione": "2"}])
def test_invio_senza_conferma_rifiutato(client, auth, bozza, finta, corpo):
    if corpo is None:
        r = client.post(f"/api/bozze/{bozza['id']}/invia", headers=auth)
    else:
        r = client.post(f"/api/bozze/{bozza['id']}/invia", json=corpo, headers=auth)
    assert r.status_code == 422
    assert modulo_finta.stato(finta["id"]).invii == []
    assert client.get(f"/api/bozze/{bozza['id']}", headers=auth).json()["stato"] == "pronta"


def test_invio_con_conferma(client, auth, bozza, finta):
    r = invia(client, auth, bozza['id'])
    assert r.status_code == 200, r.text
    assert r.json()["stato"] == "inviata" and r.json()["inviata_il"].endswith("+02:00")
    invii = modulo_finta.stato(finta["id"]).invii
    assert len(invii) == 1
    assert invii[0]["a"] == ["laura.bianchi@esempio.it"]
    assert invii[0]["in_risposta_a"] == "<finta-7@esempio.it>"  # stesso filo
    assert invii[0]["oggetto"] == "Re: Sopralluogo urgente quadro elettrico"
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["risposta_data"] is True
    # Non si invia due volte.
    r = invia(client, auth, bozza['id'])
    assert r.status_code == 409
    assert len(modulo_finta.stato(finta["id"]).invii) == 1
    assert client.put(f"/api/bozze/{bozza['id']}", json={"testo": "x"}, headers=auth).status_code == 409


def test_invio_fallito_si_vede_e_si_riprova(client, auth, bozza, finta):
    modulo_finta.stato(finta["id"]).guasti.add("invio")
    r = invia(client, auth, bozza['id'])
    assert r.status_code == 502
    assert r.json()["codice"] == "casella_non_raggiungibile"
    assert "non ha inviato" in r.json()["errore"]
    b = client.get(f"/api/bozze/{bozza['id']}", headers=auth).json()
    assert b["stato"] == "errore" and "non ha inviato" in b["errore"]
    modulo_finta.stato(finta["id"]).guasti.clear()
    r = invia(client, auth, bozza['id'])
    assert r.status_code == 200 and r.json()["stato"] == "inviata" and r.json()["errore"] is None


def test_scarta(client, auth, bozza):
    r = client.post(f"/api/bozze/{bozza['id']}/scarta", headers=auth)
    assert r.json()["stato"] == "scartata"
    assert invia(client, auth, bozza['id']).status_code == 409
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["bozza"] is None


def test_rifai_con_istruzioni(client, auth, bozza, servizi, decisore):
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    r = client.post(f"/api/mail/{m['id']}/bozza", json={"istruzioni": "digli che giovedì alle 10 va bene"}, headers=auth)
    assert r.status_code == 202
    rifatta = r.json()
    # Si rifà la stessa bozza (contratto 15.13): niente doppioni, la versione sale.
    assert rifatta["id"] == bozza["id"]
    assert rifatta["stato"] == "in_preparazione" and rifatta["istruzioni"] == "digli che giovedì alle 10 va bene"
    assert rifatta["versione"] == bozza["versione"] + 1
    assert client.put(f"/api/bozze/{rifatta['id']}", json={"testo": "x"}, headers=auth).status_code == 409
    scrivi_tutto(servizi)
    pronta = client.get(f"/api/bozze/{rifatta['id']}", headers=auth).json()
    assert pronta["stato"] == "pronta" and pronta["versione"] == bozza["versione"] + 2
    ultimo = next(g for g in reversed(decisore.generati) if "Prepara la bozza" in g[-1]["testo"])
    # Le istruzioni dell'utente sono fuori dal recinto della mail; la bozza attuale è la base.
    testo = ultimo[-1]["testo"]
    assert testo.index("giovedì alle 10") > testo.index("<<<FINE-MAIL-")
    assert "<<<BOZZA-" in testo and "Le rispondo appena possibile" in testo


def test_scrittore_giu_bozza_chiesta_in_errore(client, auth, finta, servizi, decisore):
    m = mail_per_oggetto(client, auth, "Listino 2027")
    r = client.post(f"/api/mail/{m['id']}/bozza", json={}, headers=auth)
    assert r.status_code == 202
    decisore.genera_giu = True
    assert servizi.lavoro.passo_scrittore() == "guasto"
    b = client.get(f"/api/bozze/{r.json()['id']}", headers=auth).json()
    assert b["stato"] == "errore" and "scrittura" in b["errore"]
    # Scritta a mano diventa pronta e si può inviare.
    b = client.put(f"/api/bozze/{b['id']}", json={"testo": "Grazie, ricevuto."}, headers=auth).json()
    assert b["stato"] == "pronta" and b["errore"] is None


def test_bozze_automatiche_riprovano_prima_di_arrendersi(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    decisore.genera_giu = True
    servizi.lavoro.passo_scrittore()
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    b = client.get("/api/bozze", headers=auth).json()
    assert all(x["stato"] == "in_preparazione" for x in b)


def test_503_senza_scrittori(client, auth, finta, decisore):
    decisore.giu = True  # niente chiavi e locale spento
    m = mail_per_oggetto(client, auth, "Listino 2027")
    r = client.post(f"/api/mail/{m['id']}/bozza", json={}, headers=auth)
    assert r.status_code == 503
    assert r.json()["codice"] == "scrittore_non_disponibile"


def test_invio_bozza_vuota_rifiutato(client, auth, finta, servizi, decisore):
    m = mail_per_oggetto(client, auth, "Listino 2027")
    b = client.post(f"/api/mail/{m['id']}/bozza", json={}, headers=auth).json()
    decisore.genera_giu = True
    servizi.lavoro.passo_scrittore()
    r = invia(client, auth, b['id'])
    assert r.status_code == 422 and "vuoto" in r.json()["errore"]
