import re

from app import abbina, db


def _codice(app):
    with db.aperta(app.state.settings.db_path) as conn:
        codice, _ = abbina.nuovo_codice(conn)
    return codice


def test_comando_stampa_un_codice(app, capsys):
    abbina.main()
    uscita = capsys.readouterr().out
    assert re.search(r"Codice di abbinamento: \d{8}\b", uscita)
    assert "15 minuti" in uscita


def test_abbina_e_usa_il_token(client, app):
    codice = _codice(app)
    r = client.post("/api/abbina", json={"codice": codice, "nome": "Telefono di Papà", "tipo": "android"})
    assert r.status_code == 200, r.text
    dati = r.json()
    assert len(dati["token"]) == 64 and dati["dispositivo_id"] >= 1
    headers = {"Authorization": f"Bearer {dati['token']}"}
    elenco = client.get("/api/dispositivi", headers=headers).json()
    assert elenco[0]["nome"] == "Telefono di Papà" and elenco[0]["tipo"] == "android"
    assert elenco[0]["abbinato_il"].endswith("+02:00")
    assert elenco[0]["ultimo_accesso"] is not None


def test_nel_database_solo_hash(client, app):
    codice = _codice(app)
    token = client.post("/api/abbina", json={"codice": codice, "nome": "PC", "tipo": "pc"}).json()["token"]
    with db.aperta(app.state.settings.db_path) as conn:
        contenuto = "\n".join(str(tuple(r)) for t in ("dispositivi", "codici_abbinamento")
                              for r in conn.execute(f"SELECT * FROM {t}"))
    assert token not in contenuto and codice not in contenuto
    assert db.hash_segreto(token) in contenuto


def test_codice_monouso(client, app):
    codice = _codice(app)
    assert client.post("/api/abbina", json={"codice": codice, "nome": "A", "tipo": "pc"}).status_code == 200
    r = client.post("/api/abbina", json={"codice": codice, "nome": "B", "tipo": "pc"})
    assert r.status_code == 401
    assert r.json()["codice"] == "non_autorizzato"


def test_codice_scaduto(client, app, orologio):
    codice = _codice(app)
    orologio.avanza(minutes=16)
    assert client.post("/api/abbina", json={"codice": codice, "nome": "A", "tipo": "pc"}).status_code == 401


def test_nuovo_codice_annulla_il_vecchio(client, app):
    vecchio = _codice(app)
    nuovo = _codice(app)
    assert client.post("/api/abbina", json={"codice": vecchio, "nome": "A", "tipo": "pc"}).status_code == 401
    assert client.post("/api/abbina", json={"codice": nuovo, "nome": "A", "tipo": "pc"}).status_code == 200


def test_dieci_sbagli_bloccano_e_annullano_i_codici(client, app, orologio):
    codice = _codice(app)
    for _ in range(9):
        assert client.post("/api/abbina", json={"codice": "00000000", "nome": "X", "tipo": "pc"}).status_code == 401
    assert client.post("/api/abbina", json={"codice": "00000001", "nome": "X", "tipo": "pc"}).status_code == 401
    r = client.post("/api/abbina", json={"codice": codice, "nome": "X", "tipo": "pc"})
    assert r.status_code == 429
    assert r.json()["codice"] == "troppi_tentativi"
    orologio.avanza(minutes=16)
    # Il codice che era aperto è stato annullato: ne serve uno nuovo.
    assert client.post("/api/abbina", json={"codice": codice, "nome": "X", "tipo": "pc"}).status_code == 401
    nuovo = _codice(app)
    assert client.post("/api/abbina", json={"codice": nuovo, "nome": "X", "tipo": "pc"}).status_code == 200


def test_limite_uguale_per_tutti_gli_indirizzi(app):
    """Dietro Tailscale tutte le richieste hanno lo stesso indirizzo: il limite è globale."""
    from app import clock

    codice = _codice(app)
    with db.aperta(app.state.settings.db_path) as conn:
        for n in range(10):
            assert abbina.abbina(conn, "00000000", "X", "pc", clock.now(), ip=f"10.0.0.{n}") is None
        assert abbina.abbina(conn, codice, "X", "pc", clock.now(), ip="10.0.0.99") == abbina.BLOCCATO


def test_tentativi_contati_senza_corse(app):
    """Venti tentativi insieme non superano il limite: controllo e registrazione sono atomici."""
    import threading

    from app import clock

    risultati = []

    def prova():
        with db.aperta(app.state.settings.db_path) as conn:
            risultati.append(abbina.abbina(conn, "00000000", "X", "pc", clock.now()))

    fili = [threading.Thread(target=prova) for _ in range(20)]
    for f in fili:
        f.start()
    for f in fili:
        f.join()
    with db.aperta(app.state.settings.db_path) as conn:
        registrati = conn.execute("SELECT COUNT(*) FROM tentativi_abbinamento").fetchone()[0]
    assert registrati == 10
    assert risultati.count(None) == 10 and risultati.count(abbina.BLOCCATO) == 10


def test_tipo_sbagliato(client, app):
    r = client.post("/api/abbina", json={"codice": _codice(app), "nome": "X", "tipo": "tablet"})
    assert r.status_code == 422


def test_revoca(client, app, auth):
    codice = _codice(app)
    altro = client.post("/api/abbina", json={"codice": codice, "nome": "PC", "tipo": "pc"}).json()
    r = client.delete(f"/api/dispositivi/{altro['dispositivo_id']}", headers=auth)
    assert r.status_code == 200
    r = client.get("/api/caselle", headers={"Authorization": f"Bearer {altro['token']}"})
    assert r.status_code == 401
    assert client.delete("/api/dispositivi/999", headers=auth).status_code == 404
