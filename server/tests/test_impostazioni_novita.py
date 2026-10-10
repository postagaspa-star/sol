from conftest import scrivi_tutto, smista_tutto


def test_impostazioni_iniziali(client, auth):
    assert client.get("/api/impostazioni", headers=auth).json() == {
        "modalita_prova": True, "soglia_sicurezza": 0.85, "giorni_risposta": 2, "giorni_sollecito": 7,
        "intervallo_controllo_minuti": 5, "firma": "Cordiali saluti\nManuel", "tono": "cortese e diretto",
        "scrittori": ["groq", "cerebras", "locale"], "conserva_giorni": 180,
    }


def test_impostazioni_cambio_parziale(client, auth):
    r = client.patch("/api/impostazioni", json={"giorni_risposta": 3, "scrittori": ["locale", "groq", "locale"]}, headers=auth)
    assert r.status_code == 200
    assert r.json()["giorni_risposta"] == 3
    assert r.json()["scrittori"] == ["locale", "groq"]
    assert r.json()["giorni_sollecito"] == 7
    assert client.get("/api/impostazioni", headers=auth).json()["giorni_risposta"] == 3


def test_impostazioni_validazione(client, auth):
    for corpo in (
        {"soglia_sicurezza": 1.5}, {"giorni_risposta": 0}, {"scrittori": []}, {"scrittori": ["openai"]},
        {"modalita_prova": "no"}, {"intervallo_controllo_minuti": 0}, {"tono": "  "}, {"conserva_giorni": 1},
    ):
        r = client.patch("/api/impostazioni", json=corpo, headers=auth)
        assert r.status_code == 422, corpo
        assert r.json()["codice"] == "non_valido"


def test_nessuna_chiave_nelle_impostazioni(client, auth, monkeypatch):
    testo = client.get("/api/impostazioni", headers=auth).text.lower()
    assert "key" not in testo and "chiave" not in testo


def test_novita(client, auth, finta, servizi, orologio):
    primo = client.get("/api/novita", headers=auth).json()
    assert primo == {"cursore": "0", "urgenti": [], "da_rispondere_nuove": 0, "bozze_pronte": 0,
                     "bozze_nuove": [], "scadenze_domani": []}
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    dopo = client.get(f"/api/novita?dal={primo['cursore']}", headers=auth).json()
    assert {m["oggetto"] for m in dopo["urgenti"]} == {"Sopralluogo urgente quadro elettrico", "URGENTE: inoltro fatture"}
    assert dopo["da_rispondere_nuove"] >= 2
    pronte = client.get("/api/bozze?stato=pronta", headers=auth).json()
    assert dopo["bozze_pronte"] == len(pronte) >= 2
    assert {b["id"] for b in dopo["bozze_nuove"]} == {b["id"] for b in pronte}
    assert set(dopo["bozze_nuove"][0]) == {"id", "mail_id", "oggetto"}
    assert int(dopo["cursore"]) > 0
    ancora = client.get(f"/api/novita?dal={dopo['cursore']}", headers=auth).json()
    assert ancora["urgenti"] == [] and ancora["da_rispondere_nuove"] == 0 and ancora["bozze_nuove"] == []
    # 15.6 e 15.13: bozze_pronte è il TOTALE delle pronte; per notificare si usa solo bozze_nuove.
    assert ancora["bozze_pronte"] == len(pronte)
    assert ancora["cursore"] == dopo["cursore"]


def test_novita_mail_valutata_dopo_il_cursore(client, auth, finta, servizi):
    """Il telefono ha già visto l'id della mail, ma la valutazione arriva dopo: la notifica scatta lo stesso."""
    cursore = client.get("/api/novita", headers=auth).json()["cursore"]
    client.get("/api/mail", headers=auth)
    smista_tutto(servizi)
    assert client.get(f"/api/novita?dal={cursore}", headers=auth).json()["urgenti"]


def test_novita_scadenze_domani(client, auth):
    client.post("/api/scadenze", json={"descrizione": "Sopralluogo", "quando": "2026-10-11", "ora": "09:00"}, headers=auth)
    client.post("/api/scadenze", json={"descrizione": "Più avanti", "quando": "2026-10-20"}, headers=auth)
    cursore = client.get("/api/novita", headers=auth).json()["cursore"]
    dati = client.get(f"/api/novita?dal={cursore}", headers=auth).json()
    assert [s["descrizione"] for s in dati["scadenze_domani"]] == ["Sopralluogo"]


def test_novita_cursore_sbagliato(client, auth):
    assert client.get("/api/novita?dal=abc", headers=auth).status_code == 422
