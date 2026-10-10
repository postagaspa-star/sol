"""Revisione, punti 1, 11, 12, 14, 15, 16: la lettura della posta non si ferma e non resta appesa."""

from datetime import timedelta

import httpx
from fastapi.testclient import TestClient

from app import db
from conftest import ORA_INIZIALE, aggiungi_gmail, collega_finti, smista_tutto
from finti_server import messaggio

RAW_VELENO = (b"From: =?utf-8?q?Mario=0A?= <x@esterno.esempio.it>\r\nTo: studio@esempio.it\r\n"
              b"Subject: ciao\r\nMessage-ID: <v1@esterno.esempio.it>\r\n\r\nciao\r\n")


def _mail_della_casella(client, auth, casella_id):
    return client.get(f"/api/mail?limite=200&casella_id={casella_id}", headers=auth).json()["mail"]


# --- punto 1 ----------------------------------------------------------------------------------


def test_p1_a_capo_nel_nome_non_ferma_il_giro(client, auth, server_gmail, servizi, orologio):
    c = aggiungi_gmail(client, auth)
    f = client.post("/api/caselle", json={"tipo": "finta"}, headers=auth).json()  # creata DOPO
    server_gmail.aggiungi(messaggio(oggetto="Impianto fermo, urgente", id_messaggio="<buona@esempio.it>"),
                          quando=ORA_INIZIALE + timedelta(minutes=5))
    server_gmail.aggiungi(RAW_VELENO, quando=ORA_INIZIALE + timedelta(minutes=6))
    orologio.avanza(minutes=70)
    servizi.lavoro.giro_posta()  # non solleva più
    gmail = {m["oggetto"]: m for m in _mail_della_casella(client, auth, c["id"])}
    assert set(gmail) == {"Impianto fermo, urgente", "ciao"}
    assert gmail["ciao"]["mittente"] == {"nome": "Mario", "indirizzo": "x@esterno.esempio.it"}
    assert len(_mail_della_casella(client, auth, f["id"])) == 13  # le 3 nuove della finta arrivano
    stato = client.get(f"/api/caselle/{c['id']}", headers=auth).json()
    assert stato["stato"] == "collegata" and stato["errore"] is None


def test_p1_mail_illeggibile_si_salta_e_non_si_riprova(client, auth, server_gmail, servizi, orologio, app,
                                                       monkeypatch):
    import app.caselle.gmail as modulo_gmail

    c = aggiungi_gmail(client, auth)
    for i in range(3):
        server_gmail.aggiungi(messaggio(oggetto=f"Mail {i}", id_messaggio=f"<x{i}@esempio.it>"),
                              quando=ORA_INIZIALE + timedelta(minutes=1 + i))
    cattiva = server_gmail.messaggi[1]["msgid"]
    vera, chiamate = modulo_gmail.componi_mail, []

    def esplode(msgid, *a, **kw):
        chiamate.append(msgid)
        if msgid == cattiva:
            raise ValueError("struttura impossibile")
        return vera(msgid, *a, **kw)

    monkeypatch.setattr(modulo_gmail, "componi_mail", esplode)
    orologio.avanza(minutes=10)
    servizi.lavoro.giro_posta()
    assert sorted(m["oggetto"] for m in _mail_della_casella(client, auth, c["id"])) == ["Mail 0", "Mail 2"]
    with db.aperta(app.state.settings.db_path) as conn:
        illeggibili = conn.execute("SELECT id_esterno, motivo FROM mail_illeggibili").fetchall()
    assert [tuple(r) for r in illeggibili] == [(cattiva, "ValueError")]
    chiamate.clear()
    servizi.lavoro.giro_posta()
    assert chiamate == []  # non si riprova a ogni giro
    assert client.get(f"/api/caselle/{c['id']}", headers=auth).json()["stato"] == "collegata"


def test_p1_casella_che_esplode_non_ferma_le_altre(client, auth, servizi, orologio):
    f1 = client.post("/api/caselle", json={"tipo": "finta"}, headers=auth).json()
    f2 = client.post("/api/caselle", json={"tipo": "finta"}, headers=auth).json()
    vera = servizi.fabbrica.per

    def per(casella):
        if casella["id"] == f1["id"]:
            raise RuntimeError("guasto imprevisto 0xdead")
        return vera(casella)

    servizi.fabbrica.per = per
    orologio.avanza(minutes=8)
    servizi.lavoro.giro_posta()
    stato = client.get(f"/api/caselle/{f1['id']}", headers=auth).json()
    assert stato["stato"] == "errore"
    assert "problema" in stato["errore"] and "0xdead" not in stato["errore"] and "Runtime" not in stato["errore"]
    assert len(_mail_della_casella(client, auth, f2["id"])) == 11


