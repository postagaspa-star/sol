"""Revisione, punti 6 e 7, contratto 15.7 e 15.11: niente doppioni, niente registro falso,
si invia solo la versione che l'utente ha visto."""

import pytest
from fastapi.testclient import TestClient

from app import db
from app.caselle import finta as modulo_finta
from conftest import collega_finti, invia, mail_per_oggetto, scrivi_tutto, smista_tutto

NON_SO = "Non riesco a capire se la mail è già partita. Guarda in Posta inviata prima di riprovare."
CAMBIATA = "La risposta è cambiata dopo che l'hai controllata: ricontrollala prima di inviarla."


@pytest.fixture
def bozza(client, auth, finta, servizi):
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    return client.get(f"/api/bozze/{m['bozza']['id']}", headers=auth).json()


def _riga(app, bozza_id):
    with db.aperta(app.state.settings.db_path) as conn:
        return conn.execute("SELECT * FROM bozze WHERE id = ?", (bozza_id,)).fetchone()


# --- punto 6 / 15.7 ---------------------------------------------------------------------------


def test_p6_invio_ambiguo_e_riprova_non_manda_due_volte(client, auth, bozza, finta):
    stato = modulo_finta.stato(finta["id"])
    stato.guasti.add("invio_ambiguo")  # la casella l'ha presa, ma la conferma si è persa
    r1 = invia(client, auth, bozza["id"])
    assert r1.status_code == 502 and r1.json()["bozza"]["stato"] == "errore"
    stato.guasti.clear()
    r2 = invia(client, auth, bozza["id"])
    assert r2.status_code == 200, r2.text
    assert r2.json()["gia_inviata"] is True and r2.json()["stato"] == "inviata"
    assert len(stato.invii) == 1
    assert mail_per_oggetto(client, auth, "quadro elettrico")["risposta_data"] is True


def test_p6_se_non_si_puo_controllare_non_si_rimanda(client, auth, bozza, finta):
    stato = modulo_finta.stato(finta["id"])
    stato.guasti.add("invio_ambiguo")
    invia(client, auth, bozza["id"])
    stato.guasti = {"controllo_inviata"}
    r = invia(client, auth, bozza["id"])
    assert r.status_code == 502
    assert r.json() == {"errore": NON_SO, "codice": "casella_non_raggiungibile"}
    assert len(stato.invii) == 1


def test_p6_message_id_fisso_tra_un_tentativo_e_l_altro(client, auth, bozza, finta, app):
    stato = modulo_finta.stato(finta["id"])
    stato.guasti.add("invio")  # fallito di sicuro: non è partita
    assert invia(client, auth, bozza["id"]).status_code == 502
    fisso = _riga(app, bozza["id"])["id_messaggio"]
    assert fisso and fisso.startswith("<") and _riga(app, bozza["id"])["invio_ambiguo"] == 0
    stato.guasti.clear()
    r = invia(client, auth, bozza["id"])
    assert r.status_code == 200 and r.json()["gia_inviata"] is False
    assert stato.invii[0]["id_messaggio"] == fisso


def test_p6_riavvio_tra_invio_e_registrazione(client, auth, bozza, finta, app, ambiente, decisore):
    """Il server cade dopo che la mail è partita ma prima di scriverlo: al riavvio la bozza
    risulta interrotta e al tentativo dopo si trova in Posta inviata."""
    stato = modulo_finta.stato(finta["id"])
    with db.aperta(app.state.settings.db_path) as conn:
        conn.execute("UPDATE bozze SET in_invio_dal = ?, id_messaggio = '<partita@esempio.it>' WHERE id = ?",
                     (db.clock.adesso(), bozza["id"]))
    stato.invii.append({"a": ["laura.bianchi@esempio.it"], "cc": [], "oggetto": "Re: x", "testo": "y",
                        "in_risposta_a": "<finta-7@esempio.it>", "riferimenti": "", "filo": "f-7",
                        "id_messaggio": "<partita@esempio.it>", "il": db.clock.now()})
    from app.main import create_app

    app2 = create_app()
    collega_finti(app2, decisore)
    with TestClient(app2) as c2:
        b = c2.get(f"/api/bozze/{bozza['id']}", headers=auth).json()
        assert b["stato"] == "errore" and "Posta inviata" in b["errore"]
        r = invia(c2, auth, bozza["id"])
        assert r.status_code == 200 and r.json()["gia_inviata"] is True
    assert len(stato.invii) == 1


def test_p6_outlook_fallimento_sicuro_niente_bozze_orfane(client, auth, server_graph, servizi):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    server_graph.mail("o1", oggetto="Preventivo?", quando="2026-10-10T08:00:00Z")
    servizi.lavoro.controlla_casella(cid)
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    bozza_id = client.get("/api/mail", headers=auth).json()["mail"][0]["bozza"]["id"]
    server_graph.codice_invio = 400
    assert invia(client, auth, bozza_id).status_code == 502
    assert server_graph.bozze == {} and len(server_graph.cancellate) == 1
    server_graph.codice_invio = None
    r = invia(client, auth, bozza_id)
    assert r.status_code == 200 and len(server_graph.spedite) == 1 and server_graph.bozze == {}


