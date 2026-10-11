"""Contratto v1.4, sezione 18: pagina "Oggi", raccolte, nome."""

from datetime import timedelta

from app import clock, db
from conftest import invia, mail_per_oggetto, scrivi_tutto, smista_tutto

# I campi di ogni voce di da_fare (18.1): niente frasi pronte, solo dati.
CAMPI = {
    "truffa": {"tipo", "mail_id", "mittente", "oggetto", "riassunto", "colore", "iniziali"},
    "risposta": {"tipo", "mail_id", "bozza_id", "mittente", "oggetto", "riassunto", "urgenza", "colore", "iniziali"},
    "smista": {"tipo", "conteggio"},
    "rispondi": {"tipo", "mail_id", "mittente", "oggetto", "riassunto", "colore", "iniziali"},
    "sollecito": {"tipo", "inviata_id", "bozza_id", "a", "nome", "oggetto", "giorni", "iniziali"},
    "scadenza": {"tipo", "scadenza_id", "descrizione", "quando", "mail_id"},
}


def _oggi(client, auth):
    r = client.get("/api/oggi", headers=auth)
    assert r.status_code == 200, r.text
    return r.json()


def _conn(app):
    return db.aperta(app.state.settings.db_path)


def _gruppi(client, auth):
    return {g["nome"]: g for g in client.get("/api/gruppi", headers=auth).json()}


def _pronta(app):
    smista_tutto(app.state.servizi)
    scrivi_tutto(app.state.servizi)


def _totale_giusto(dati):
    voci = sum(v["conteggio"] if v["tipo"] == "smista" else 1 for v in dati["da_fare"])
    return dati["totale_oggi"] == dati["fatte_oggi"] + voci


def test_oggi_vuota_e_protetta(client, auth):
    assert client.get("/api/oggi").status_code == 401
    dati = _oggi(client, auth)
    assert set(dati) == {"data", "nome", "mail_oggi", "riassunto", "scadenza_prossima", "da_fare", "fatte_oggi",
                         "totale_oggi"}
    assert dati["data"] == "2026-10-10" and dati["nome"] == "" and dati["mail_oggi"] == 0
    assert set(dati["riassunto"]) == {"testo", "stato", "aggiornato_il", "mail_citate"}
    assert dati["scadenza_prossima"] is None and dati["da_fare"] == []
    assert dati["fatte_oggi"] == 0 and dati["totale_oggi"] == 0


def test_oggi_campi_e_ordine(client, auth, finta, app):
    _pronta(app)
    listino = mail_per_oggetto(client, auth, "Listino 2027")
    fattura = mail_per_oggetto(client, auth, "Fattura n. 112")
    with _conn(app) as conn:
        conn.execute("UPDATE mail SET smistamento = 'da_controllare' WHERE id = ?", (listino["id"],))
        conn.execute("UPDATE mail SET da_rispondere = 1, risposta_data = 0 WHERE id = ?", (fattura["id"],))
        conn.execute("DELETE FROM bozze WHERE mail_id = ?", (fattura["id"],))
    dati = _oggi(client, auth)
    tipi = [v["tipo"] for v in dati["da_fare"]]
    # Possibile truffa, risposta pronta urgente, smista, altra risposta pronta, rispondi, sollecito, scadenze:
    # massimo 8 voci.
    assert tipi == ["truffa", "risposta", "smista", "risposta", "rispondi", "sollecito", "scadenza", "scadenza"]
    for voce in dati["da_fare"]:
        assert set(voce) == CAMPI[voce["tipo"]], voce
    truffa, urgente, smista, normale, rispondi, sollecito = dati["da_fare"][:6]
    assert truffa["oggetto"] == "URGENTE: inoltro fatture" and truffa["iniziali"] == "A"
    assert urgente["oggetto"] == "Sopralluogo urgente quadro elettrico" and urgente["urgenza"] == "alta"
    assert urgente["iniziali"] == "LB" and urgente["colore"] == "blu" and urgente["mittente"] == "Laura Bianchi"
    assert urgente["bozza_id"] == client.get(f"/api/mail/{urgente['mail_id']}", headers=auth).json()["bozza"]["id"]
    # La mail sospetta da controllare non conta in "smista": ha già la sua voce.
    assert smista == {"tipo": "smista", "conteggio": 1}
    assert normale["urgenza"] != "alta" and normale["oggetto"] == "Offerta capannone Bassano"
    assert rispondi["mail_id"] == fattura["id"] and rispondi["iniziali"] == "SC"  # Studio Verdi Commercialisti: prima e ultima parola
    assert sollecito["a"] == "neri@esempio.it" and sollecito["giorni"] == 9 and sollecito["bozza_id"] is None
    assert dati["mail_oggi"] == 4 and _totale_giusto(dati)
    assert dati["scadenza_prossima"]["quando"] >= dati["data"]


