"""Contratto v1.3, sezione 17: colori dei gruppi, scadenze prossime, "Annulla" dello spostamento."""

from datetime import timedelta

from app import db
from conftest import mail_per_oggetto


def _gruppi(client, auth):
    return {g["nome"]: g for g in client.get("/api/gruppi", headers=auth).json()}


def test_colori_dei_gruppi_iniziali(client, auth):
    colori = {nome: g["colore"] for nome, g in _gruppi(client, auth).items()}
    assert colori == {"Clienti": "blu", "Fornitori": "verde", "Amministrazione e fatture": "ambra",
                      "PEC ed enti": "viola", "Newsletter": "grigio", "Altro": "ardesia"}


def test_gruppo_nuovo_riceve_il_primo_colore_libero(client, auth):
    assert client.post("/api/gruppi", json={"nome": "Perizie"}, headers=auth).json()["colore"] == "petrolio"
    assert client.post("/api/gruppi", json={"nome": "Cantieri", "colore": "ciano"}, headers=auth).json()["colore"] == "ciano"
    assert client.post("/api/gruppi", json={"nome": "Assicurazioni"}, headers=auth).json()["colore"] == "rosa"
    for nome in ("A1", "A2", "A3"):  # indaco, oliva, marrone: ora sono usati tutti e 12
        client.post("/api/gruppi", json={"nome": nome}, headers=auth)
    # Tutti usati: si ricomincia dall'inizio dell'elenco.
    assert client.post("/api/gruppi", json={"nome": "A4"}, headers=auth).json()["colore"] == "blu"
    assert client.post("/api/gruppi", json={"nome": "A5"}, headers=auth).json()["colore"] == "verde"


def test_colore_si_cambia_e_si_controlla(client, auth):
    clienti = _gruppi(client, auth)["Clienti"]
    r = client.patch(f"/api/gruppi/{clienti['id']}", json={"colore": "indaco"}, headers=auth)
    assert r.status_code == 200 and r.json()["colore"] == "indaco"
    for corpo, metodo, url in (({"colore": "fucsia"}, "patch", f"/api/gruppi/{clienti['id']}"),
                               ({"nome": "Nuovo", "colore": "Blu "}, "post", "/api/gruppi")):
        r = getattr(client, metodo)(url, json=corpo, headers=auth)
        assert r.status_code == 422 and r.json()["campo"] == "colore"
        assert r.json()["errore"].startswith("Questo colore non c'è.") and "colore" not in r.json()["errore"][:3]


def test_colori_assegnati_anche_ai_database_esistenti(tmp_path, monkeypatch):
    percorso = str(tmp_path / "v12.db")
    monkeypatch.setattr(db, "MIGRAZIONI", db.MIGRAZIONI[:3])
    db.init_db(percorso)
    with db.aperta(percorso) as conn:
        conn.execute("INSERT INTO gruppi (nome, ordine, descrizione) VALUES ('Perizie', 7, 'Perizie')")
    monkeypatch.undo()
    db.init_db(percorso)
    with db.aperta(percorso) as conn:
        colori = {r["nome"]: r["colore"] for r in conn.execute("SELECT nome, colore FROM gruppi")}
    assert colori["Clienti"] == "blu" and colori["Altro"] == "ardesia" and colori["Perizie"] == "petrolio"


def test_scadenze_prossime(client, auth, finta, servizi, app):
    from app import clock

    oggi = clock.oggi()
    with db.aperta(app.state.settings.db_path) as conn:
        trappola = conn.execute("SELECT id FROM mail WHERE oggetto LIKE '%inoltro fatture%'").fetchone()["id"]
        conn.execute("UPDATE mail SET sospetta = 1 WHERE id = ?", (trappola,))
        for giorni, stato, mail_id in ((0, "aperta", None), (7, "aperta", None), (8, "aperta", None),
                                       (-1, "aperta", None), (3, "fatta", None), (2, "aperta", trappola)):
            conn.execute(
                "INSERT INTO scadenze (mail_id, descrizione, quando, stato, creata_il) VALUES (?, 'x', ?, ?, ?)",
                (mail_id, (oggi + timedelta(days=giorni)).isoformat(), stato, clock.adesso()),
            )
    # Oggi e fra 7 giorni sì; fra 8, ieri, le fatte e quelle di una possibile truffa no.
    assert client.get("/api/conteggi", headers=auth).json()["scadenze_prossime"] == 2


def test_annulla_lo_spostamento_aggiorna_la_regola(client, auth, finta):
    gruppi = _gruppi(client, auth)
    m = mail_per_oggetto(client, auth, "Listino 2027")
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": gruppi["Fornitori"]["id"], "impara": True}, headers=auth)
    # "Annulla": la si rimette dov'era, sempre con impara.
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": gruppi["Clienti"]["id"], "impara": True}, headers=auth)
    regole = client.get("/api/regole", headers=auth).json()
    assert [(r["tipo"], r["valore"], r["gruppo_id"]) for r in regole] == [
        ("mittente", "commerciale@ferri.esempio.it", gruppi["Clienti"]["id"])
    ]
