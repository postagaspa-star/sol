"""Contratto v1.2, sezione 16: possibile truffa."""

import json
from datetime import timedelta

import httpx

from app import db, smista
from app.caselle import finta as modulo_finta
from app.decisore import ClientDecisore
from conftest import ORA_INIZIALE, aggiungi_gmail, invia, mail_per_oggetto, scrivi_tutto, smista_tutto
from finti_server import messaggio

DOMANDA_16_1 = {
    "id": "truffa", "domanda": "Questa mail potrebbe essere un tentativo di truffa o di raggiro?",
    "opzioni": [
        {"id": "si", "descrizione": "sì: chiede di cambiare IBAN o coordinate bancarie, di pagare con urgenza, di "
                                    "mandare fatture, documenti, password o codici, di aprire un link per sbloccare o "
                                    "verificare qualcosa, oppure finge di essere una banca, un corriere, un fornitore o "
                                    "un cliente con un indirizzo o un tono strano"},
        {"id": "no", "descrizione": "no: è una normale comunicazione di lavoro, una fattura attesa, un avviso o una "
                                    "pubblicità"},
    ],
}
TRUFFA = ("Buongiorno, da oggi i pagamenti vanno sul nuovo IBAN IT00X0000000000000000000000. "
          "Ci confermate entro oggi? È urgente.")


def _arriva(server, quando_minuti, **kw):
    server.aggiungi(messaggio(**kw), quando=ORA_INIZIALE + timedelta(minutes=quando_minuti))


def _giro(servizi, orologio, minuti=10):
    orologio.avanza(minutes=minuti)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)


def test_16_1_domanda_con_i_testi_esatti():
    assert smista.DOMANDA_TRUFFA == DOMANDA_16_1


