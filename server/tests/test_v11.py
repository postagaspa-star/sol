"""Contratto v1.1: conteggi (15.4), rifare le bozze (15.5, 15.13), filtro da rispondere e bozze
scartate quando si è già risposto da un altro programma (15.13)."""

from conftest import invia, mail_per_oggetto, scrivi_tutto, smista_tutto


def test_15_4_conteggi(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    c = client.get("/api/conteggi", headers=auth).json()
    tutte = client.get("/api/mail?limite=200", headers=auth).json()["mail"]
    assert set(c) == {"da_rispondere", "urgenti", "da_controllare", "da_leggere", "bozze_pronte", "gruppi", "sospette"}
    assert c["da_rispondere"] == sum(1 for m in tutte if m["da_rispondere"] and not m["risposta_data"] and not m["sospetta"])
    # La mail-trappola è una possibile truffa: conta tra le sospette e da controllare, non tra le urgenti.
    assert c["urgenti"] == 1 and c["da_leggere"] == 10 and c["da_controllare"] == 1 and c["sospette"] == 1
    assert c["bozze_pronte"] == len(client.get("/api/bozze?stato=pronta", headers=auth).json())
    gruppi = {g["id"]: g for g in client.get("/api/gruppi", headers=auth).json()}
    assert set(c["gruppi"]) == {str(i) for i in gruppi}
    for gid, valori in c["gruppi"].items():
        assert valori == {"da_leggere": gruppi[int(gid)]["da_leggere"], "totale": gruppi[int(gid)]["totale"]}
    # Letta una urgente, resta urgente solo se è ancora da rispondere.
    urgente = mail_per_oggetto(client, auth, "quadro elettrico")
    client.post(f"/api/mail/{urgente['id']}/letta", json={"letta": True}, headers=auth)
    assert client.get("/api/conteggi", headers=auth).json()["urgenti"] == 1
    invia(client, auth, urgente["bozza"]["id"])
    assert client.get("/api/conteggi", headers=auth).json()["urgenti"] == 0


def test_15_13_filtro_da_rispondere_esclude_le_mail_gia_risposte(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    prima = {m["id"] for m in client.get("/api/mail?da_rispondere=true", headers=auth).json()["mail"]}
    urgente = mail_per_oggetto(client, auth, "quadro elettrico")
    invia(client, auth, urgente["bozza"]["id"])
    dopo = {m["id"] for m in client.get("/api/mail?da_rispondere=true", headers=auth).json()["mail"]}
    assert dopo == prima - {urgente["id"]}
    non = {m["id"] for m in client.get("/api/mail?da_rispondere=false", headers=auth).json()["mail"]}
    assert urgente["id"] in non and len(non) + len(dopo) == 10


def test_15_5_rifai_qualsiasi_bozza(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    voce = client.get("/api/indietro", headers=auth).json()["aspetti_tu"][0]
    sollecito = client.post(f"/api/indietro/{voce['inviata']['id']}/sollecito", headers=auth).json()
    scrivi_tutto(servizi)
    r = client.post(f"/api/bozze/{sollecito['id']}/rifai", json={"istruzioni": "più gentile"}, headers=auth)
    assert r.status_code == 202 and r.json()["stato"] == "in_preparazione" and r.json()["tipo"] == "sollecito"
    scrivi_tutto(servizi)
    assert client.get(f"/api/bozze/{sollecito['id']}", headers=auth).json()["stato"] == "pronta"
    invia(client, auth, sollecito["id"])
    r = client.post(f"/api/bozze/{sollecito['id']}/rifai", json={"istruzioni": "x"}, headers=auth)
    assert r.status_code == 409


def test_15_13_rifai_parte_dalla_bozza_modificata(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    bid = m["bozza"]["id"]
    client.put(f"/api/bozze/{bid}", json={"testo": "Buongiorno Laura, passo domani alle 9 col tester.",
                                          "cc": ["neri@esempio.it"], "oggetto": "Re: sopralluogo domani"}, headers=auth)
    # Senza istruzioni una bozza modificata a mano non si butta via...
    r = client.post(f"/api/bozze/{bid}/rifai", json={"istruzioni": None}, headers=auth)
    assert r.status_code == 409 and r.json()["codice"] == "conflitto"
    assert client.post(f"/api/mail/{m['id']}/bozza", json={}, headers=auth).status_code == 409
    # ...con le istruzioni si parte dal testo dell'utente, e destinatari e oggetto restano suoi.
    r = client.post(f"/api/bozze/{bid}/rifai", json={"istruzioni": "aggiungi che porto anche il preventivo"},
                    headers=auth)
    assert r.status_code == 202
    scrivi_tutto(servizi)
    richiesta = next(g for g in reversed(decisore.generati) if "Prepara la bozza" in g[-1]["testo"])[-1]["testo"]
    assert "passo domani alle 9 col tester" in richiesta
    assert richiesta.index("<<<BOZZA-") < richiesta.index("passo domani") < richiesta.index("aggiungi che porto")
    b = client.get(f"/api/bozze/{bid}", headers=auth).json()
    assert b["cc"] == ["neri@esempio.it"] and b["oggetto"] == "Re: sopralluogo domani" and b["stato"] == "pronta"
    # Da zero solo se chiesto esplicitamente: torna tutto come lo prepara Sol.
    r = client.post(f"/api/bozze/{bid}/rifai", json={"istruzioni": None, "da_zero": True}, headers=auth)
    assert r.status_code == 202
    assert r.json()["cc"] == [] and r.json()["oggetto"] == "Re: Sopralluogo urgente quadro elettrico"
    scrivi_tutto(servizi)
    ultima = next(g for g in reversed(decisore.generati) if "Prepara la bozza" in g[-1]["testo"])[-1]["testo"]
    assert "<<<BOZZA-" not in ultima


def test_15_13_risposta_da_un_altro_programma_scarta_la_bozza_non_toccata(client, auth, server_gmail, servizi,
                                                                         orologio):
    from datetime import timedelta

    from conftest import ORA_INIZIALE, aggiungi_gmail
    from finti_server import messaggio

    aggiungi_gmail(client, auth)
    server_gmail.aggiungi(messaggio(oggetto="Offerta?", testo="Ci manda l'offerta?", id_messaggio="<q1@esempio.it>"),
                          quando=ORA_INIZIALE + timedelta(minutes=1), filo="t1")
    server_gmail.aggiungi(messaggio(oggetto="Sopralluogo?", testo="Quando passa?", id_messaggio="<q2@esempio.it>"),
                          quando=ORA_INIZIALE + timedelta(minutes=2), filo="t2")
    orologio.avanza(minutes=5)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    offerta = mail_per_oggetto(client, auth, "Offerta?")
    sopralluogo = mail_per_oggetto(client, auth, "Sopralluogo?")
    client.put(f"/api/bozze/{sopralluogo['bozza']['id']}", json={"testo": "La mia risposta"}, headers=auth)
    # L'utente risponde a tutte e due dal sito di Gmail.
    for filo, oggetto in (("t1", "Re: Offerta?"), ("t2", "Re: Sopralluogo?")):
        server_gmail.aggiungi(messaggio(da="studio@esempio.it", a="rossi@esempio.it", oggetto=oggetto,
                                        id_messaggio=f"<r-{filo}@esempio.it>"),
                              etichette=("\\Sent",), quando=orologio.corrente, filo=filo)
    orologio.avanza(minutes=5)
    servizi.lavoro.giro_posta()
    assert client.get(f"/api/bozze/{offerta['bozza']['id']}", headers=auth).json()["stato"] == "scartata"
    resta = client.get(f"/api/bozze/{sopralluogo['bozza']['id']}", headers=auth).json()
    assert resta["stato"] == "pronta" and resta["testo"] == "La mia risposta"  # modificata: resta
    assert mail_per_oggetto(client, auth, "Offerta?")["risposta_data"] is True
