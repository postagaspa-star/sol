from app.caselle import finta as modulo_finta


def test_crea_finta_e_arrivano_le_mail(client, auth, finta):
    assert finta["tipo"] == "finta"
    assert finta["indirizzo"] == "demo@esempio.it"
    assert finta["stato"] == "collegata"
    assert finta["attiva_dal"] == "2026-09-30T10:00:00+02:00"  # 10 giorni prima
    mail = client.get("/api/mail?limite=200", headers=auth).json()["mail"]
    assert len(mail) == 10
    # In ordine dalla più recente.
    date = [m["ricevuta_il"] for m in mail]
    assert date == sorted(date, reverse=True)
    assert mail[0]["oggetto"] == "Listino 2027"


def test_le_mail_arrivano_col_tempo(client, auth, finta, servizi, orologio):
    orologio.avanza(minutes=8)
    assert servizi.lavoro.controlla_casella(finta["id"]) == 1
    orologio.avanza(minutes=60)
    assert servizi.lavoro.controlla_casella(finta["id"]) == 2
    assert servizi.lavoro.controlla_casella(finta["id"]) == 0  # nessun doppione
    orologio.avanza(days=2)
    assert servizi.lavoro.controlla_casella(finta["id"]) == 2
    assert len(client.get("/api/mail?limite=200", headers=auth).json()["mail"]) == len(modulo_finta.MAIL)


def test_controlla_forza_un_controllo(client, auth, finta, orologio):
    orologio.avanza(minutes=8)
    r = client.post(f"/api/caselle/{finta['id']}/controlla", headers=auth)
    assert r.status_code == 202
    assert len(client.get("/api/mail?limite=200", headers=auth).json()["mail"]) == 11


def test_dettaglio_rinomina_elenco(client, auth, finta):
    r = client.patch(f"/api/caselle/{finta['id']}", json={"nome": "Demo studio"}, headers=auth)
    assert r.status_code == 200 and r.json()["nome"] == "Demo studio"
    assert client.get(f"/api/caselle/{finta['id']}", headers=auth).json()["nome"] == "Demo studio"
    assert [c["id"] for c in client.get("/api/caselle", headers=auth).json()] == [finta["id"]]
    assert client.get("/api/caselle/999", headers=auth).status_code == 404


def test_elimina_toglie_le_mail_dal_server(client, auth, finta, servizi):
    from conftest import scrivi_tutto, smista_tutto

    smista_tutto(servizi)
    scrivi_tutto(servizi)
    assert client.get("/api/bozze", headers=auth).json()
    r = client.delete(f"/api/caselle/{finta['id']}", headers=auth)
    assert r.status_code == 200
    assert client.get("/api/mail", headers=auth).json() == {"mail": [], "altre": False}
    assert client.get("/api/bozze", headers=auth).json() == []
    assert client.get("/api/caselle", headers=auth).json() == []


def test_guasto_della_casella_si_vede(client, auth, finta, servizi):
    modulo_finta.stato(finta["id"]).guasti.add("controllo")
    servizi.lavoro.controlla_casella(finta["id"])
    c = client.get(f"/api/caselle/{finta['id']}", headers=auth).json()
    # Un problema passeggero: la casella resta collegata, con l'avviso (si riprova da soli).
    assert c["stato"] == "collegata"
    assert "non risponde" in c["errore"]
    modulo_finta.stato(finta["id"]).guasti.clear()
    servizi.lavoro.controlla_casella(finta["id"])
    c = client.get(f"/api/caselle/{finta['id']}", headers=auth).json()
    assert c["stato"] == "collegata" and c["errore"] is None


def test_tipo_sconosciuto(client, auth):
    assert client.post("/api/caselle", json={"tipo": "yahoo"}, headers=auth).status_code == 422
