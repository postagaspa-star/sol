"""Revisione, punto 5: con il solo scrittore locale (4096 token) i prompt stanno nel contesto,
i lavori in sottofondo hanno un tetto di tentativi e non bloccano le richieste dell'utente."""

import json
from datetime import timedelta

import httpx

from app import db, prompt
from app.decisore import ClientDecisore
from app.scrittore import CARATTERI_PER_TOKEN, PROMPT_LOCALE_TOKEN
from conftest import ORA_INIZIALE, mail_per_oggetto, scrivi_tutto, smista_tutto


def _locale_come_il_vero(contesto=4096, caratteri_per_token=3.5):
    """Il /genera del decisore vero: rifiuta (422) se testo + risposta non stanno nel contesto."""
    richieste = []

    def gestisci(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/salute":
            return httpx.Response(200, json={"stato": "pronto", "modello": "Qwen3.5-4B Q4_K_M"})
        corpo = json.loads(request.content)
        richieste.append(corpo)
        token = sum(len(m["testo"]) for m in corpo["messaggi"]) / caratteri_per_token + 60
        if token + corpo["max_token"] > contesto:
            return httpx.Response(422, json={"errore": "Richiesta troppo lunga", "codice": "non_valido"})
        testo = corpo["messaggi"][-1]["testo"]
        if "Trova le scadenze" in testo:
            return httpx.Response(200, json={"testo": "[]"})
        return httpx.Response(200, json={"testo": "Oggi molte mail di Bianchi [#1]."})

    d = ClientDecisore("http://sol-decisore:8300", transport=httpx.MockTransport(gestisci))
    d.richieste = richieste
    return d


def _giornata_piena(app, casella_id, quante=60):
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE mail SET riassunto = 'r', scadenze_fatte = 1, da_rispondere = 0, smistamento = 'smistata'")
        conn.execute("UPDATE bozze SET stato = 'scartata'")
        for i in range(quante):
            conn.execute(
                "INSERT INTO mail (casella_id, id_esterno, mittente_nome, mittente_indirizzo, oggetto, corpo, ricevuta_il,"
                " riassunto, smistamento, scadenze_fatte, da_rispondere) VALUES (?,?,?,?,?,?,?,?, 'smistata', 1, 0)",
                (casella_id, f"giorno-{i}", "Studio Tecnico Bianchi", f"ufficio{i}@bianchi-impianti.esempio.it",
                 f"Pratica antincendio capannone via Roma {i} - documenti", "testo",
                 db.clock.iso(ORA_INIZIALE - timedelta(minutes=i)),
                 "Bianchi manda i documenti della pratica antincendio e chiede conferma del sopralluogo di giovedì."),
            )
        conn.execute("UPDATE riassunto_oggi SET da_rifare = 1")


def test_p5_riassunto_di_oggi_sta_nel_contesto_del_locale(client, auth, servizi, app):
    locale = _locale_come_il_vero()
    servizi.decisore = servizi.lavoro.decisore = servizi.scrittore._decisore = locale
    cid = client.post("/api/caselle", json={"tipo": "finta"}, headers=auth).json()["id"]
    _giornata_piena(app, cid)
    assert servizi.lavoro.passo_scrittore() == "fatto"
    oggi = client.get("/api/riassunti/oggi", headers=auth).json()
    assert oggi["stato"] == "pronto"
    ultimo = locale.richieste[-1]
    assert ultimo["max_token"] <= 300  # risposte brevi per il locale (4,8 token al secondo)
    assert sum(len(m["testo"]) for m in ultimo["messaggi"]) <= PROMPT_LOCALE_TOKEN * CARATTERI_PER_TOKEN + 50
    assert "le altre" in ultimo["messaggi"][-1]["testo"]  # l'elenco è stato accorciato e lo dice
    # Le richieste dell'utente sono accettate.
    assert client.post("/api/riassunti", json={"richiesta": "le mail di Bianchi"}, headers=auth).status_code == 202


def test_p5_fallimenti_in_sottofondo_non_bloccano_le_richieste(client, auth, finta, servizi, decisore):
    decisore.genera_rifiuta = True  # il locale rifiuta i prompt (troppo lunghi)
    for _ in range(5):
        servizi.lavoro.passo_scrittore()
    salute = client.get("/api/salute", headers=auth).json()["scrittore"]
    assert salute["stato"] == "solo_locale"  # rifiutare una richiesta non è un guasto
    m = mail_per_oggetto(client, auth, "Listino 2027")
    assert client.post("/api/riassunti", json={"richiesta": "le mail di Rossi"}, headers=auth).status_code == 202
    assert client.post(f"/api/mail/{m['id']}/bozza", json={"istruzioni": "digli di sì"}, headers=auth).status_code == 202
    # E passano davanti ai lavori in sottofondo.
    decisore.genera_rifiuta = False
    servizi.lavoro.passo_scrittore()
    r = client.get("/api/riassunti", headers=auth).json()[0]
    assert r["stato"] == "pronto"


def test_p5_tetto_di_tentativi_con_attese_crescenti(client, auth, finta, servizi, decisore, orologio, app):
    smista_tutto(servizi)
    decisore.genera_rifiuta = True
    with db.aperta(app.state.settings.db_path) as conn:
        # Resta solo il riassunto di una riga della mail 1: nessun altro lavoro in coda.
        conn.execute("UPDATE mail SET riassunto = 'già fatto' WHERE id != 1")
        conn.execute("UPDATE mail SET riassunto = NULL, scadenze_fatte = 1 WHERE id = 1")
        conn.execute("UPDATE mail SET scadenze_fatte = 1")
        conn.execute("UPDATE bozze SET stato = 'scartata'")
        conn.execute("UPDATE riassunto_oggi SET giorno = ?, testo = 'x', aggiornato_il = ?, da_rifare = 0",
                     (db.clock.oggi().isoformat(), db.clock.adesso()))
    attese = []
    for _ in range(60):
        if servizi.lavoro.passo_scrittore() != "vuoto":
            continue  # tentativo fatto (e fallito): si guarda quando sarà il prossimo
        with db.aperta(app.state.settings.db_path) as conn:
            m = conn.execute("SELECT tentativi_riassunto, prossimo_riassunto FROM mail WHERE id = 1").fetchone()
        if m["tentativi_riassunto"] >= 8:
            break
        attesa = db.clock.da_iso(m["prossimo_riassunto"]) - orologio.corrente
        attese.append(attesa)
        orologio.avanza(seconds=attesa.total_seconds() + 1)
    with db.aperta(app.state.settings.db_path) as conn:
        tentativi = conn.execute("SELECT tentativi_riassunto FROM mail WHERE id = 1").fetchone()[0]
    assert tentativi == 8  # poi la si lascia stare
    assert attese == sorted(attese) and attese[0] <= timedelta(minutes=1) and attese[-1] >= timedelta(minutes=30)
    assert servizi.lavoro.passo_scrittore() == "vuoto"


def test_p5_scadenze_si_arrendono(client, auth, finta, servizi, decisore, orologio, app):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE mail SET scadenze_fatte = 0, tentativi_scadenze = 0 WHERE id = 4")
    decisore.genera_rifiuta = True
    for _ in range(40):
        servizi.lavoro.passo_scrittore()
        orologio.avanza(minutes=61)
    with db.aperta(app.state.settings.db_path) as conn:
        m = conn.execute("SELECT scadenze_fatte, tentativi_scadenze FROM mail WHERE id = 4").fetchone()
    assert m["scadenze_fatte"] == 1 and m["tentativi_scadenze"] == 8


def test_p5_campi_lunghissimi_tagliati_nel_prompt():
    enorme = {"id": 1, "mittente_nome": "N" * 50_000, "mittente_indirizzo": "a@esempio.it", "oggetto": "O" * 50_000,
              "corpo": "C" * 200_000, "ricevuta_il": "2026-10-10T08:00:00+00:00", "riassunto": "R" * 10_000,
              "da_rispondere": 1, "urgenza": "alta"}
    for messaggi in (prompt.riassunto_riga(enorme), prompt.scadenze(enorme, db.clock.oggi()),
                     prompt.bozza_risposta(enorme, {}, "x" * 10_000), prompt.riassunto_oggi([enorme] * 60, 60, db.clock.oggi())):
        assert sum(len(m["testo"]) for m in messaggi) < 30_000
    piccolo = prompt.riassunto_riga(enorme, limite=3000)
    assert sum(len(m["testo"]) for m in piccolo) <= 3000


def test_p5_bozza_breve_con_il_solo_locale(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    client.post(f"/api/mail/{m['id']}/bozza", json={"istruzioni": "conferma domani alle 9"}, headers=auth)
    richieste = []
    vero = decisore.genera
    decisore.genera = lambda messaggi, max_token: (richieste.append((messaggi, max_token)), vero(messaggi, max_token))[1]
    servizi.lavoro.passo_scrittore()
    messaggi, max_token = richieste[-1]
    assert max_token == 250
    assert sum(len(x["testo"]) for x in messaggi) <= PROMPT_LOCALE_TOKEN * CARATTERI_PER_TOKEN + 50