# --- punto 11 ---------------------------------------------------------------------------------


def test_p11_outlook_secondo_controllo_senza_riscaricare(client, auth, server_graph, servizi):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    server_graph.pagina = 50
    for i in range(300):
        server_graph.mail(f"o{i}", oggetto=f"Mail {i}", quando="2026-10-10T08:00:00Z", corpo="x" * 2000)
    assert servizi.lavoro.controlla_casella(cid) == 300
    server_graph.richieste.clear()
    assert servizi.lavoro.controlla_casella(cid) == 0
    elenchi = [p for _, p in server_graph.richieste if "/mailFolders/inbox/messages" in p]
    corpi = [p for m, p in server_graph.richieste if m == "GET" and p.startswith("/me/messages/")]
    assert elenchi == ["/me/mailFolders/inbox/messages/delta"]  # una richiesta, solo le novità
    assert corpi == []


def test_p11_gmail_si_salva_una_mail_alla_volta(client, auth, server_gmail, servizi, orologio, app):
    """Il giro salva ogni mail appena letta: se cade a metà, quelle lette restano."""
    c = aggiungi_gmail(client, auth)
    for i in range(3):
        server_gmail.aggiungi(messaggio(oggetto=f"Mail {i}", id_messaggio=f"<y{i}@esempio.it>"),
                              quando=ORA_INIZIALE + timedelta(minutes=1 + i))
    import finti_server

    originale = finti_server._SessioneImap.uid
    conteggio = {"fetch": 0}

    def cade_alla_terza(self, comando, *a):
        if comando.upper() == "FETCH" and "BODYSTRUCTURE" in str(a[1]):
            conteggio["fetch"] += 1
            if conteggio["fetch"] == 3:
                raise OSError("rete caduta")
        return originale(self, comando, *a)

    finti_server._SessioneImap.uid = cade_alla_terza
    try:
        orologio.avanza(minutes=10)
        servizi.lavoro.controlla_casella(c["id"])
    finally:
        finti_server._SessioneImap.uid = originale
    assert sorted(m["oggetto"] for m in _mail_della_casella(client, auth, c["id"])) == ["Mail 0", "Mail 1"]
    stato = client.get(f"/api/caselle/{c['id']}", headers=auth).json()
    assert stato["stato"] == "collegata" and "Gmail" in stato["errore"]  # avviso, si riprova da soli
    servizi.lavoro.controlla_casella(c["id"])
    assert len(_mail_della_casella(client, auth, c["id"])) == 3


# --- punto 12 ---------------------------------------------------------------------------------


def test_p12_timeout_su_decisore_e_scrittori():
    from app.config import carica_settings
    from app.decisore import ClientDecisore
    from app.scrittore import Scrittore

    tempi = {}

    def gestore(request: httpx.Request):
        percorso = "/chat/completions" if request.url.path.endswith("/chat/completions") else request.url.path
        tempi[percorso] = request.extensions["timeout"]["read"]
        if percorso == "/decidi":
            return httpx.Response(200, json={"risposte": {"x": {"scelta": "a", "probabilita": {"a": 1.0}}}})
        if percorso == "/genera":
            return httpx.Response(200, json={"testo": "ok"})
        if percorso == "/chat/completions":
            return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})
        return httpx.Response(200, json={"stato": "pronto"})

    d = ClientDecisore("http://sol-decisore:8300", transport=httpx.MockTransport(gestore))
    d.salute(massima_eta=0)
    d.decidi("x", [{"id": "x", "domanda": "?", "opzioni": [{"id": "a"}, {"id": "b"}]}])
    d.genera([{"ruolo": "utente", "testo": "x"}], 10)
    s = Scrittore(carica_settings().__class__(**{**carica_settings().__dict__, "groq_key": "k"}), d,
                  httpx.MockTransport(gestore), dormi=lambda x: None)
    s.scrivi([{"ruolo": "utente", "testo": "x"}], ["groq"], 10)
    # Contratto 15.14: almeno 3 minuti per /decidi e 5 per /genera; nessuna chiamata senza tempo massimo.
    assert tempi["/decidi"] >= 180 and tempi["/genera"] >= 300
    assert tempi["/salute"] and tempi["/chat/completions"]


def test_p12_una_sola_app_msal_per_casella(client, auth, server_graph, servizi, app):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    prima = len(server_graph.accessi)
    with db.aperta(app.state.settings.db_path) as conn:
        c = conn.execute("SELECT * FROM caselle WHERE id = ?", (cid,)).fetchone()
    for _ in range(3):
        servizi.fabbrica.per(c)
        servizi.lavoro.controlla_casella(cid)
    assert len(server_graph.accessi) == prima


# --- punto 14 ---------------------------------------------------------------------------------


