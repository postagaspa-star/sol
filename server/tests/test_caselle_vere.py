"""Gmail e Outlook attraverso le API, con i server finti al posto della rete."""

import logging
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from app import db
from conftest import ORA_INIZIALE, collega_finti, invia
from finti_server import AccessoFinto, GmailFinto, GraphFinto, messaggio

PASSWORD = "abcd efgh ijkl mnop"


@pytest.fixture
def gmail(servizi):
    server = GmailFinto()
    servizi.fabbrica.imap_factory = server.imap
    servizi.fabbrica.smtp_factory = server.smtp
    return server


def test_aggiungi_gmail(client, auth, gmail, app, caplog):
    gmail.aggiungi(messaggio(oggetto="Prima di Sol"), quando=ORA_INIZIALE - timedelta(hours=1))
    caplog.set_level(logging.DEBUG)
    r = client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "Studio@Esempio.it", "nome": "Studio", "password_app": PASSWORD}, headers=auth)
    assert r.status_code == 201, r.text
    c = r.json()
    assert c["tipo"] == "gmail" and c["indirizzo"] == "studio@esempio.it" and c["stato"] == "collegata"
    assert c["attiva_dal"] == "2026-10-10T10:00:00+02:00"
    assert "password" not in r.text and "abcd" not in r.text
    # La password sta cifrata nel database, mai in chiaro; e mai nei log.
    with db.aperta(app.state.settings.db_path) as conn:
        riga = conn.execute("SELECT segreto_cifrato FROM caselle").fetchone()
    assert riga["segreto_cifrato"] and "abcd" not in riga["segreto_cifrato"]
    assert app.state.servizi.cifrario.decifra(riga["segreto_cifrato"]) == PASSWORD
    assert "abcd" not in caplog.text and "efgh" not in caplog.text
    # La mail di prima del collegamento non si tocca.
    assert client.get("/api/mail", headers=auth).json()["mail"] == []
    for percorso in ("/api/caselle", f"/api/caselle/{c['id']}", "/api/salute"):
        assert "abcd" not in client.get(percorso, headers=auth).text


def test_gmail_arrivano_le_mail_e_si_risponde(client, auth, gmail, servizi, orologio):
    c = client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": PASSWORD}, headers=auth).json()
    gmail.aggiungi(messaggio(oggetto="Sopralluogo?", testo="Può venire giovedì?", id_messaggio="<g1@esempio.it>"),
                   quando=ORA_INIZIALE + timedelta(minutes=10))
    orologio.avanza(minutes=15)
    assert servizi.lavoro.controlla_casella(c["id"]) == 1
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["oggetto"] == "Sopralluogo?" and m["casella"] == "studio@esempio.it"
    from conftest import scrivi_tutto, smista_tutto

    smista_tutto(servizi)
    scrivi_tutto(servizi)
    bozza_id = client.get(f"/api/mail/{m['id']}", headers=auth).json()["bozza"]["id"]
    r = invia(client, auth, bozza_id)
    assert r.status_code == 200, r.text
    assert gmail.inviati[0]["In-Reply-To"] == "<g1@esempio.it>"
    assert gmail.inviati[0]["Subject"] == "Re: Sopralluogo?"
    # Fuori prova lo spostamento mette l'etichetta.
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert gmail.messaggi[0]["etichette"] == {"Sol/Clienti"}
    client.post(f"/api/mail/{m['id']}/letta", json={"letta": True}, headers=auth)
    assert gmail.messaggi[0]["flags"] == {"\\Seen"}


def test_gmail_password_sbagliata(client, auth, gmail):
    r = client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": "sbagliata"}, headers=auth)
    assert r.status_code == 422
    assert r.json()["errore"].startswith("Google ha rifiutato la password per le app")
    assert client.get("/api/caselle", headers=auth).json() == []


def test_gmail_irraggiungibile(client, auth, gmail):
    gmail.rete_giu = True
    r = client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": PASSWORD}, headers=auth)
    assert r.status_code == 502 and r.json()["codice"] == "casella_non_raggiungibile"


def test_gmail_doppia_e_dati_mancanti(client, auth, gmail):
    corpo = {"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": PASSWORD}
    assert client.post("/api/caselle", json=corpo, headers=auth).status_code == 201
    assert client.post("/api/caselle", json=corpo, headers=auth).status_code == 409
    assert client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio", "password_app": PASSWORD}, headers=auth).status_code == 422
    assert client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "a@esempio.it"}, headers=auth).status_code == 422


def test_password_cambiata_su_google_non_si_riprova_da_sola(client, auth, gmail, servizi):
    c = client.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": PASSWORD}, headers=auth).json()
    gmail.password = "nuova-password-xx"
    servizi.lavoro.giro_posta()
    stato = client.get(f"/api/caselle/{c['id']}", headers=auth).json()
    assert stato["stato"] == "errore" and "password" in stato["errore"]
    tentativi = gmail.login_imap
    servizi.lavoro.giro_posta()
    assert gmail.login_imap == tentativi  # Google blocca chi sbaglia troppe volte: niente tentativi automatici


