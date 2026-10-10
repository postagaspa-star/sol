from conftest import mail_per_oggetto, scrivi_tutto, smista_tutto


def _gruppi(client, auth):
    return {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}


def test_forma_della_mail(client, auth, finta):
    m = mail_per_oggetto(client, auth, "capannone")
    assert set(m) == {
        "id", "casella_id", "casella", "mittente", "oggetto", "anteprima", "riassunto", "ricevuta_il", "letta",
        "risposta_data", "gruppo_id", "gruppo", "da_rispondere", "urgenza", "sicurezza", "smistamento",
        "origine_smistamento", "applicata_in_casella", "bozza", "allegati", "pec", "incerti",
    }
    assert m["pec"] is None and m["incerti"] == []
    assert m["mittente"] == {"nome": "Mario Rossi", "indirizzo": "rossi@esempio.it"}
    assert m["casella"] == "demo@esempio.it"
    assert m["allegati"] == [{"nome": "pianta-capannone.pdf", "byte": 482113}]
    assert m["anteprima"].startswith("Buongiorno ingegnere")
    assert len(m["anteprima"]) <= 160
    assert m["ricevuta_il"] == "2026-10-02T10:00:00+02:00"
    assert m["bozza"] is None


def test_dettaglio(client, auth, finta, servizi):
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "capannone")
    d = client.get(f"/api/mail/{m['id']}", headers=auth).json()
    assert d["destinatari"] == ["demo@esempio.it"] and d["cc"] == []
    assert "pratica antincendio" in d["corpo"]
    assert set(d["probabilita"]) == {"gruppo", "da_rispondere", "urgenza"}


def test_filtri(client, auth, finta, servizi):
    smista_tutto(servizi)
    g = _gruppi(client, auth)
    pec = client.get(f"/api/mail?gruppo_id={g['PEC ed enti']}", headers=auth).json()["mail"]
    assert {m["gruppo"] for m in pec} == {"PEC ed enti"}
    urgenti = client.get("/api/mail?urgenza=alta", headers=auth).json()["mail"]
    assert [m["oggetto"] for m in urgenti] == ["URGENTE: inoltro fatture", "Sopralluogo urgente quadro elettrico"]
    da_rispondere = client.get("/api/mail?da_rispondere=true", headers=auth).json()["mail"]
    assert da_rispondere and all(m["da_rispondere"] for m in da_rispondere)
    non = client.get("/api/mail?da_rispondere=false", headers=auth).json()["mail"]
    assert len(non) + len(da_rispondere) == 10
    assert client.get(f"/api/mail?casella_id={finta['id']}", headers=auth).json()["mail"]
    assert client.get("/api/mail?casella_id=999", headers=auth).json()["mail"] == []
    assert len(client.get("/api/mail?letta=false", headers=auth).json()["mail"]) == 10
    assert client.get("/api/mail?urgenza=altissima", headers=auth).status_code == 422


def test_cerca(client, auth, finta, servizi):
    trovate = client.get("/api/mail?cerca=bianchi", headers=auth).json()["mail"]
    assert {m["mittente"]["indirizzo"] for m in trovate} == {"ordini@bianchi-compressori.esempio.it", "laura.bianchi@esempio.it"}
    assert client.get("/api/mail?cerca=%25", headers=auth).json()["mail"] == []  # il % non è un jolly
    smista_tutto(servizi)  # con mail da smistare il locale non scrive i riassunti in sottofondo
    scrivi_tutto(servizi)
    assert client.get("/api/mail?cerca=Riassunto di prova", headers=auth).json()["mail"]


def test_paginazione(client, auth, finta):
    pagina = client.get("/api/mail?limite=4", headers=auth).json()
    assert len(pagina["mail"]) == 4 and pagina["altre"] is True
    visti = [m["id"] for m in pagina["mail"]]
    while pagina["altre"]:
        pagina = client.get(f"/api/mail?limite=4&prima_di={visti[-1]}", headers=auth).json()
        visti += [m["id"] for m in pagina["mail"]]
    assert len(visti) == len(set(visti)) == 10
    assert client.get("/api/mail?prima_di=99999", headers=auth).status_code == 404


def test_sposta_impara_mittente(client, auth, finta):
    g = _gruppi(client, auth)
    m = mail_per_oggetto(client, auth, "Listino 2027")
    r = client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": g["Fornitori"], "impara": True}, headers=auth)
    assert r.status_code == 200
    regole = client.get("/api/regole", headers=auth).json()
    assert [(x["tipo"], x["valore"], x["gruppo_id"], x["origine"]) for x in regole] == [
        ("mittente", "commerciale@ferri.esempio.it", g["Fornitori"], "imparata")
    ]
    # Una seconda correzione aggiorna la regola, non ne crea un'altra.
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": g["Clienti"], "impara": True}, headers=auth)
    regole = client.get("/api/regole", headers=auth).json()
    assert len(regole) == 1 and regole[0]["gruppo_id"] == g["Clienti"]


def test_sposta_impara_dominio_solo_se_chiesto_e_non_pubblico(client, auth, finta, servizi, orologio):
    g = _gruppi(client, auth)
    m = mail_per_oggetto(client, auth, "Listino 2027")
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": g["Fornitori"], "impara": True, "impara_dominio": True}, headers=auth)
    tipi = {(x["tipo"], x["valore"]) for x in client.get("/api/regole", headers=auth).json()}
    assert ("dominio", "ferri.esempio.it") in tipi


