
from app import db, smista
from conftest import mail_per_oggetto, scrivi_tutto, smista_tutto


def test_formato_per_il_decisore(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    stato = next(s for s, _ in decisore.chiamate if "quadro elettrico" in s)
    righe = stato.split("\n")
    assert righe[0] == "Mittente: Laura Bianchi <laura.bianchi@esempio.it>"
    assert righe[1] == "Oggetto: Sopralluogo urgente quadro elettrico"
    assert righe[2].startswith("Testo: Buongiorno,")


def test_pulizia_vecchie_mail(client, auth, finta, servizi, orologio, app):
    client.patch("/api/impostazioni", json={"conserva_giorni": 7}, headers=auth)
    servizi.lavoro._ultima_pulizia = None
    servizi.lavoro.pulizia_se_serve()
    oggetti = {m["oggetto"] for m in client.get("/api/mail?limite=200", headers=auth).json()["mail"]}
    assert "Offerta capannone Bassano" not in oggetti  # 8 giorni fa
    assert "Richiesta integrazione pratica SCIA n. 45/2026" in oggetti
    # E non si riprendono dalla casella al controllo dopo.
    servizi.lavoro.controlla_casella(finta["id"])
    oggetti = {m["oggetto"] for m in client.get("/api/mail?limite=200", headers=auth).json()["mail"]}
    assert "Offerta capannone Bassano" not in oggetti


def test_gruppo_cancellato_mentre_il_decisore_pensa(client, auth, finta, servizi, app):
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    risposte = {
        "gruppo": {"scelta": "Sparito", "probabilita": {"Sparito": 0.99}},
        "da_rispondere": {"scelta": "no", "probabilita": {"si": 0.1, "no": 0.9}},
        "urgenza": {"scelta": "normale", "probabilita": {"alta": 0, "normale": 1, "bassa": 0}},
    }
    with db.aperta(app.state.settings.db_path) as conn:
        smista.salva_valutazione(conn, m["id"], risposte, 0.85)
    assert mail_per_oggetto(client, auth, "quadro elettrico")["gruppo"] == "Altro"


def test_correzione_utente_durante_la_valutazione_resta(client, auth, finta, servizi, app):
    gruppi = {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": gruppi["Fornitori"]}, headers=auth)
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["gruppo"] == "Fornitori" and m["smistamento"] == "corretta"
    assert m["urgenza"] == "alta"  # le altre due domande sì


def test_casella_eliminata_durante_il_collegamento(client, auth, servizi):
    from finti_server import AccessoFinto

    servizi.settings = servizi.settings.__class__(**{**servizi.settings.__dict__, "ms_client_id": "x"})
    servizi.fabbrica.ms_client_id = "x"
    rimandati = []
    servizi.fabbrica.accesso_factory = lambda cache: AccessoFinto(cache)
    servizi.fabbrica.in_sottofondo = lambda f, *a: rimandati.append((f, a))
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    client.delete(f"/api/caselle/{cid}", headers=auth)
    for f, a in rimandati:
        f(*a)  # non deve far rinascere la casella né cadere
    assert client.get("/api/caselle", headers=auth).json() == []


def test_passi_vuoti_quando_non_c_e_niente(servizi):
    assert servizi.lavoro.passo_decisore() == "vuoto"
    assert servizi.lavoro.passo_scrittore() == "fatto"  # il primo riassunto di oggi ("nessuna mail")
    assert servizi.lavoro.passo_scrittore() == "vuoto"
    assert servizi.lavoro.giro_posta() == "vuoto"


def test_scrittore_giu_riassunti_di_una_riga_aspettano(client, auth, finta, servizi, decisore, orologio):
    smista_tutto(servizi)
    decisore.genera_giu = True
    assert servizi.lavoro.passo_scrittore() == "guasto"
    assert all(m["riassunto"] is None for m in client.get("/api/mail", headers=auth).json()["mail"])
    decisore.genera_giu = False
    orologio.avanza(minutes=2)  # la mail fallita si riprova dopo l'attesa
    scrivi_tutto(servizi)
    assert all(m["riassunto"] for m in client.get("/api/mail", headers=auth).json()["mail"])


def test_inviata_senza_risposta_valutata_una_volta(client, auth, finta, servizi, decisore, orologio):
    smista_tutto(servizi)
    prima = len([1 for _, d in decisore.chiamate if d == ["aspetta"]])
    orologio.avanza(minutes=30)
    servizi.lavoro.controlla_casella(finta["id"])
    smista_tutto(servizi)
    assert len([1 for _, d in decisore.chiamate if d == ["aspetta"]]) == prima
