from app.caselle import finta as modulo_finta
from conftest import invia, mail_per_oggetto, scrivi_tutto, smista_tutto


def test_aspettano_te(client, auth, finta, servizi):
    smista_tutto(servizi)
    dati = client.get("/api/indietro", headers=auth).json()
    oggetti = {x["mail"]["oggetto"]: x["giorni"] for x in dati["aspettano_te"]}
    # Rossi (8 giorni, chiede l'offerta) sì; il sopralluogo di oggi no: è troppo recente.
    assert oggetti == {"Offerta capannone Bassano": 8}
    client.patch("/api/impostazioni", json={"giorni_risposta": 10}, headers=auth)
    assert client.get("/api/indietro", headers=auth).json()["aspettano_te"] == []


def test_risposta_data_toglie_dalla_lista(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "capannone")
    invia(client, auth, m['bozza']['id'])
    assert client.get("/api/indietro", headers=auth).json()["aspettano_te"] == []


def test_aspetti_tu(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    dati = client.get("/api/indietro", headers=auth).json()["aspetti_tu"]
    # Neri (9 giorni, chiede conferma, nessuna risposta) sì; Gallo ha risposto nel filo;
    # Verdi era un "ricevuto, grazie" e non aspetta niente.
    assert len(dati) == 1
    voce = dati[0]
    assert voce["inviata"]["a"] == ["neri@esempio.it"]
    assert voce["inviata"]["oggetto"] == "Offerta compressore"
    assert voce["giorni"] == 9
    assert voce["bozza_sollecito"] is None
    assert set(voce["inviata"]) == {"id", "casella_id", "a", "oggetto", "inviata_il", "anteprima"}
    # Il decisore è stato interrogato per le inviate senza risposta, non per quella di Gallo.
    domande_inviate = [s for s, d in decisore.chiamate if d == ["aspetta"]]
    assert len(domande_inviate) == 2 and not any("Gallo" in s for s in domande_inviate)


def test_aspetti_tu_rispetta_i_giorni(client, auth, finta, servizi):
    smista_tutto(servizi)
    client.patch("/api/impostazioni", json={"giorni_sollecito": 10}, headers=auth)
    assert client.get("/api/indietro", headers=auth).json()["aspetti_tu"] == []


def test_sollecito_e_invio(client, auth, finta, servizi, orologio):
    smista_tutto(servizi)
    voce = client.get("/api/indietro", headers=auth).json()["aspetti_tu"][0]
    r = client.post(f"/api/indietro/{voce['inviata']['id']}/sollecito", headers=auth)
    assert r.status_code == 202
    b = r.json()
    assert b["tipo"] == "sollecito" and b["stato"] == "in_preparazione"
    assert b["a"] == ["neri@esempio.it"] and b["oggetto"] == "Re: Offerta compressore"
    assert b["mail_id"] is None and b["inviata_id"] == voce["inviata"]["id"]
    voce = client.get("/api/indietro", headers=auth).json()["aspetti_tu"][0]
    assert voce["bozza_sollecito"] == {"id": b["id"], "stato": "in_preparazione"}
    scrivi_tutto(servizi)
    pronta = client.get(f"/api/bozze/{b['id']}", headers=auth).json()
    assert pronta["stato"] == "pronta" and pronta["testo"].endswith("Manuel")
    r = invia(client, auth, b['id'])
    assert r.status_code == 200
    invio = modulo_finta.stato(finta["id"]).invii[0]
    assert invio["in_risposta_a"] == "<finta-inviata-1@esempio.it>"
    assert invio["filo"] == "f-neri"
    # Sollecitata: esce dalla lista finché non passano di nuovo i giorni.
    smista_tutto(servizi)
    assert client.get("/api/indietro", headers=auth).json()["aspetti_tu"] == []


def test_ignora(client, auth, finta, servizi):
    smista_tutto(servizi)
    voce = client.get("/api/indietro", headers=auth).json()["aspetti_tu"][0]
    assert client.post(f"/api/indietro/{voce['inviata']['id']}/ignora", headers=auth).status_code == 200
    assert client.get("/api/indietro", headers=auth).json()["aspetti_tu"] == []
    assert client.post("/api/indietro/999/ignora", headers=auth).status_code == 404
    assert client.post("/api/indietro/999/sollecito", headers=auth).status_code == 404


def test_risposta_arrivata_dopo_toglie_dalla_lista(client, auth, finta, servizi, app):
    from app import clock, db

    smista_tutto(servizi)
    voce = client.get("/api/indietro", headers=auth).json()["aspetti_tu"][0]
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute(
            "INSERT INTO mail (casella_id, id_esterno, filo, mittente_indirizzo, oggetto, ricevuta_il, riferimenti)"
            " VALUES (?, 'risposta-neri', 'altro-filo', 'neri@esempio.it', 'Re: Offerta compressore', ?, ?)",
            (finta["id"], clock.adesso(), "<finta-inviata-1@esempio.it>"),
        )
    assert client.get("/api/indietro", headers=auth).json()["aspetti_tu"] == []
    assert voce


def test_risposta_data_da_fuori_sol(client, auth, servizi, app, orologio):
    """Se l'utente risponde dal sito o da Outlook, la mail non aspetta più lui."""
    from app import clock, db
    from app.caselle import InviataGrezza

    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    smista_tutto(servizi)
    with db.aperta(app.state.settings.db_path) as conn:
        servizi.lavoro._salva_inviata(conn, 1, InviataGrezza(
            "risposta-a-rossi", "<r@esempio.it>", "f-rossi", "<finta-1@esempio.it>", ["rossi@esempio.it"],
            "Re: Offerta capannone Bassano", "Ecco l'offerta.", clock.now(),
        ))
    assert mail_per_oggetto(client, auth, "capannone")["risposta_data"] is True
    assert client.get("/api/indietro", headers=auth).json()["aspettano_te"] == []