def test_chiave_cambiata_si_dice_in_chiaro(ambiente, orologio, decisore, monkeypatch):
    from cryptography.fernet import Fernet

    from app.main import create_app

    server = GmailFinto()
    app = create_app()
    s = collega_finti(app, decisore)
    s.fabbrica.imap_factory, s.fabbrica.smtp_factory = server.imap, server.smtp
    with TestClient(app) as c:
        from app import abbina

        with db.aperta(app.state.settings.db_path) as conn:
            codice, _ = abbina.nuovo_codice(conn)
            token, _ = abbina.abbina(conn, codice, "x", "pc", abbina.clock.now())
        h = {"Authorization": f"Bearer {token}"}
        cid = c.post("/api/caselle", json={"tipo": "gmail", "indirizzo": "studio@esempio.it", "password_app": PASSWORD}, headers=h).json()["id"]
    monkeypatch.setenv("SOL_CHIAVE", Fernet.generate_key().decode())
    app2 = create_app()
    s2 = collega_finti(app2, decisore)
    s2.fabbrica.imap_factory, s2.fabbrica.smtp_factory = server.imap, server.smtp
    with TestClient(app2) as c:
        s2.lavoro.controlla_casella(cid)
        stato = c.get(f"/api/caselle/{cid}", headers=h).json()
    assert stato["stato"] == "errore" and "chiave del server" in stato["errore"]


# --- Outlook --------------------------------------------------------------------------------


@pytest.fixture
def outlook(servizi):
    graph = GraphFinto()
    servizi.settings = servizi.settings.__class__(**{**servizi.settings.__dict__, "ms_client_id": "id-app-finta"})
    servizi.fabbrica.ms_client_id = "id-app-finta"
    servizi.fabbrica.graph_transport = graph.trasporto()
    accessi = []

    def fabbrica_accesso(cache):
        a = AccessoFinto(cache)
        accessi.append(a)
        return a

    servizi.fabbrica.accesso_factory = fabbrica_accesso
    graph.accessi = accessi
    return graph


def test_outlook_non_configurato(client, auth):
    r = client.post("/api/caselle", json={"tipo": "outlook", "nome": "Studio"}, headers=auth)
    assert r.status_code == 422 and "Outlook" in r.json()["errore"]


def test_outlook_collegamento_con_codice(client, auth, outlook, app, servizi):
    rimandato = []
    servizi.fabbrica.in_sottofondo = lambda f, *a: rimandato.append((f, a))
    r = client.post("/api/caselle", json={"tipo": "outlook", "nome": "Studio"}, headers=auth)
    assert r.status_code == 202, r.text
    dati = r.json()
    assert dati["codice_utente"] == "ABCD-EFGH"
    assert dati["url_verifica"] == "https://microsoft.com/devicelogin"
    assert dati["scade_il"] == "2026-10-10T10:15:00+02:00"
    assert dati["casella"]["stato"] == "in_collegamento"
    cid = dati["casella"]["id"]
    # L'utente fa l'accesso: il server completa da solo.
    outlook.mail("o1", oggetto="Offerta impianto?", quando="2026-10-10T08:00:00Z")
    servizi.fabbrica.in_sottofondo = lambda f, *a: f(*a)
    for f, a in rimandato:
        f(*a)
    c = client.get(f"/api/caselle/{cid}", headers=auth).json()
    assert c["stato"] == "collegata" and c["indirizzo"] == "studio@esempio.it"
    with db.aperta(app.state.settings.db_path) as conn:
        cifrato = conn.execute("SELECT segreto_cifrato FROM caselle WHERE id = ?", (cid,)).fetchone()[0]
    assert "cache-finta" in servizi.cifrario.decifra(cifrato)
    assert "cache-finta" not in cifrato
    assert [m["oggetto"] for m in client.get("/api/mail", headers=auth).json()["mail"]] == ["Offerta impianto?"]


def test_outlook_codice_scaduto(client, auth, outlook, servizi):
    servizi.fabbrica.accesso_factory = lambda cache: AccessoFinto(cache, riesce=False)
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    c = client.get(f"/api/caselle/{cid}", headers=auth).json()
    assert c["stato"] == "errore" and "scaduto" in c["errore"]


def test_outlook_invio_e_spostamento(client, auth, outlook, servizi):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    outlook.mail("o2", oggetto="Preventivo?", quando="2026-10-10T08:00:00Z")
    servizi.lavoro.controlla_casella(cid)
    from conftest import scrivi_tutto, smista_tutto

    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    r = invia(client, auth, m['bozza']['id'])
    assert r.status_code == 200
    assert outlook.spedite[0]["risponde_a"] == "o2"
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert "o2" in outlook.spostate
