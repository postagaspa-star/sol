from app.caselle import finta as modulo_finta
from conftest import mail_per_oggetto, smista_tutto


def _gruppi(client, auth):
    return {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}


def test_in_prova_non_si_sposta_niente(client, auth, finta, servizi):
    smista_tutto(servizi)
    servizi.lavoro.applica_in_caselle()
    assert modulo_finta.stato(finta["id"]).cartelle == {}
    for m in client.get("/api/mail?limite=200", headers=auth).json()["mail"]:
        assert m["applicata_in_casella"] is False


def test_spenta_la_prova_l_arretrato_si_sposta_solo_se_lo_chiedi(client, auth, finta, servizi):
    smista_tutto(servizi)
    r = client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    assert r.json()["modalita_prova"] is False
    servizi.lavoro.giro_posta()
    cartelle = modulo_finta.stato(finta["id"]).cartelle
    assert cartelle == {}  # contratto 15.2: niente arretrato spostato da solo
    da_applicare = client.get("/api/arretrato", headers=auth).json()["da_applicare"]
    assert da_applicare == 9  # la mail-trappola è da controllare (possibile truffa): non si sposta
    assert client.post("/api/arretrato/applica", json={}, headers=auth).status_code == 422
    r = client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert r.status_code == 202 and r.json() == {"da_applicare": 9}
    assert cartelle["finta-7"] == "Sol/Clienti"  # sopralluogo urgente
    assert cartelle["finta-4"] == "Sol/PEC ed enti"
    assert mail_per_oggetto(client, auth, "quadro elettrico")["applicata_in_casella"] is True
    assert client.get("/api/arretrato", headers=auth).json() == {"da_applicare": 0}


def test_arretrato_in_prova_non_si_applica(client, auth, finta, servizi):
    smista_tutto(servizi)
    r = client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert r.status_code == 409 and r.json()["codice"] == "conflitto"
    assert modulo_finta.stato(finta["id"]).cartelle == {}


def test_spenta_la_prova_le_mail_nuove_si_spostano(client, auth, finta, servizi, orologio):
    smista_tutto(servizi)
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    orologio.avanza(minutes=30)  # arrivano il sollecito di Rossi e la PEC del Comune
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    servizi.lavoro.applica_in_caselle()
    cartelle = modulo_finta.stato(finta["id"]).cartelle
    assert cartelle == {"finta-11": "Sol/Clienti", "finta-12": "Sol/PEC ed enti"}


def test_da_controllare_resta_finche_non_si_conferma(client, auth, finta, servizi, decisore):
    decisore.sicurezza = 0.5
    smista_tutto(servizi)
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    assert "finta-7" not in modulo_finta.stato(finta["id"]).cartelle
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    r = client.post(f"/api/mail/{m['id']}/conferma", headers=auth)
    assert r.json()["smistamento"] == "smistata"
    assert modulo_finta.stato(finta["id"]).cartelle["finta-7"] == "Sol/Clienti"


def test_sposta_in_prova_tocca_solo_sol(client, auth, finta, servizi):
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    r = client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": _gruppi(client, auth)["Fornitori"]}, headers=auth)
    assert r.status_code == 200
    assert r.json()["gruppo"] == "Fornitori"
    assert r.json()["smistamento"] == "corretta"
    assert r.json()["origine_smistamento"] == "utente"
    assert r.json()["applicata_in_casella"] is False
    assert modulo_finta.stato(finta["id"]).cartelle == {}


def test_sposta_fuori_prova_sposta_nella_casella(client, auth, finta, servizi):
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    m = mail_per_oggetto(client, auth, "integrazione pratica")
    r = client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": _gruppi(client, auth)["Clienti"]}, headers=auth)
    assert r.json()["applicata_in_casella"] is True
    assert modulo_finta.stato(finta["id"]).cartelle["finta-4"] == "Sol/Clienti"


def test_sposta_fallito_si_vede_e_non_cambia_niente(client, auth, finta, servizi):
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    modulo_finta.stato(finta["id"]).guasti.add("sposta")
    m = mail_per_oggetto(client, auth, "integrazione pratica")
    r = client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": _gruppi(client, auth)["Clienti"]}, headers=auth)
    assert r.status_code == 502
    assert r.json()["codice"] == "casella_non_raggiungibile"
    assert "spostamento" in r.json()["errore"]
    assert mail_per_oggetto(client, auth, "integrazione pratica")["gruppo"] == "PEC ed enti"


def test_spostamenti_in_sottofondo_falliti_si_riprovano_poco(client, auth, finta, servizi):
    smista_tutto(servizi)
    modulo_finta.stato(finta["id"]).guasti.add("sposta")
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    for _ in range(5):
        servizi.lavoro.applica_in_caselle()
    assert modulo_finta.stato(finta["id"]).cartelle == {}
    modulo_finta.stato(finta["id"]).guasti.clear()


def test_segna_letta_anche_in_prova(client, auth, finta):
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    r = client.post(f"/api/mail/{m['id']}/letta", json={"letta": True}, headers=auth)
    assert r.status_code == 200 and r.json()["letta"] is True
    assert modulo_finta.stato(finta["id"]).lette["finta-7"] is True
    client.post(f"/api/mail/{m['id']}/letta", json={"letta": False}, headers=auth)
    assert modulo_finta.stato(finta["id"]).lette["finta-7"] is False


def test_segna_letta_fallito(client, auth, finta):
    modulo_finta.stato(finta["id"]).guasti.add("letta")
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    r = client.post(f"/api/mail/{m['id']}/letta", json={"letta": True}, headers=auth)
    assert r.status_code == 502
    assert mail_per_oggetto(client, auth, "quadro elettrico")["letta"] is False


def test_nessuna_cancellazione_nei_connettori():
    """Sol non cancella mai mail: l'interfaccia delle caselle non ha niente per farlo."""
    from app.caselle.base import Connettore

    metodi = {n for n in dir(Connettore) if not n.startswith("_")}
    assert metodi == {
        "tipo", "verifica", "nuove", "inviate", "conferma_lettura", "sessione", "sposta", "segna_letta",
        "nuovo_id_messaggio", "invia_risposta", "gia_inviata", "ripulisci_bozza_sol", "chiudi",
    }
    # L'unica cosa che si può togliere è una bozza creata da Sol e non partita (Outlook).
    assert not any(p in n for n in metodi for p in ("cancella", "elimina", "delete", "rimuovi"))