def _riavvia(ambiente, decisore):
    from app.main import create_app

    app2 = create_app()
    collega_finti(app2, decisore)
    return app2


def test_p14_dopo_un_riavvio_niente_resta_in_corso(client, auth, app, ambiente, decisore):
    rid = client.post("/api/riassunti", json={"richiesta": "le mail di Rossi"}, headers=auth).json()["id"]
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE riassunti SET stato = 'in_corso' WHERE id = ?", (rid,))
        conn.execute(
            "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il, in_collegamento_dal)"
            " VALUES ('outlook', '', 'Studio', 'in_collegamento', ?, ?, ?)",
            (db.clock.adesso(), db.clock.adesso(), db.clock.adesso()),
        )
    with TestClient(_riavvia(ambiente, decisore)) as c2:
        assert c2.get(f"/api/riassunti/{rid}", headers=auth).json()["stato"] == "in_coda"
        casella = [x for x in c2.get("/api/caselle", headers=auth).json() if x["tipo"] == "outlook"][0]
        assert casella["stato"] == "errore" and "Ricollega" in casella["errore"]


def test_p14_collegamento_scaduto_dopo_15_minuti(client, auth, server_graph, servizi, orologio):
    rimandati = []
    servizi.fabbrica.in_sottofondo = lambda f, *a: rimandati.append((f, a))  # l'utente non fa mai l'accesso
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    orologio.avanza(minutes=10)
    servizi.lavoro.giro_posta()
    assert client.get(f"/api/caselle/{cid}", headers=auth).json()["stato"] == "in_collegamento"
    orologio.avanza(minutes=6)
    servizi.lavoro.giro_posta()
    c = client.get(f"/api/caselle/{cid}", headers=auth).json()
    assert c["stato"] == "errore" and "Ricollega" in c["errore"]


def test_p14_errore_inatteso_nello_scrittore_porta_a_uno_stato_finale(client, auth, finta, servizi, monkeypatch):
    import sqlite3

    from app import ricerca

    rid = client.post("/api/riassunti", json={"richiesta": "le mail di Rossi"}, headers=auth).json()["id"]

    def bloccato(*a):
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(ricerca, "seleziona", bloccato)
    assert servizi.lavoro.passo_scrittore() == "fatto"  # nessuna eccezione verso il giro
    r = client.get(f"/api/riassunti/{rid}", headers=auth).json()
    assert r["stato"] == "errore" and r["errore"] and "locked" not in r["errore"]


# --- punto 15 ---------------------------------------------------------------------------------


def test_p15_risposta_automatica_non_conta_come_risposta(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    server_gmail.aggiungi(messaggio(da="studio@esempio.it", a="Neri <neri@esempio.it>", oggetto="Offerta compressore",
                                    testo="Mi conferma se possiamo procedere?", id_messaggio="<inv1@esempio.it>"),
                          etichette=("\\Sent",), quando=ORA_INIZIALE + timedelta(minutes=5), filo="55")
    server_gmail.aggiungi(messaggio(da="Neri <neri@esempio.it>", oggetto="Risposta automatica: Offerta compressore",
                                    testo="Sono in ferie fino al 25 ottobre.", id_messaggio="<auto1@esempio.it>",
                                    intestazioni={"Auto-Submitted": "auto-replied", "In-Reply-To": "<inv1@esempio.it>"}),
                          quando=ORA_INIZIALE + timedelta(minutes=6), filo="55")
    orologio.avanza(minutes=10)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    automatica = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert automatica["da_rispondere"] is False and automatica["bozza"] is None
    orologio.avanza(days=8)
    aspetti = client.get("/api/indietro", headers=auth).json()["aspetti_tu"]
    assert [a["inviata"]["oggetto"] for a in aspetti] == ["Offerta compressore"]


# --- punto 16 ---------------------------------------------------------------------------------


def test_p16_alzare_conserva_giorni_non_fa_rientrare_le_mail_pulite(client, auth, finta, servizi, app):
    smista_tutto(servizi)
    cursore = client.get("/api/novita", headers=auth).json()["cursore"]
    client.patch("/api/impostazioni", json={"conserva_giorni": 7}, headers=auth)
    servizi.lavoro._ultima_pulizia = None
    servizi.lavoro.pulizia_se_serve()
    client.patch("/api/impostazioni", json={"conserva_giorni": 180}, headers=auth)
    servizi.lavoro.giro_posta()
    oggetti = [m["oggetto"] for m in client.get("/api/mail?limite=200", headers=auth).json()["mail"]]
    assert "Offerta capannone Bassano" not in oggetti  # 8 giorni fa: tolta e non rientrata
    smista_tutto(servizi)
    assert client.get(f"/api/novita?dal={cursore}", headers=auth).json()["da_rispondere_nuove"] == 0