def test_oggi_conta_le_azioni_chiuse_oggi(client, auth, finta, app, orologio):
    _pronta(app)
    listino = mail_per_oggetto(client, auth, "Listino 2027")
    webinar = mail_per_oggetto(client, auth, "Webinar gratuito")
    with _conn(app) as conn:
        conn.execute("UPDATE mail SET smistamento = 'da_controllare' WHERE id IN (?, ?)", (listino["id"], webinar["id"]))
    assert _oggi(client, auth)["fatte_oggi"] == 0

    # Possibile truffa chiarita: sparisce da da_fare e conta come fatta.
    truffa = _oggi(client, auth)["da_fare"][0]
    assert truffa["tipo"] == "truffa"
    client.post(f"/api/mail/{truffa['mail_id']}/sospetta", json={"sospetta": False}, headers=auth)
    dati = _oggi(client, auth)
    assert dati["fatte_oggi"] == 1 and "truffa" not in [v["tipo"] for v in dati["da_fare"]]

    # Mail da controllare sistemate: una confermata, una spostata.
    client.post(f"/api/mail/{listino['id']}/conferma", headers=auth)
    gruppi = _gruppi(client, auth)
    client.post(f"/api/mail/{webinar['id']}/sposta", json={"gruppo_id": gruppi["Newsletter"]["id"]}, headers=auth)
    dati = _oggi(client, auth)
    assert dati["fatte_oggi"] == 3 and "smista" not in [v["tipo"] for v in dati["da_fare"]]

    # Risposta inviata.
    risposta = next(v for v in dati["da_fare"] if v["tipo"] == "risposta")
    assert invia(client, auth, risposta["bozza_id"]).status_code == 200
    dati = _oggi(client, auth)
    assert dati["fatte_oggi"] == 4 and risposta["bozza_id"] not in [v.get("bozza_id") for v in dati["da_fare"]]

    # Scadenza fatta; se torna aperta non conta più.
    scadenza = next(v for v in dati["da_fare"] if v["tipo"] == "scadenza")
    client.patch(f"/api/scadenze/{scadenza['scadenza_id']}", json={"stato": "fatta"}, headers=auth)
    assert _oggi(client, auth)["fatte_oggi"] == 5
    client.patch(f"/api/scadenze/{scadenza['scadenza_id']}", json={"stato": "aperta"}, headers=auth)
    assert _oggi(client, auth)["fatte_oggi"] == 4
    client.patch(f"/api/scadenze/{scadenza['scadenza_id']}", json={"stato": "fatta"}, headers=auth)
    dati = _oggi(client, auth)
    assert dati["fatte_oggi"] == 5 and _totale_giusto(dati)

    # Correggere una mail già smistata non è "sistemare una da controllare".
    clienti = gruppi["Clienti"]["id"]
    client.post(f"/api/mail/{listino['id']}/sposta", json={"gruppo_id": clienti}, headers=auth)
    assert _oggi(client, auth)["fatte_oggi"] == 5

    # Il giorno dopo (ora italiana) si riparte da zero.
    orologio.avanza(hours=14)  # 22:00 UTC = mezzanotte a Roma
    assert _oggi(client, auth)["fatte_oggi"] == 0


