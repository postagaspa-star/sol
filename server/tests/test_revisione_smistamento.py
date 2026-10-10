"""Revisione, punti 2, 4, 9 e contratto 15.1, 15.8, 15.14: smistamento con il decisore vero."""

import json
from datetime import timedelta
from email.message import EmailMessage
from pathlib import Path

import httpx
import pytest

from app import db, smista
from app.decisore import ClientDecisore
from conftest import ORA_INIZIALE, aggiungi_gmail, mail_per_oggetto, scrivi_tutto, smista_tutto
from finti_server import messaggio, pec_vera

FILE_DECISORE = Path(__file__).resolve().parents[2] / "decisore" / "app" / "domande_consigliate.json"
FILE_SERVER = Path(__file__).resolve().parents[1] / "app" / "domande_decisore.json"


def _decisore_che_valida(rifiuta_sempre: bool = False, codice: int = 422):
    """ClientDecisore vero + un trasporto che controlla le domande come il decisore vero
    (da 2 a 16 opzioni, al massimo 8 domande) e risponde sempre la prima opzione."""
    visti = []

    def gestisci(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/salute":
            return httpx.Response(200, json={"stato": "pronto", "modello": "Qwen3.5-4B Q4_K_M"})
        corpo = json.loads(request.content)
        visti.append(corpo)
        if rifiuta_sempre:
            return httpx.Response(codice, json={"errore": "no", "codice": "non_valido"})
        if not 1 <= len(corpo["domande"]) <= 8 or any(not 2 <= len(d["opzioni"]) <= 16 for d in corpo["domande"]):
            return httpx.Response(422, json={"errore": "La domanda deve avere da 2 a 16 opzioni.", "codice": "non_valido"})
        risposte = {
            d["id"]: {"scelta": d["opzioni"][0]["id"], "massa_opzioni": 0.97, "token": 4,
                      "probabilita": {o["id"]: (0.9 if i == 0 else 0.1 / (len(d["opzioni"]) - 1))
                                      for i, o in enumerate(d["opzioni"])}}
            for d in corpo["domande"]
        }
        return httpx.Response(200, json={"risposte": risposte, "millisecondi": 18000})

    d = ClientDecisore("http://sol-decisore:8300", transport=httpx.MockTransport(gestisci))
    d.visti = visti
    return d


def _usa(servizi, decisore):
    servizi.decisore = decisore
    servizi.lavoro.decisore = decisore


# --- punto 2 / 15.1 -----------------------------------------------------------------------------


def test_p2_al_massimo_16_gruppi(client, auth):
    for i in range(10):
        assert client.post("/api/gruppi", json={"nome": f"Cliente {i}"}, headers=auth).status_code == 201
    r = client.post("/api/gruppi", json={"nome": "Diciassettesimo"}, headers=auth)
    assert r.status_code == 422
    assert r.json() == {"errore": "Puoi avere al massimo 16 gruppi: togline uno prima di aggiungerne un altro.",
                        "codice": "non_valido"}


def test_p2_almeno_due_gruppi(client, auth):
    gruppi = client.get("/api/gruppi", headers=auth).json()
    altri = [g for g in gruppi if g["nome"] != "Altro"]
    for g in altri[:-1]:
        assert client.delete(f"/api/gruppi/{g['id']}", headers=auth).status_code == 200
    r = client.delete(f"/api/gruppi/{altri[-1]['id']}", headers=auth)
    assert r.status_code == 422 and r.json()["codice"] == "non_valido"


def test_p2_con_16_gruppi_il_decisore_vero_lavora(client, auth, servizi):
    decisore = _decisore_che_valida()
    _usa(servizi, decisore)
    for i in range(10):
        client.post("/api/gruppi", json={"nome": f"Cliente {i}"}, headers=auth)
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    smista_tutto(servizi)
    assert client.get("/api/mail?smistamento=in_attesa", headers=auth).json()["mail"] == []
    assert len(decisore.visti[0]["domande"][0]["opzioni"]) == 16


@pytest.mark.parametrize("codice", [422, 500])
def test_p2_decisore_che_rifiuta_conta_i_tentativi(client, auth, servizi, codice):
    decisore = _decisore_che_valida(rifiuta_sempre=True, codice=codice)
    _usa(servizi, decisore)
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    esiti = []
    for _ in range(100):
        esiti.append(servizi.lavoro.passo_decisore())
        if esiti[-1] == "vuoto":
            break
    assert "guasto" not in esiti  # non è "spento": rifiuta queste mail
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["smistamento"] == "da_controllare" and m["gruppo"] == "Altro"
    salute = client.get("/api/salute", headers=auth).json()["decisore"]
    assert salute["stato"] == "non_disponibile" and "rifiuta" in salute["errore"]


def test_p2_decisore_in_avvio_non_conta_come_tentativo(client, auth, servizi, app):
    def in_avvio(request):
        if request.url.path == "/salute":
            return httpx.Response(200, json={"stato": "in_avvio", "modello": "m", "percentuale": 37})
        return httpx.Response(503, json={"errore": "non pronto", "codice": "decisore_non_disponibile"})

    _usa(servizi, ClientDecisore("http://sol-decisore:8300", transport=httpx.MockTransport(in_avvio)))
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    for _ in range(5):
        assert servizi.lavoro.passo_decisore() == "guasto"
    with db.aperta(app.state.settings.db_path) as conn:
        assert conn.execute("SELECT MAX(tentativi_decisore) FROM mail").fetchone()[0] == 0
    salute = client.get("/api/salute", headers=auth).json()["decisore"]
    assert salute == {"stato": "in_avvio", "modello": "m", "percentuale": 37, "errore": None}


# --- 15.14 --------------------------------------------------------------------------------------


def test_domande_identiche_a_quelle_del_decisore(client, auth, app):
    """Il server manda ESATTAMENTE le domande consigliate dal decisore (contratto 15.14)."""
    copia = json.loads(FILE_SERVER.read_text(encoding="utf-8"))
    if FILE_DECISORE.exists():  # nel repo c'è anche il decisore: le due copie non divergono
        assert copia["domande"] == json.loads(FILE_DECISORE.read_text(encoding="utf-8"))["domande"]
    with db.aperta(app.state.settings.db_path) as conn:
        mandate = [smista.domanda_gruppo(conn), smista.DOMANDA_DA_RISPONDERE, smista.DOMANDA_URGENZA]
    assert mandate == copia["domande"]
    assert {k: v for k, v in db.DESCRIZIONI_2.items()} == {
        chiave: o["descrizione"] for chiave, o in zip(
            ["clienti", "fornitori", "amministrazione", "pec", "newsletter", "altro"], copia["domande"][0]["opzioni"])
    }


def test_gruppo_con_descrizione(client, auth, app):
    clienti = next(g for g in client.get("/api/gruppi", headers=auth).json() if g["nome"] == "Clienti")
    assert clienti["descrizione"].startswith("un cliente (azienda o privato)")
    senza = client.post("/api/gruppi", json={"nome": "Perizie"}, headers=auth).json()
    assert senza["descrizione"] == "Perizie"
    con = client.post("/api/gruppi", json={"nome": "Assicurazioni", "descrizione": "compagnie e periti"}, headers=auth).json()
    assert con["descrizione"] == "compagnie e periti"
    r = client.patch(f"/api/gruppi/{senza['id']}", json={"descrizione": "perizie per danni e sinistri"}, headers=auth)
    assert r.json()["descrizione"] == "perizie per danni e sinistri"
    with db.aperta(app.state.settings.db_path) as conn:
        opzioni = {o["id"]: o["descrizione"] for o in smista.domanda_gruppo(conn)["opzioni"]}
    assert opzioni["Perizie"] == "perizie per danni e sinistri" and opzioni["Assicurazioni"] == "compagnie e periti"


def test_al_decisore_solo_la_parte_nuova_della_risposta(client, auth, server_gmail, servizi, decisore, orologio):
    aggiungi_gmail(client, auth)
    testo = ("Va bene giovedì alle 9?\n\nIl giorno ven 10 ott 2026 alle 09:30 Studio <studio@esempio.it>\nha scritto:\n"
             "> Le propongo giovedì.\n> URGENTE fattura da pagare")
    server_gmail.aggiungi(messaggio(oggetto="Re: Sopralluogo", testo=testo), quando=ORA_INIZIALE + timedelta(minutes=1))
    orologio.avanza(minutes=5)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    stato = decisore.chiamate[-1][0]
    assert "Va bene giovedì alle 9?" in stato and "Le propongo" not in stato and "URGENTE" not in stato


def test_scelte_incerte_prudenti_e_segnate(client, auth, finta, servizi, decisore):
    decisore.incerto = True  # probabilità 0,5 su da_rispondere e urgenza
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "Listino 2027")  # il decisore direbbe "no"
    assert m["da_rispondere"] is True and m["urgenza"] == "normale"
    assert set(m["incerti"]) == {"da_rispondere", "urgenza"}
    assert m["bozza"] is not None


