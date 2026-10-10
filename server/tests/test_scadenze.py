from app import prompt
from conftest import mail_per_oggetto, scrivi_tutto, smista_tutto


def test_scadenze_estratte_dalle_mail_smistate(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    scadenze = client.get("/api/scadenze?stato=aperta", headers=auth).json()
    assert scadenze
    s = scadenze[0]
    assert set(s) == {"id", "mail_id", "descrizione", "quando", "ora", "stato", "creata_il"}
    assert s["quando"] == "2026-10-15" and s["ora"] == "09:30" and s["stato"] == "aperta"
    # Mai dalle newsletter.
    news = {mail_per_oggetto(client, auth, o)["id"] for o in ("Webinar", "Novità normative")}
    assert not news & {x["mail_id"] for x in scadenze}
    # Una per mail (niente doppioni anche se si riprova).
    assert len({x["mail_id"] for x in scadenze}) == len(scadenze)


def test_scadenze_solo_dopo_lo_smistamento(client, auth, finta, servizi, monkeypatch):
    from app import lavoro

    monkeypatch.setattr(lavoro, "ARRETRATO_SMISTAMENTO", 100)  # qui conta solo l'ordine dei lavori
    scrivi_tutto(servizi)
    ids = {x["mail_id"] for x in client.get("/api/scadenze", headers=auth).json()}
    pec = mail_per_oggetto(client, auth, "integrazione pratica")["id"]  # smistata da regola
    cliente = mail_per_oggetto(client, auth, "quadro elettrico")["id"]  # ancora in attesa
    assert pec in ids and cliente not in ids


def test_leggi_scadenze_robusta():
    assert prompt.leggi_scadenze("niente") == []
    assert prompt.leggi_scadenze("[{\"descrizione\": \"x\", \"quando\": \"2026-02-30\"}]") == []
    assert prompt.leggi_scadenze("[{\"descrizione\": \"\", \"quando\": \"2026-10-20\"}]") == []
    assert prompt.leggi_scadenze("Ecco [{\"descrizione\": \"Relazione\", \"quando\": \"2026-10-20\", \"ora\": \"25:00\"}] fine") == [
        {"descrizione": "Relazione", "quando": "2026-10-20", "ora": None}
    ]


def test_scadenze_passate_scartate(client, auth, finta, servizi, decisore):
    decisore.testo_generato = '[{"descrizione": "Vecchia", "quando": "2026-01-01", "ora": null}]'
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    assert client.get("/api/scadenze", headers=auth).json() == []


def test_scadenza_a_mano_e_modifica(client, auth, finta):
    m = mail_per_oggetto(client, auth, "capannone")
    r = client.post("/api/scadenze", json={"descrizione": "Offerta a Rossi", "quando": "2026-10-16", "mail_id": m["id"]}, headers=auth)
    assert r.status_code == 201
    s = r.json()
    assert s["ora"] is None and s["mail_id"] == m["id"]
    r = client.patch(f"/api/scadenze/{s['id']}", json={"stato": "fatta"}, headers=auth)
    assert r.json()["stato"] == "fatta"
    assert client.get("/api/scadenze?stato=aperta", headers=auth).json() == []
    r = client.patch(f"/api/scadenze/{s['id']}", json={"quando": "2026-10-20", "descrizione": "Offerta capannone"}, headers=auth)
    assert r.json()["quando"] == "2026-10-20" and r.json()["descrizione"] == "Offerta capannone"


def test_scadenze_validazione(client, auth):
    assert client.post("/api/scadenze", json={"descrizione": "x", "quando": "16/10/2026"}, headers=auth).status_code == 422
    assert client.post("/api/scadenze", json={"descrizione": "x", "quando": "2026-10-16", "ora": "9"}, headers=auth).status_code == 422
    assert client.post("/api/scadenze", json={"descrizione": "x", "quando": "2026-10-16", "mail_id": 99}, headers=auth).status_code == 404
    s = client.post("/api/scadenze", json={"descrizione": "x", "quando": "2026-10-16", "ora": "09:00"}, headers=auth).json()
    assert client.patch(f"/api/scadenze/{s['id']}", json={"stato": "rimandata"}, headers=auth).status_code == 422
    assert client.patch(f"/api/scadenze/{s['id']}", json={"quando": "domani"}, headers=auth).status_code == 422
    assert client.patch("/api/scadenze/999", json={"stato": "fatta"}, headers=auth).status_code == 404


def test_ordine_per_data(client, auth):
    for quando in ("2026-10-20", "2026-10-12", "2026-10-15"):
        client.post("/api/scadenze", json={"descrizione": quando, "quando": quando}, headers=auth)
    assert [s["quando"] for s in client.get("/api/scadenze", headers=auth).json()] == ["2026-10-12", "2026-10-15", "2026-10-20"]