def test_truffa_confermata_esce_da_da_fare(client, auth, finta, app):
    _pronta(app)
    truffa = _oggi(client, auth)["da_fare"][0]
    client.post(f"/api/mail/{truffa['mail_id']}/sospetta", json={"sospetta": True}, headers=auth)
    dati = _oggi(client, auth)
    # L'utente ha deciso: resta segnata, ma non c'è più niente da chiarire.
    assert dati["fatte_oggi"] == 1 and "truffa" not in [v["tipo"] for v in dati["da_fare"]]
    assert client.get(f"/api/mail/{truffa['mail_id']}", headers=auth).json()["sospetta"] is True
    # Una mail segnata dall'utente senza che Sol la sospettasse non conta come "chiarita".
    listino = mail_per_oggetto(client, auth, "Listino 2027")
    client.post(f"/api/mail/{listino['id']}/sospetta", json={"sospetta": True}, headers=auth)
    assert _oggi(client, auth)["fatte_oggi"] == 1


def test_oggi_sollecito_con_nome_e_bozza(client, auth, finta, app):
    _pronta(app)
    newsletter = mail_per_oggetto(client, auth, "Novità normative")
    with _conn(app) as conn:  # Anna Neri ci ha scritto: il suo nome si prende da lì
        conn.execute("UPDATE mail SET mittente_nome = 'Anna Neri', mittente_indirizzo = 'NERI@esempio.it' WHERE id = ?",
                     (newsletter["id"],))
    voce = next(v for v in _oggi(client, auth)["da_fare"] if v["tipo"] == "sollecito")
    assert voce["nome"] == "Anna Neri" and voce["iniziali"] == "AN" and voce["bozza_id"] is None

    sollecito = client.post(f"/api/indietro/{voce['inviata_id']}/sollecito", headers=auth).json()
    # In preparazione non è ancora "già preparata".
    assert next(v for v in _oggi(client, auth)["da_fare"] if v["tipo"] == "sollecito")["bozza_id"] is None
    scrivi_tutto(app.state.servizi)
    voce = next(v for v in _oggi(client, auth)["da_fare"] if v["tipo"] == "sollecito")
    assert voce["bozza_id"] == sollecito["id"]

    fatte = _oggi(client, auth)["fatte_oggi"]
    assert invia(client, auth, sollecito["id"]).status_code == 200
    dati = _oggi(client, auth)
    assert dati["fatte_oggi"] == fatte + 1 and "sollecito" not in [v["tipo"] for v in dati["da_fare"]]


def test_oggi_scadenze_e_prossima(client, auth, finta, app):
    oggi = clock.oggi()
    trappola = mail_per_oggetto(client, auth, "inoltro fatture")
    with _conn(app) as conn:
        conn.execute("UPDATE mail SET sospetta = 1 WHERE id = ?", (trappola["id"],))
        for giorni, stato, mail_id, descrizione in ((-1, "aperta", None, "Passata"), (1, "aperta", trappola["id"], "Truffa"),
                                                    (2, "fatta", None, "Fatta"), (3, "aperta", None, "Offerta a Rossi"),
                                                    (8, "aperta", None, "Lontana")):
            conn.execute(
                "INSERT INTO scadenze (mail_id, descrizione, quando, stato, creata_il) VALUES (?, ?, ?, ?, ?)",
                (mail_id, descrizione, (oggi + timedelta(days=giorni)).isoformat(), stato, clock.adesso()),
            )
    dati = _oggi(client, auth)
    tre_giorni = (oggi + timedelta(days=3)).isoformat()
    assert dati["scadenza_prossima"] == {"id": 4, "descrizione": "Offerta a Rossi", "quando": tre_giorni, "mail_id": None}
    # Entro 7 giorni: quella fra 8 no, come per conteggi.scadenze_prossime.
    assert [v for v in dati["da_fare"] if v["tipo"] == "scadenza"] == [
        {"tipo": "scadenza", "scadenza_id": 4, "descrizione": "Offerta a Rossi", "quando": tre_giorni, "mail_id": None}
    ]
    with _conn(app) as conn:
        conn.execute("UPDATE scadenze SET stato = 'fatta' WHERE id = 4")
    assert _oggi(client, auth)["scadenza_prossima"]["descrizione"] == "Lontana"