# --- punto 4 / 15.8 -------------------------------------------------------------------------------


def _pec_finta() -> bytes:
    interno = EmailMessage()
    interno["From"] = "Mario Rossi <rossi@cliente.esempio.it>"
    interno["Subject"] = "Nuove coordinate bancarie"
    interno.set_content("Da oggi i pagamenti vanno su un altro conto. Ci confermate?")
    busta = EmailMessage()
    busta["From"] = "promo@altro.esempio.it"
    busta["To"] = "studio@esempio.it"
    busta["Subject"] = "Comunicazione"
    busta["Message-ID"] = "<pec-finta@altro.esempio.it>"
    busta["X-Trasporto"] = "posta-certificata"
    busta.set_content("vedi allegato")
    busta.add_attachment(interno)
    return busta.as_bytes()


def test_p4_pec_finta_non_si_apre(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    server_gmail.aggiungi(_pec_finta(), quando=ORA_INIZIALE + timedelta(minutes=2))
    orologio.avanza(minutes=5)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["mittente"]["indirizzo"] == "promo@altro.esempio.it" and m["oggetto"] == "Comunicazione"
    assert m["pec"] is None and m["origine_smistamento"] != "regola" and m["sicurezza"] != 1.0
    if m["bozza"]:
        b = client.get(f"/api/bozze/{m['bozza']['id']}", headers=auth).json()
        assert b["a"] == ["promo@altro.esempio.it"]


def test_p4_pec_vera_si_apre_e_non_insegna_regole(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    interno = messaggio(da="Comune <protocollo@pec.comune.esempio.it>", oggetto="Integrazione pratica SCIA",
                        testo="Serve la relazione entro 30 giorni.")
    server_gmail.aggiungi(pec_vera(interno), quando=ORA_INIZIALE + timedelta(minutes=2))
    orologio.avanza(minutes=5)
    servizi.lavoro.giro_posta()
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert m["mittente"]["indirizzo"] == "protocollo@pec.comune.esempio.it"
    assert m["oggetto"] == "Integrazione pratica SCIA" and m["gruppo"] == "PEC ed enti"
    assert m["pec"] == {"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}
    newsletter = next(g for g in client.get("/api/gruppi", headers=auth).json() if g["nome"] == "Newsletter")
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": newsletter["id"], "impara": True,
                                                     "impara_dominio": True}, headers=auth)
    assert client.get("/api/regole", headers=auth).json() == []


# --- punto 9 --------------------------------------------------------------------------------------


def test_p9_list_unsubscribe_non_nasconde_le_richieste(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    server_gmail.aggiungi(messaggio(da="Ferri Impianti <commerciale@ferri.esempio.it>", oggetto="Ordine compressore",
                                    testo="Ci conferma l'ordine entro il 16 ottobre?", id_messaggio="<crm1@ferri.esempio.it>",
                                    intestazioni={"List-Unsubscribe": "<https://crm.esempio.it/u/123>"}),
                          quando=ORA_INIZIALE + timedelta(minutes=3))
    server_gmail.aggiungi(messaggio(da="Notizie <notizie@newsletter.esempio.it>", oggetto="Novità di ottobre",
                                    testo="Le novità del mese.", id_messaggio="<n1@newsletter.esempio.it>",
                                    intestazioni={"List-Unsubscribe": "<mailto:via@esempio.it>"}),
                          quando=ORA_INIZIALE + timedelta(minutes=4))
    orologio.avanza(minutes=10)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    ordine = mail_per_oggetto(client, auth, "Ordine compressore")
    assert ordine["smistamento"] == "da_controllare" and ordine["gruppo"] == "Newsletter"
    assert ordine["da_rispondere"] is True and ordine["bozza"]["stato"] == "pronta"
    scadenze = client.get("/api/scadenze", headers=auth).json()
    assert any(s["mail_id"] == ordine["id"] for s in scadenze)
    orologio.avanza(days=3)
    assert any(x["mail"]["id"] == ordine["id"] for x in client.get("/api/indietro", headers=auth).json()["aspettano_te"])
    news = mail_per_oggetto(client, auth, "Novità di ottobre")
    assert news["smistamento"] == "smistata" and news["da_rispondere"] is False and news["bozza"] is None
    assert not any(s["mail_id"] == news["id"] for s in scadenze)
