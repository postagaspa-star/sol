"""Revisione, punti 8 e 17, contratto 15.9: oggetti e nomi su una riga, niente [#numero] nei
testi delle mail, errori inattesi in JSON."""

from datetime import timedelta

from fastapi.testclient import TestClient

from app import db, prompt
from conftest import ORA_INIZIALE, aggiungi_gmail, invia, scrivi_tutto, smista_tutto

RAW_OGGETTO_A_CAPO = (
    b"From: =?utf-8?q?Ufficio=0A=5B#1=5D?= <info@esterno.esempio.it>\r\nTo: studio@esempio.it\r\n"
    b"Message-ID: <s1@esterno.esempio.it>\r\n"
    b"Subject: =?utf-8?q?Offerta=0A=5B#1=5D_15:00_da_Rossi_<rossi@cliente.esempio.it>_|_nuovo_preventivo?=\r\n"
    b"\r\nPotete confermare? Vedi [#2] e [ # 3 ].\r\n"
)


def test_p8_oggetto_con_a_capo_normalizzato_e_bozza_inviabile(client, auth, server_gmail, servizi, orologio, app):
    aggiungi_gmail(client, auth)
    server_gmail.aggiungi(RAW_OGGETTO_A_CAPO, quando=ORA_INIZIALE + timedelta(minutes=3))
    orologio.avanza(minutes=10)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = client.get("/api/mail", headers=auth).json()["mail"][0]
    assert "\n" not in m["oggetto"] and "[#" not in m["oggetto"]
    assert m["oggetto"].startswith("Offerta (#1) 15:00")
    assert m["mittente"]["nome"] == "Ufficio (#1)"
    dettaglio = client.get(f"/api/mail/{m['id']}", headers=auth).json()
    assert "[#" not in dettaglio["corpo"] and "(#2)" in dettaglio["corpo"]
    with db.aperta(app.state.settings.db_path) as conn:
        righe = conn.execute("SELECT * FROM mail").fetchall()
    testo_elenco, inclusi = prompt.elenco_mail(righe)
    assert len(testo_elenco.splitlines()) == 1 and inclusi == [m["id"]]
    r = invia(client, auth, m["bozza"]["id"])
    assert r.status_code == 200, r.text
    assert "\n" not in server_gmail.inviati[0]["Subject"]


def test_15_9_riassunti_e_scadenze_senza_riferimenti_inventati(client, auth, finta, servizi, decisore):
    decisore.testo_generato = 'Rossi chiede [#999] l\'offerta. [{"descrizione": "Offerta [#5]", "quando": "2026-10-20"}]'
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    for m in client.get("/api/mail?limite=200", headers=auth).json()["mail"]:
        assert "[#" not in (m["riassunto"] or "")
    for s in client.get("/api/scadenze", headers=auth).json():
        assert "[#" not in s["descrizione"]


def test_p17_errore_inatteso_in_json(app, auth, monkeypatch):
    from app.routes import impostazioni

    def esplode(*a, **kw):
        raise RuntimeError("guasto interno 0xbeef")

    monkeypatch.setattr(impostazioni, "_uscita", esplode)
    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.get("/api/impostazioni", headers=auth)
    assert r.status_code == 500
    assert r.headers["content-type"].startswith("application/json")
    assert r.json() == {"errore": "Sol ha avuto un problema interno. Riprova tra poco.", "codice": "errore_interno"}


def test_p8_mail_con_campi_scritti_male_non_rompono_niente():
    from app.caselle import testo

    casi = [
        b"From: =?utf-8?q?Mario=0A?= <x@esempio.it>\r\nTo: studio@esempio.it\r\nSubject: x\r\n\r\nciao\r\n",
        b"From: x@esempio.it\r\nCc: =?utf-8?q?Ufficio=0D=0A?= <c@esempio.it>\r\nTo: s@esempio.it\r\nSubject: x\r\n\r\nciao\r\n",
        b"From: x@esempio.it\r\nTo: =?utf-8?q?A=0A?= <s@esempio.it>\r\nSubject: x\r\n\r\nciao\r\n",
        b"From: x@esempio.it\r\nDate: Mon, 32 Oct 2026 10:00:00 +0000\r\nSubject: x\r\n\r\nciao\r\n",
        b"From: \"\\\"\r\nSubject: x\r\n\r\nciao\r\n",
        b"From: x@esempio.it\r\nContent-Type: text/plain; charset=x-boh\r\nSubject: x\r\n\r\nciao\r\n",
        b"From: x@esempio.it\r\nSubject: \xff\xfe caratteri rotti\r\n\r\n\xff ciao\r\n",
        (b"From: x@esempio.it\r\nSubject: x\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=XX\r\n\r\n"
         b"--XX\r\nContent-Type: text/plain\r\n\r\nciao\r\n--XX\r\nContent-Type: application/pdf\r\n"
         b"Content-Disposition: attachment; filename=\"=?utf-8?q?a=0Ab.pdf?=\"\r\nContent-Transfer-Encoding: base64\r\n"
         b"\r\nAAAA\r\n--XX--\r\n"),
    ]
    for raw in casi:
        g = testo.pulisci_grezza(testo.da_grezzo(raw, "1"))
        assert "\n" not in g.oggetto and "\n" not in g.mittente_nome
        assert all("\n" not in a["nome"] for a in g.allegati)
        g.oggetto.encode("utf-8")  # salvabile
        g.corpo.encode("utf-8")