def test_p6_outlook_ambiguo_riusa_la_stessa_bozza(client, auth, server_graph, servizi):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    server_graph.mail("o1", oggetto="Preventivo?", quando="2026-10-10T08:00:00Z")
    servizi.lavoro.controlla_casella(cid)
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    bozza_id = client.get("/api/mail", headers=auth).json()["mail"][0]["bozza"]["id"]
    server_graph.codice_invio = 503
    assert invia(client, auth, bozza_id).status_code == 502
    assert len(server_graph.bozze) == 1  # tenuta: forse è partita
    server_graph.codice_invio = None
    r = invia(client, auth, bozza_id)
    assert r.status_code == 200 and r.json()["gia_inviata"] is False
    assert server_graph.richieste.count(("POST", "/me/messages/o1/createReply")) == 1
    assert len(server_graph.spedite) == 1


def test_p6_scartare_toglie_la_bozza_di_sol_da_outlook(client, auth, server_graph, servizi):
    cid = client.post("/api/caselle", json={"tipo": "outlook"}, headers=auth).json()["casella"]["id"]
    server_graph.mail("o1", oggetto="Preventivo?", quando="2026-10-10T08:00:00Z")
    servizi.lavoro.controlla_casella(cid)
    smista_tutto(servizi)
    scrivi_tutto(servizi)
    bozza_id = client.get("/api/mail", headers=auth).json()["mail"][0]["bozza"]["id"]
    server_graph.codice_invio = 503
    invia(client, auth, bozza_id)
    client.post(f"/api/bozze/{bozza_id}/scarta", headers=auth)
    assert server_graph.bozze == {} and len(server_graph.cancellate) == 1
    assert len(server_graph.posta) == 1  # la mail vera non si tocca


# --- punto 7 / 15.7 ---------------------------------------------------------------------------


def test_p7_durante_l_invio_niente_modifiche_e_registro_giusto(client, auth, bozza, finta, monkeypatch):
    stato = modulo_finta.stato(finta["id"])
    originale = modulo_finta.CasellaFinta.invia_risposta
    risposte = {}

    def invio_lento(self, *a, **kw):
        # Mentre la casella spedisce, dal PC e dal telefono arrivano altre richieste.
        risposte["put"] = client.put(f"/api/bozze/{bozza['id']}", json={"a": ["neri@esempio.it"], "testo": "Mai partito"},
                                     headers=auth).status_code
        risposte["rifai"] = client.post(f"/api/bozze/{bozza['id']}/rifai", json={"istruzioni": "più breve"},
                                        headers=auth).status_code
        risposte["scarta"] = client.post(f"/api/bozze/{bozza['id']}/scarta", headers=auth).status_code
        risposte["invia"] = invia(client, auth, bozza["id"]).status_code
        return originale(self, *a, **kw)

    monkeypatch.setattr(modulo_finta.CasellaFinta, "invia_risposta", invio_lento)
    r = invia(client, auth, bozza["id"])
    assert risposte == {"put": 409, "rifai": 409, "scarta": 409, "invia": 409}
    assert r.status_code == 200
    b = r.json()
    assert b["stato"] == "inviata" and b["a"] == ["laura.bianchi@esempio.it"] and b["testo"] == bozza["testo"]
    assert len(stato.invii) == 1 and stato.invii[0]["testo"] == bozza["testo"]


# --- 15.11 ----------------------------------------------------------------------------------------


def test_15_11_si_invia_solo_la_versione_vista(client, auth, bozza, finta):
    vista = bozza["versione"]
    # Un salvataggio arriva dopo che l'utente ha visto la conferma.
    client.put(f"/api/bozze/{bozza['id']}", json={"testo": "Testo cambiato altrove"}, headers=auth)
    r = client.post(f"/api/bozze/{bozza['id']}/invia", json={"conferma": True, "versione": vista}, headers=auth)
    assert r.status_code == 409
    assert r.json()["errore"] == CAMBIATA and r.json()["codice"] == "conflitto"
    assert r.json()["bozza"]["versione"] == vista + 1 and r.json()["bozza"]["testo"] == "Testo cambiato altrove"
    assert modulo_finta.stato(finta["id"]).invii == []


def test_15_11_versione_obbligatoria(client, auth, bozza, finta):
    r = client.post(f"/api/bozze/{bozza['id']}/invia", json={"conferma": True}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "versione"
    assert modulo_finta.stato(finta["id"]).invii == []


def test_15_11_la_versione_sale_a_ogni_cambiamento(client, auth, finta, servizi):
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    b = client.get(f"/api/bozze/{m['bozza']['id']}", headers=auth).json()
    assert b["stato"] == "in_preparazione" and b["versione"] == 1
    scrivi_tutto(servizi)  # rifatta dal server
    assert client.get(f"/api/bozze/{b['id']}", headers=auth).json()["versione"] == 2
    assert client.put(f"/api/bozze/{b['id']}", json={"testo": "a mano"}, headers=auth).json()["versione"] == 3
    assert client.post(f"/api/bozze/{b['id']}/rifai", json={"istruzioni": "più breve"}, headers=auth).json()["versione"] == 4
