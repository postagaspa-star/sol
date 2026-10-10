"""Revisione, punto 10 e contratto 15.3: una credenziale revocata si ripara con «Ricollega»
senza perdere niente; un problema passeggero di Microsoft non mette la casella in errore."""

from datetime import timedelta

from app import db
from app.caselle import ErroreCasella
from conftest import ORA_INIZIALE, aggiungi_gmail, scrivi_tutto, smista_tutto
from finti_server import messaggio


def _conta(app):
    with db.aperta(app.state.settings.db_path) as conn:
        return tuple(conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("mail", "bozze", "scadenze"))


def test_p10_gmail_ricollega_senza_perdere_niente(client, auth, server_gmail, servizi, orologio, app):
    c = aggiungi_gmail(client, auth)
    server_gmail.aggiungi(messaggio(oggetto="Offerta?", testo="Ci manda l'offerta entro il 20 ottobre?",
                                    id_messaggio="<p1@esempio.it>"), quando=ORA_INIZIALE + timedelta(minutes=3))
    orologio.avanza(minutes=10)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    prima = _conta(app)
    server_gmail.password = "nuovapasswordapp"  # Google revoca la password per le app
    server_gmail.aggiungi(messaggio(oggetto="Urgente: impianto fermo?", id_messaggio="<p2@esempio.it>"),
                          quando=orologio.corrente + timedelta(hours=1))
    orologio.avanza(hours=2)
    servizi.lavoro.giro_posta()
    stato = client.get(f"/api/caselle/{c['id']}", headers=auth).json()
    assert stato["stato"] == "errore" and "password" in stato["errore"]
    r = client.post(f"/api/caselle/{c['id']}/ricollega", json={"password_app": "sbagliata"}, headers=auth)
    assert r.status_code == 422
    r = client.post(f"/api/caselle/{c['id']}/ricollega", json={"password_app": "nuova pass word appx"}, headers=auth)
    assert r.status_code == 422  # spazi tolti ma password diversa
    r = client.post(f"/api/caselle/{c['id']}/ricollega", json={"password_app": "nuov apas swor dapp"}, headers=auth)
    assert r.status_code == 200 and r.json()["stato"] == "collegata" and r.json()["errore"] is None
    assert r.json()["attiva_dal"] == c["attiva_dal"]
    dopo = _conta(app)
    assert dopo[1:] == prima[1:] and dopo[0] == prima[0] + 1  # e la mail arrivata nel buco è entrata
    oggetti = [m["oggetto"] for m in client.get("/api/mail", headers=auth).json()["mail"]]
    assert "Urgente: impianto fermo?" in oggetti


def test_p10_ricollega_senza_password(client, auth, server_gmail):
    c = aggiungi_gmail(client, auth)
    r = client.post(f"/api/caselle/{c['id']}/ricollega", json={}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "password_app"


def test_p10_outlook_ricollega_con_un_nuovo_codice(client, auth, server_graph, servizi, app):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    server_graph.mail("o1", oggetto="Preventivo?", quando="2026-10-10T08:00:00Z")
    servizi.lavoro.controlla_casella(cid)
    attiva_dal = client.get(f"/api/caselle/{cid}", headers=auth).json()["attiva_dal"]
    accesso = server_graph.accessi[-1]
    accesso.errore_token = ErroreCasella("Microsoft non riconosce più l'accesso.", "credenziali")
    servizi.lavoro.giro_posta()
    assert client.get(f"/api/caselle/{cid}", headers=auth).json()["stato"] == "errore"
    server_graph.mail("o2", oggetto="Arrivata nel buco", quando="2026-10-10T08:10:00Z")
    r = client.post(f"/api/caselle/{cid}/ricollega", json={}, headers=auth)
    assert r.status_code == 202
    assert r.json()["codice_utente"] == "ABCD-EFGH" and r.json()["url_verifica"] == "https://microsoft.com/devicelogin"
    c = client.get(f"/api/caselle/{cid}", headers=auth).json()
    assert c["stato"] == "collegata" and c["attiva_dal"] == attiva_dal
    oggetti = sorted(m["oggetto"] for m in client.get("/api/mail", headers=auth).json()["mail"])
    assert oggetti == ["Arrivata nel buco", "Preventivo?"]


def test_p10_outlook_ricollega_con_un_altro_account(client, auth, server_graph, servizi, app):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE caselle SET indirizzo = 'ufficio@esempio.it', stato = 'errore' WHERE id = ?", (cid,))
    client.post(f"/api/caselle/{cid}/ricollega", json={}, headers=auth)
    c = client.get(f"/api/caselle/{cid}", headers=auth).json()
    assert c["stato"] == "errore" and "studio@esempio.it" in c["errore"] and "ufficio@esempio.it" in c["errore"]


def test_p10_microsoft_lento_non_mette_in_errore(client, auth, server_graph, servizi):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    server_graph.accessi[-1].errore_token = ErroreCasella("Microsoft non risponde adesso.", "rete")
    servizi.lavoro.giro_posta()
    c = client.get(f"/api/caselle/{cid}", headers=auth).json()
    assert c["stato"] == "collegata" and "non risponde" in c["errore"]  # avviso, si riprova da soli
    server_graph.accessi[-1].errore_token = None
    servizi.lavoro.giro_posta()
    assert client.get(f"/api/caselle/{cid}", headers=auth).json()["errore"] is None


def test_p10_finta_ricollega(client, auth, finta, app):
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE caselle SET stato = 'errore', errore = 'x', errore_tipo = 'credenziali'")
    r = client.post(f"/api/caselle/{finta['id']}/ricollega", json={}, headers=auth)
    assert r.status_code == 200 and r.json()["stato"] == "collegata"