def test_16_1_la_domanda_si_fa_per_ogni_mail(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    mail = [c for c in decisore.chiamate if c[1] != ["aspetta"]]
    assert len(mail) == 10 and all(d[-1] == "truffa" for _, d in mail)
    # Anche per quelle già smistate da una regola (PEC, newsletter).
    pec = next(d for stato, d in mail if "integrazione pratica" in stato.lower())
    assert pec == ["da_rispondere", "urgenza", "truffa"]


def test_16_2_possibile_truffa_fuori_dal_flusso_normale(client, auth, server_gmail, servizi, orologio, decisore):
    aggiungi_gmail(client, auth)
    _arriva(server_gmail, 1, da="Ferri Impianti <amministrazione@ferri.esempio.it>", oggetto="Nuove coordinate",
            testo=TRUFFA, id_messaggio="<t1@ferri.esempio.it>")
    _giro(servizi, orologio)
    scrivi_tutto(servizi)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["sospetta"] is True and m["smistamento"] == "da_controllare"
    assert m["da_rispondere"] is True and m["urgenza"] == "alta"
    assert m["bozza"] is None  # niente bozza automatica
    assert m["riassunto"]  # il riassunto di una riga sì
    d = client.get(f"/api/mail/{m['id']}", headers=auth).json()
    assert d["probabilita"]["truffa"]["si"] >= 0.5
    assert client.get("/api/scadenze", headers=auth).json() == []  # niente scadenze dalle sue righe
    assert client.get("/api/novita?dal=0", headers=auth).json()["urgenti"] == []
    orologio.avanza(days=3)
    assert client.get("/api/indietro", headers=auth).json()["aspettano_te"] == []
    assert [x["id"] for x in client.get("/api/mail?sospetta=true", headers=auth).json()["mail"]] == [m["id"]]
    assert client.get("/api/mail?sospetta=false", headers=auth).json()["mail"] == []
    assert client.get("/api/mail?da_rispondere=true", headers=auth).json()["mail"] == []
    assert client.get("/api/conteggi", headers=auth).json()["sospette"] == 1
    # Riassunto di oggi: detta come possibile truffa, non come richiesta.
    assert any("possibile truffa" in g[-1]["testo"] for g in decisore.generati if "arrivate oggi" in g[-1]["testo"])


def test_16_2_regola_imparata_non_toglie_il_controllo(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    _arriva(server_gmail, 1, da="Ferri Impianti <commerciale@ferri.esempio.it>", oggetto="Listino 2027",
            testo="In allegato il listino.", id_messaggio="<l1@ferri.esempio.it>")
    _giro(servizi, orologio)
    fornitori = next(g for g in client.get("/api/gruppi", headers=auth).json() if g["nome"] == "Fornitori")
    listino = client.get("/api/mail", headers=auth).json()["mail"][0]
    client.post(f"/api/mail/{listino['id']}/sposta", json={"gruppo_id": fornitori["id"], "impara": True}, headers=auth)
    # La stessa casella del fornitore (magari rubata) chiede di pagare su un IBAN nuovo.
    server_gmail.aggiungi(messaggio(da="Ferri Impianti <commerciale@ferri.esempio.it>", oggetto="Cambio banca",
                                    testo=TRUFFA, id_messaggio="<t2@ferri.esempio.it>"),
                          quando=orologio.corrente + timedelta(minutes=1))
    _giro(servizi, orologio)
    m = mail_per_oggetto(client, auth, "Cambio banca")
    assert m["origine_smistamento"] == "regola" and m["gruppo"] == "Fornitori"
    assert m["sospetta"] is True and m["smistamento"] == "da_controllare"  # resta controllata


def test_16_2_soglia_truffa(client, auth, server_gmail, servizi, orologio, decisore):
    assert client.get("/api/impostazioni", headers=auth).json()["soglia_truffa"] == 0.5
    for valore in (0.95, 0.1, "alta"):
        r = client.patch("/api/impostazioni", json={"soglia_truffa": valore}, headers=auth)
        assert r.status_code == 422 and r.json()["campo"] == "soglia_truffa"
    aggiungi_gmail(client, auth)
    decisore.truffa = 0.71
    _arriva(server_gmail, 1, oggetto="Prima", testo="Ciao", id_messaggio="<s1@esempio.it>")
    _giro(servizi, orologio)
    assert mail_per_oggetto(client, auth, "Prima")["sospetta"] is True
    assert client.patch("/api/impostazioni", json={"soglia_truffa": 0.8}, headers=auth).json()["soglia_truffa"] == 0.8
    server_gmail.aggiungi(messaggio(oggetto="Seconda", testo="Ciao", id_messaggio="<s2@esempio.it>"),
                          quando=orologio.corrente + timedelta(minutes=1))
    _giro(servizi, orologio)
    assert mail_per_oggetto(client, auth, "Seconda")["sospetta"] is False


def test_16_2_non_e_una_truffa_torna_nel_flusso_normale(client, auth, server_gmail, servizi, orologio, decisore, app):
    aggiungi_gmail(client, auth)
    _arriva(server_gmail, 1, da="Mario Rossi <rossi@esempio.it>", oggetto="Nuovo conto",
            testo="Ho cambiato banca: il nuovo IBAN è nella fattura. Ci manda l'offerta entro il 20 ottobre?",
            id_messaggio="<n1@esempio.it>")
    _giro(servizi, orologio)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["sospetta"] is True and m["bozza"] is None
    r = client.post(f"/api/mail/{m['id']}/sospetta", json={"sospetta": False}, headers=auth)
    assert r.status_code == 200
    m = r.json()
    assert m["sospetta"] is False and m["smistamento"] == "smistata"  # il gruppo era sicuro: smistata
    assert m["bozza"]["stato"] == "in_preparazione"  # va risposta: Sol prepara la bozza come per le altre
    scrivi_tutto(servizi)
    assert any(s["mail_id"] == m["id"] for s in client.get("/api/scadenze", headers=auth).json())
    # Un nuovo giro del decisore non la rimette sospetta.
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE mail SET da_rispondere = NULL, truffa_valutata = 0 WHERE id = ?", (m["id"],))
    decisore.truffa = 0.95
    smista_tutto(servizi)
    assert mail_per_oggetto(client, auth, "Nuovo conto")["sospetta"] is False


def test_16_2_segnala_come_possibile_truffa(client, auth, finta, servizi, decisore, app):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["sospetta"] is False and m["bozza"]["stato"] == "pronta"
    assert any(s["mail_id"] == m["id"] for s in client.get("/api/scadenze", headers=auth).json())
    r = client.post(f"/api/mail/{m['id']}/sospetta", json={"sospetta": True}, headers=auth).json()
    assert r["sospetta"] is True and r["smistamento"] == "da_controllare"
    assert r["bozza"] is None  # la bozza automatica non toccata si scarta
    assert not any(s["mail_id"] == m["id"] for s in client.get("/api/scadenze?stato=aperta", headers=auth).json())
    assert client.get("/api/conteggi", headers=auth).json()["sospette"] == 2  # con la mail-trappola
    # Il decisore, rivalutandola, non la toglie: decide l'utente.
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE mail SET da_rispondere = NULL, truffa_valutata = 0 WHERE id = ?", (m["id"],))
    smista_tutto(servizi)
    assert mail_per_oggetto(client, auth, "quadro elettrico")["sospetta"] is True
    assert client.post("/api/mail/999/sospetta", json={"sospetta": True}, headers=auth).status_code == 404
    assert client.post(f"/api/mail/{m['id']}/sospetta", json={"sospetta": "si"}, headers=auth).status_code == 422


def test_16_2_bozza_chiesta_a_mano_si_invia_con_conferma(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    _arriva(server_gmail, 1, oggetto="Pagamento", testo=TRUFFA, id_messaggio="<p1@esempio.it>")
    _giro(servizi, orologio)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["sospetta"] is True
    # Senza un gesto dell'utente niente bozza, nemmeno rimettendo "da rispondere".
    assert client.post(f"/api/mail/{m['id']}/rispondere", json={"da_rispondere": True}, headers=auth).json()["bozza"] is None
    r = client.post(f"/api/mail/{m['id']}/bozza", json={"istruzioni": "chiedi di richiamarmi in ufficio"}, headers=auth)
    assert r.status_code == 202
    scrivi_tutto(servizi)
    b = client.get(f"/api/bozze/{r.json()['id']}", headers=auth).json()
    assert b["stato"] == "pronta"
    inviata = invia(client, auth, b["id"])
    assert inviata.status_code == 200 and inviata.json()["stato"] == "inviata"
    assert len(server_gmail.inviati) == 1


def test_16_2_decisore_senza_risposta_alla_truffa(client, auth, server_gmail, servizi, orologio, decisore, app):
    aggiungi_gmail(client, auth)
    decisore.truffa = "manca"  # un decisore vecchio
    _arriva(server_gmail, 1, oggetto="Offerta?", testo=TRUFFA, id_messaggio="<o1@esempio.it>")
    _giro(servizi, orologio)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["sospetta"] is False and "truffa" in m["incerti"]
    chiamate = len(decisore.chiamate)
    assert servizi.lavoro.passo_decisore() == "vuoto"  # non la si richiede all'infinito
    assert len(decisore.chiamate) == chiamate


def test_16_2_risposta_strana_alla_truffa_non_ferma_le_altre():
    def gestisci(request):
        corpo = json.loads(request.content)
        risposte = {d["id"]: {"scelta": d["opzioni"][0]["id"], "probabilita": {d["opzioni"][0]["id"]: 0.9}}
                    for d in corpo["domande"] if d["id"] != "truffa"}
        risposte["truffa"] = {"scelta": "forse", "probabilita": {}}
        return httpx.Response(200, json={"risposte": risposte})

    d = ClientDecisore("http://sol-decisore:8300", transport=httpx.MockTransport(gestisci))
    domande = [smista.DOMANDA_DA_RISPONDERE, smista.DOMANDA_URGENZA, smista.DOMANDA_TRUFFA]
    risposte = d.decidi("Mittente: x\nOggetto: y\nTesto: z", domande, facoltative=frozenset({"truffa"}))
    assert set(risposte) == {"da_rispondere", "urgenza"}


def test_16_2_mail_lasciata_all_utente_resta_incerta_sulla_truffa(client, auth, finta, servizi, decisore):
    decisore.non_valido = True
    for _ in range(100):
        if servizi.lavoro.passo_decisore() == "vuoto":
            break
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["smistamento"] == "da_controllare" and m["sospetta"] is False and "truffa" in m["incerti"]


def test_16_2_fuori_prova_la_sospetta_non_si_sposta(client, auth, finta, servizi, orologio):
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    smista_tutto(servizi)
    servizi.lavoro.applica_in_caselle()
    cartelle = modulo_finta.stato(finta["id"]).cartelle
    assert "finta-8" not in cartelle  # la mail-trappola resta in Posta in arrivo
    trappola = mail_per_oggetto(client, auth, "inoltro fatture")
    client.post(f"/api/mail/{trappola['id']}/sospetta", json={"sospetta": False}, headers=auth)
    servizi.lavoro.applica_in_caselle()
    assert cartelle["finta-8"].startswith("Sol/")