def test_mail_oggi_nel_giorno_italiano(client, auth, finta, app):
    vecchie = [mail_per_oggetto(client, auth, o)["id"] for o in ("Fattura n. 112", "Re: Sopralluogo impianto")]
    with _conn(app) as conn:
        # 00:30 a Roma di oggi sì, 23:30 a Roma di ieri no.
        conn.execute("UPDATE mail SET ricevuta_il = '2026-10-09T22:30:00+00:00' WHERE id = ?", (vecchie[0],))
        conn.execute("UPDATE mail SET ricevuta_il = '2026-10-09T21:30:00+00:00' WHERE id = ?", (vecchie[1],))
    assert _oggi(client, auth)["mail_oggi"] == 5


def test_iniziali():
    from app.routes.oggi import iniziali

    assert iniziali("Mario Rossi", "rossi@esempio.it") == "MR"
    assert iniziali("Ferramenta Neri", None) == "FN"
    assert iniziali("Studio Tecnico Bianchi Srl", None) == "SS"
    assert iniziali("Rossi, Mario", None) == "RM"
    assert iniziali("Élodie Bianchi", None) == "ÉB"
    assert iniziali("Amministrazione", None) == "A"
    assert iniziali("", "anna.neri@esempio.it") == "AN"
    assert iniziali(None, "neri@esempio.it") == "N"
    assert iniziali("neri@esempio.it", None) == "N"
    assert iniziali("\"Bianchi\" - Ufficio", None) == "BU"
    assert iniziali(None, None) == ""


def test_raccolte_iniziali_e_modifiche(client, auth):
    gruppi = _gruppi(client, auth)
    assert {n: g["raccolta"] for n, g in gruppi.items()} == {
        "Clienti": False, "Fornitori": False, "Amministrazione e fatture": True, "PEC ed enti": False,
        "Newsletter": True, "Altro": False}
    r = client.post("/api/gruppi", json={"nome": "Avvisi", "raccolta": True}, headers=auth)
    assert r.status_code == 201 and r.json()["raccolta"] is True
    assert client.post("/api/gruppi", json={"nome": "Perizie"}, headers=auth).json()["raccolta"] is False
    r = client.patch(f"/api/gruppi/{gruppi['Newsletter']['id']}", json={"raccolta": False}, headers=auth)
    assert r.status_code == 200 and r.json()["raccolta"] is False
    # Cambiare altro non tocca la raccolta.
    r = client.patch(f"/api/gruppi/{gruppi['Amministrazione e fatture']['id']}", json={"colore": "rosa"}, headers=auth)
    assert r.json()["raccolta"] is True
    for metodo, url, corpo in (("patch", f"/api/gruppi/{gruppi['Clienti']['id']}", {"raccolta": "sì"}),
                               ("post", "/api/gruppi", {"nome": "Altri", "raccolta": 1})):
        r = getattr(client, metodo)(url, json=corpo, headers=auth)
        assert r.status_code == 422 and r.json()["campo"] == "raccolta"
        assert r.json()["errore"] == "Va indicato se il gruppo è una raccolta (sì o no)."