def test_dominio_pubblico_non_diventa_regola(client, auth, servizi, app, monkeypatch):
    from app import clock, db, smista

    # Un dominio "pubblico" finto: nel repo niente indirizzi che potrebbero esistere.
    monkeypatch.setattr(smista, "DOMINI_PUBBLICI", smista.DOMINI_PUBBLICI | {"posta-pubblica.esempio.it"})

    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute(
            "INSERT INTO caselle (tipo, indirizzo, nome, stato, attiva_dal, creata_il) VALUES ('finta', 'x@esempio.it', 'x', 'collegata', ?, ?)",
            (clock.adesso(), clock.adesso()),
        )
        conn.execute(
            "INSERT INTO mail (casella_id, id_esterno, mittente_indirizzo, oggetto, ricevuta_il) VALUES (1, 'a', 'mario.rossi@posta-pubblica.esempio.it', 'Ciao', ?)",
            (clock.adesso(),),
        )
    g = _gruppi(client, auth)
    client.post("/api/mail/1/sposta", json={"gruppo_id": g["Clienti"], "impara": True, "impara_dominio": True}, headers=auth)
    tipi = {(x["tipo"], x["valore"]) for x in client.get("/api/regole", headers=auth).json()}
    assert tipi == {("mittente", "mario.rossi@posta-pubblica.esempio.it")}


def test_sposta_gruppo_inesistente(client, auth, finta):
    m = mail_per_oggetto(client, auth, "Listino 2027")
    assert client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": 999}, headers=auth).status_code == 404


def test_conferma(client, auth, finta, servizi, decisore):
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert client.post(f"/api/mail/{m['id']}/conferma", headers=auth).status_code == 409
    decisore.sicurezza = 0.4
    smista_tutto(servizi)
    r = client.post(f"/api/mail/{m['id']}/conferma", headers=auth)
    assert r.status_code == 200 and r.json()["smistamento"] == "smistata"
    # Ripetuta non fa danni.
    assert client.post(f"/api/mail/{m['id']}/conferma", headers=auth).json()["smistamento"] == "smistata"


def test_rispondere_rimette_e_toglie(client, auth, finta, servizi):
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "Listino 2027")
    assert m["da_rispondere"] is False and m["bozza"] is None
    r = client.post(f"/api/mail/{m['id']}/rispondere", json={"da_rispondere": True}, headers=auth)
    assert r.json()["da_rispondere"] is True
    assert r.json()["bozza"]["stato"] == "in_preparazione"
    r = client.post(f"/api/mail/{m['id']}/rispondere", json={"da_rispondere": False}, headers=auth)
    assert r.json()["da_rispondere"] is False
    assert client.post(f"/api/mail/{m['id']}/rispondere", json={"da_rispondere": "forse"}, headers=auth).status_code == 422


def test_gruppi_conteggi_e_gestione(client, auth, finta):
    gruppi = client.get("/api/gruppi", headers=auth).json()
    assert [g["nome"] for g in gruppi] == ["Clienti", "Fornitori", "Amministrazione e fatture", "PEC ed enti", "Newsletter", "Altro"]
    pec = next(g for g in gruppi if g["nome"] == "PEC ed enti")
    assert pec["cartella"] == "Sol/PEC ed enti" and pec["totale"] == 1 and pec["da_leggere"] == 1
    nuovo = client.post("/api/gruppi", json={"nome": "Perizie"}, headers=auth)
    assert nuovo.status_code == 201 and nuovo.json()["ordine"] == 7
    assert client.post("/api/gruppi", json={"nome": "perizie"}, headers=auth).status_code == 409
    assert client.post("/api/gruppi", json={"nome": "A/B"}, headers=auth).status_code == 422
    r = client.patch(f"/api/gruppi/{nuovo.json()['id']}", json={"nome": "Perizie danni", "ordine": 0}, headers=auth)
    assert r.json()["nome"] == "Perizie danni" and r.json()["cartella"] == "Sol/Perizie danni"
    assert client.get("/api/gruppi", headers=auth).json()[0]["nome"] == "Perizie danni"


def test_cancella_gruppo_mail_ad_altro(client, auth, finta):
    g = _gruppi(client, auth)
    client.post("/api/regole", json={"tipo": "mittente", "valore": "x@esempio.it", "gruppo_id": g["PEC ed enti"]}, headers=auth)
    assert client.delete(f"/api/gruppi/{g['PEC ed enti']}", headers=auth).status_code == 200
    assert mail_per_oggetto(client, auth, "integrazione pratica")["gruppo"] == "Altro"
    assert client.get("/api/regole", headers=auth).json() == []
    r = client.delete(f"/api/gruppi/{g['Altro']}", headers=auth)
    assert r.status_code == 409 and "Altro" in r.json()["errore"]


def test_regole_manuali(client, auth):
    g = _gruppi(client, auth)
    r = client.post("/api/regole", json={"tipo": "dominio", "valore": "@Esempio.it", "gruppo_id": g["Clienti"]}, headers=auth)
    assert r.status_code == 201
    assert r.json()["valore"] == "esempio.it" and r.json()["origine"] == "manuale"
    assert client.post("/api/regole", json={"tipo": "mittente", "valore": "senza-chiocciola", "gruppo_id": g["Clienti"]}, headers=auth).status_code == 422
    assert client.post("/api/regole", json={"tipo": "dominio", "valore": "a@esempio.it", "gruppo_id": g["Clienti"]}, headers=auth).status_code == 422
    assert client.post("/api/regole", json={"tipo": "dominio", "valore": "esempio.it", "gruppo_id": 999}, headers=auth).status_code == 404
    assert client.delete(f"/api/regole/{r.json()['id']}", headers=auth).status_code == 200
    assert client.delete(f"/api/regole/{r.json()['id']}", headers=auth).status_code == 404
