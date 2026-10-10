"""Revisione, punto 3 e contratto 15.2: spegnere la modalità prova non tocca l'arretrato da solo,
e Sol sposta una mail solo se è ancora in Posta in arrivo."""

from datetime import timedelta

from app import db
from app.caselle import finta as modulo_finta
from conftest import ORA_INIZIALE, aggiungi_gmail, mail_per_oggetto, smista_tutto
from finti_server import messaggio


def test_p3_spegnere_la_prova_non_sposta_l_arretrato(client, auth, finta, servizi):
    smista_tutto(servizi)
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    assert servizi.lavoro.applica_in_caselle() == 0
    assert modulo_finta.stato(finta["id"]).cartelle == {}
    assert client.get("/api/arretrato", headers=auth).json() == {"da_applicare": 9}  # la trappola resta da controllare


def test_p3_riaccendere_la_prova_ferma_subito_gli_spostamenti(client, auth, finta, servizi, app):
    smista_tutto(servizi)
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    originale = modulo_finta.CasellaFinta.sposta
    mosse = []

    def sposta_e_riaccendi(self, mail, cartella, da_cartella=None):
        esito = originale(self, mail, cartella, da_cartella)
        mosse.append(mail.id_esterno)
        with db.aperta(app.state.settings.db_path) as conn:  # dal telefono: "ferma, riaccendi la prova!"
            db.salva_impostazione(conn, "modalita_prova", True)
        return esito

    servizi.fabbrica.in_sottofondo = lambda f, *a: None  # lo spostamento lo lanciamo noi qui sotto
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    modulo_finta.CasellaFinta.sposta = sposta_e_riaccendi
    try:
        servizi.lavoro.applica_in_caselle()
    finally:
        modulo_finta.CasellaFinta.sposta = originale
    assert len(mosse) == 1


def test_p3_mail_gia_sistemata_dall_utente_non_si_tocca(client, auth, finta, servizi):
    smista_tutto(servizi)
    stato = modulo_finta.stato(finta["id"])
    stato.cartelle["finta-7"] = "Lavori 2026"  # l'utente l'ha spostata da sé
    stato.cartelle["finta-4"] = "Posta eliminata"  # e questa l'ha eliminata
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert stato.cartelle["finta-7"] == "Lavori 2026" and stato.cartelle["finta-4"] == "Posta eliminata"
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["applicata_in_casella"] is True  # segnata come sistemata, senza toccarla
    assert client.get("/api/arretrato", headers=auth).json() == {"da_applicare": 0}


def test_p3_correzione_dell_utente_non_viene_annullata(client, auth, finta, servizi):
    smista_tutto(servizi)
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    gruppi = {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}
    servizi.fabbrica.in_sottofondo = lambda f, *a: None
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    # Mentre il giro aspetta, l'utente corregge una mail: si sposta subito dove dice lui.
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    client.post(f"/api/mail/{m['id']}/sposta", json={"gruppo_id": gruppi["Fornitori"]}, headers=auth)
    servizi.lavoro.applica_in_caselle()
    assert modulo_finta.stato(finta["id"]).cartelle["finta-7"] == "Sol/Fornitori"
    assert mail_per_oggetto(client, auth, "quadro elettrico")["gruppo"] == "Fornitori"


def test_p3_un_accesso_imap_per_blocco(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    for i in range(30):
        server_gmail.aggiungi(messaggio(oggetto=f"Mail {i}", id_messaggio=f"<n{i}@esempio.it>"),
                              quando=ORA_INIZIALE + timedelta(minutes=1 + i))
    orologio.avanza(minutes=40)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    prima = server_gmail.login_imap
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    assert server_gmail.login_imap == prima  # spegnere la prova non sposta niente
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert server_gmail.login_imap - prima == 1
    assert all("Sol/Clienti" in m["etichette"] and "\\Inbox" not in m["etichette"] for m in server_gmail.messaggi)


def test_p3_gmail_archiviata_dall_utente_non_si_tocca(client, auth, server_gmail, servizi, orologio):
    aggiungi_gmail(client, auth)
    server_gmail.aggiungi(messaggio(oggetto="Offerta?", id_messaggio="<o1@esempio.it>"),
                          quando=ORA_INIZIALE + timedelta(minutes=1))
    orologio.avanza(minutes=5)
    servizi.lavoro.giro_posta()
    smista_tutto(servizi)
    server_gmail.messaggi[0]["etichette"] = {"Lavori 2026"}  # archiviata dal sito di Gmail
    client.patch("/api/impostazioni", json={"modalita_prova": False}, headers=auth)
    client.post("/api/arretrato/applica", json={"conferma": True}, headers=auth)
    assert server_gmail.messaggi[0]["etichette"] == {"Lavori 2026"}
    assert server_gmail.creazioni == []