def test_raccolte_anche_nei_database_esistenti(tmp_path, monkeypatch):
    percorso = str(tmp_path / "v13.db")
    monkeypatch.setattr(db, "MIGRAZIONI", db.MIGRAZIONI[:4])
    db.init_db(percorso)
    with db.aperta(percorso) as conn:
        conn.execute("INSERT INTO gruppi (nome, ordine, descrizione) VALUES ('Perizie', 7, 'Perizie')")
        conn.execute("UPDATE gruppi SET nome = 'Fatture' WHERE chiave = 'amministrazione'")
    monkeypatch.undo()
    db.init_db(percorso)
    with db.aperta(percorso) as conn:
        raccolte = {r["nome"]: r["raccolta"] for r in conn.execute("SELECT nome, raccolta FROM gruppi")}
        assert db.impostazioni(conn)["nome"] == ""
    # Anche se l'utente aveva rinominato il gruppo: conta quale gruppo è, non come si chiama.
    assert raccolte == {"Clienti": 0, "Fornitori": 0, "Fatture": 1, "PEC ed enti": 0, "Newsletter": 1, "Altro": 0,
                        "Perizie": 0}


def test_conteggi_ultima_mail_del_gruppo(client, auth, finta, app):
    smista_tutto(app.state.servizi)
    vuoto = client.post("/api/gruppi", json={"nome": "Perizie"}, headers=auth).json()
    gruppi = client.get("/api/conteggi", headers=auth).json()["gruppi"]
    assert gruppi[str(vuoto["id"])]["ultima"] is None
    tutte = client.get("/api/mail?limite=200", headers=auth).json()["mail"]  # dalla più recente
    for gid, valori in gruppi.items():
        sue = [m for m in tutte if m["gruppo_id"] == int(gid)]
        if not sue:
            assert valori["ultima"] is None
            continue
        m = sue[0]
        assert valori["ultima"] == {"mittente": m["mittente"]["nome"] or m["mittente"]["indirizzo"],
                                    "oggetto": m["oggetto"], "ricevuta_il": m["ricevuta_il"]}
    newsletter = _gruppi(client, auth)["Newsletter"]["id"]
    ultima = gruppi[str(newsletter)]["ultima"]
    assert ultima == {"mittente": "Formazione Tecnica", "oggetto": "Webinar gratuito: la nuova norma sui quadri",
                      "ricevuta_il": "2026-10-10T09:40:00+02:00"}


def test_nome_nelle_impostazioni(client, auth):
    assert client.get("/api/impostazioni", headers=auth).json()["nome"] == ""
    r = client.patch("/api/impostazioni", json={"nome": "  Manuel \n"}, headers=auth)
    assert r.status_code == 200 and r.json()["nome"] == "Manuel"
    assert _oggi(client, auth)["nome"] == "Manuel"
    assert client.patch("/api/impostazioni", json={"nome": "x" * 40}, headers=auth).status_code == 200
    r = client.patch("/api/impostazioni", json={"nome": "x" * 41}, headers=auth)
    assert r.status_code == 422 and r.json() == {"errore": "Il nome può avere al massimo 40 caratteri.",
                                                 "codice": "non_valido", "campo": "nome"}
    # Vuoto si può: le app salutano solo con "Buongiorno".
    r = client.patch("/api/impostazioni", json={"nome": ""}, headers=auth)
    assert r.status_code == 200 and r.json()["nome"] == ""
    r = client.patch("/api/impostazioni", json={"nome": 5}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "nome"


def test_versione_2_0_0(client):
    dati = client.get("/api/versione").json()
    assert dati["server"] == "2.0.0"
    assert (dati["android"]["codice"], dati["android"]["nome"]) == (3, "2.0.0")
    assert (dati["pc"]["codice"], dati["pc"]["nome"]) == (3, "2.0.0")
    assert dati["nota"] == ("Sol 2.0: nuova pagina Oggi, posta raccolta per gruppi, risposta di Sol con tono in un tocco, "
                            "smistamento una mail alla volta, tutorial.")
    assert client.get("/api/salute").json()["versione"] == "2.0.0"
