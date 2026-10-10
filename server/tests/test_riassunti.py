import re

from app import db, ricerca
from conftest import mail_per_oggetto, scrivi_tutto, smista_tutto


def test_riassunto_di_una_riga(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    for m in client.get("/api/mail?limite=200", headers=auth).json()["mail"]:
        assert m["riassunto"] == "Riassunto di prova della mail."


def test_riassunto_di_oggi(client, auth, finta, servizi, decisore):
    vuoto = client.get("/api/riassunti/oggi", headers=auth).json()
    assert vuoto["stato"] == "in_coda" and vuoto["testo"] == ""
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    oggi = client.get("/api/riassunti/oggi", headers=auth).json()
    assert oggi["stato"] == "pronto"
    assert oggi["aggiornato_il"] == "2026-10-10T10:00:00+02:00"
    # Le mail di oggi sono 4 (dalle 8:30 alle 9:55); la citazione inventata [#999999] sparisce.
    assert oggi["testo"].startswith("Oggi 4 mail")
    assert "999999" not in oggi["testo"]
    assert len(oggi["mail_citate"]) == 2
    for i in oggi["mail_citate"]:
        assert f"[#{i}]" in oggi["testo"]
    prompt_oggi = next(g for g in decisore.generati if "Riassumi le mail arrivate oggi" in g[-1]["testo"])
    # La mail-trappola è urgente ma è una possibile truffa: non conta tra le importanti e lo si dice.
    assert "Sono 4, di cui 1 importanti" in prompt_oggi[-1]["testo"]
    assert "ATTENZIONE: segnalata come possibile truffa" in prompt_oggi[-1]["testo"]


def test_riassunto_di_oggi_si_rifa_ogni_ora_e_con_mail_importanti(client, auth, finta, servizi, decisore, orologio):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    assert servizi.lavoro.passo_scrittore() == "vuoto"
    orologio.avanza(minutes=61)
    assert servizi.lavoro.passo_scrittore() == "fatto"
    assert servizi.lavoro.passo_scrittore() == "vuoto"
    orologio.avanza(minutes=8)
    # Arrivano il sollecito di Rossi (+7 min), la PEC del Comune (+25) e la mail di Galli (+60).
    assert servizi.lavoro.controlla_casella(finta["id"]) == 3
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    assert client.get("/api/riassunti/oggi", headers=auth).json()["testo"].startswith("Oggi 7 mail")


def test_riassunto_di_ieri_non_si_mostra_oggi(client, auth, finta, servizi, orologio):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    orologio.avanza(days=1)
    r = client.get("/api/riassunti/oggi", headers=auth).json()
    assert r["stato"] == "in_coda" and r["testo"] == ""


def test_giorno_senza_mail(client, auth, servizi):
    scrivi_tutto(servizi)
    r = client.get("/api/riassunti/oggi", headers=auth).json()
    assert r == {"testo": "Oggi non è arrivata nessuna mail.", "aggiornato_il": "2026-10-10T10:00:00+02:00",
                 "mail_citate": [], "stato": "pronto"}


def test_riassunto_a_richiesta(client, auth, finta, servizi, decisore):
    scrivi_tutto(servizi)
    r = client.post("/api/riassunti", json={"richiesta": "cosa ha scritto Rossi"}, headers=auth)
    assert r.status_code == 202
    assert r.json()["stato"] == "in_coda"
    rid = r.json()["id"]
    attesa = client.get(f"/api/riassunti/{rid}", headers=auth).json()
    assert attesa["stato"] == "in_coda" and attesa["testo"] is None
    scrivi_tutto(servizi)
    pronto = client.get(f"/api/riassunti/{rid}", headers=auth).json()
    assert pronto["stato"] == "pronto"
    rossi = mail_per_oggetto(client, auth, "capannone")["id"]
    assert pronto["mail_citate"] == [rossi]
    assert f"[#{rossi}]" in pronto["testo"]
    assert set(pronto) == {"id", "richiesta", "stato", "testo_parziale", "testo", "mail_citate", "errore", "creato_il"}
    ultimo = decisore.generati[-1][-1]["testo"]
    assert "Richiesta dell'utente: cosa ha scritto Rossi" in ultimo
    assert ultimo.index("Richiesta dell'utente") < ultimo.index("<<<DATI-")
    elenco = client.get("/api/riassunti", headers=auth).json()
    assert [x["id"] for x in elenco] == [rid]


def test_riassunto_senza_mail_pertinenti(client, auth, finta, servizi, decisore):
    rid = client.post("/api/riassunti", json={"richiesta": "il preventivo per Marte"}, headers=auth).json()["id"]
    chiamate = len(decisore.generati)
    scrivi_tutto(servizi)
    r = client.get(f"/api/riassunti/{rid}", headers=auth).json()
    assert r["stato"] == "pronto" and r["mail_citate"] == []
    assert "Non ho trovato" in r["testo"]


def test_riassunto_scrittore_giu(client, auth, finta, servizi, decisore):
    rid = client.post("/api/riassunti", json={"richiesta": "le mail di oggi"}, headers=auth).json()["id"]
    decisore.genera_giu = True
    assert servizi.lavoro.passo_scrittore() == "guasto"
    r = client.get(f"/api/riassunti/{rid}", headers=auth).json()
    assert r["stato"] == "errore" and "Riprova" in r["errore"]


def test_riassunto_503_senza_scrittori(client, auth, decisore):
    decisore.giu = True
    r = client.post("/api/riassunti", json={"richiesta": "le mail di oggi"}, headers=auth)
    assert r.status_code == 503


def test_riassunto_non_trovato(client, auth):
    assert client.get("/api/riassunti/77", headers=auth).status_code == 404


# --- scelta delle mail ------------------------------------------------------------------


def _scegli(app, richiesta):
    with db.aperta(app.state.settings.db_path) as conn:
        return [m["oggetto"] for m in ricerca.seleziona(conn, richiesta)]


def test_ricerca_per_mittente(app, finta):
    assert set(_scegli(app, "cosa ha scritto Bianchi")) == {"Conferma d'ordine n. 2318", "Sopralluogo urgente quadro elettrico"}


def test_ricerca_per_parola_con_plurale(app, finta):
    scelte = _scegli(app, "le fatture")
    assert scelte[0] in ("Fattura n. 112 in scadenza", "URGENTE: inoltro fatture")
    assert "Fattura n. 112 in scadenza" in scelte


def test_ricerca_per_periodo(app, finta):
    assert len(_scegli(app, "le mail di oggi")) == 4
    assert len(_scegli(app, "le mail di questa settimana")) == 9


def test_ricerca_per_gruppo(app, finta, servizi):
    smista_tutto(servizi)
    assert _scegli(app, "le pec") == ["Richiesta integrazione pratica SCIA n. 45/2026"]


def test_ricerca_massimo_40(app, servizi):
    from app import clock

    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute(
            "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il) VALUES ('finta', 'x@esempio.it', 'x', 'collegata', ?, ?)",
            (clock.adesso(), clock.adesso()),
        )
        for i in range(60):
            conn.execute(
                "INSERT INTO mail (casella_id, id_esterno, mittente_indirizzo, oggetto, ricevuta_il) VALUES (1, ?, 'neri@esempio.it', 'CRM nuovo', ?)",
                (f"e{i}", clock.adesso()),
            )
    assert len(_scegli(app, "le ultime mail sul CRM")) == 40


def test_citazioni():
    from app import prompt

    testo, citate = prompt.citazioni("Vedi [#3] e [#7], poi [#3] e [#99].", {3, 7})
    assert citate == [3, 7]
    assert not re.search(r"\[#99\]", testo)
