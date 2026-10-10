from app import smista
from conftest import mail_per_oggetto, smista_tutto


def test_regole_fisse_appena_arrivano(client, auth, finta):
    pec = mail_per_oggetto(client, auth, "integrazione pratica")
    assert pec["gruppo"] == "PEC ed enti"
    assert pec["smistamento"] == "smistata"
    assert pec["origine_smistamento"] == "regola"
    news = mail_per_oggetto(client, auth, "Webinar")
    assert news["gruppo"] == "Newsletter" and news["origine_smistamento"] == "regola"
    cliente = mail_per_oggetto(client, auth, "quadro elettrico")
    assert cliente["smistamento"] == "in_attesa" and cliente["gruppo"] is None


def test_decisore_tre_domande(client, auth, finta, servizi, decisore):
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["gruppo"] == "Clienti"
    assert m["smistamento"] == "smistata"
    assert m["origine_smistamento"] == "decisore"
    assert m["da_rispondere"] is True
    assert m["urgenza"] == "alta"
    assert m["sicurezza"] == 0.93
    assert m["bozza"]["stato"] == "in_preparazione"
    dettaglio = client.get(f"/api/mail/{m['id']}", headers=auth).json()
    assert dettaglio["probabilita"]["gruppo"]["Clienti"] == 0.93
    assert set(dettaglio["probabilita"]["da_rispondere"]) == {"si", "no"}
    assert set(dettaglio["probabilita"]["urgenza"]) == {"alta", "normale", "bassa"}
    # La mail del gruppo deciso da regola ha avuto solo le due domande.
    domande = {tuple(d) for _, d in decisore.chiamate}
    assert ("gruppo", "da_rispondere", "urgenza") in domande
    assert ("da_rispondere", "urgenza") in domande


def test_newsletter_il_decisore_decide_se_rispondere(client, auth, finta, servizi, decisore):
    news = mail_per_oggetto(client, auth, "Webinar")
    # Arrivata: Newsletter è solo la proposta (List-Unsubscribe), aspetta il decisore.
    assert news["gruppo"] == "Newsletter" and news["smistamento"] == "in_attesa"
    smista_tutto(servizi)
    news = mail_per_oggetto(client, auth, "Webinar")
    assert news["smistamento"] == "smistata" and news["da_rispondere"] is False
    domande = [d for stato, d in decisore.chiamate if "Webinar" in stato]
    assert domande == [["da_rispondere", "urgenza"]]


def test_newsletter_decisa_dall_utente_senza_decisore(client, auth, servizi, decisore):
    gruppi = {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}
    client.post("/api/regole", json={"tipo": "dominio", "valore": "newsletter.esempio.it",
                                     "gruppo_id": gruppi["Newsletter"]}, headers=auth)
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    smista_tutto(servizi)
    news = mail_per_oggetto(client, auth, "Webinar")
    assert news["smistamento"] == "smistata" and news["da_rispondere"] is False and news["urgenza"] == "bassa"
    assert not any("Webinar" in stato for stato, _ in decisore.chiamate)


def test_sotto_soglia_da_controllare(client, auth, finta, servizi, decisore):
    decisore.sicurezza = 0.6
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["smistamento"] == "da_controllare"
    assert m["gruppo"] == "Clienti"  # il gruppo proposto c'è
    assert client.get("/api/mail?smistamento=da_controllare", headers=auth).json()["mail"]


def test_soglia_dalle_impostazioni(client, auth, finta, servizi, decisore):
    decisore.sicurezza = 0.6
    client.patch("/api/impostazioni", json={"soglia_sicurezza": 0.5}, headers=auth)
    smista_tutto(servizi)
    assert mail_per_oggetto(client, auth, "quadro elettrico")["smistamento"] == "smistata"


def test_regola_utente_vince_su_quella_fissa(client, auth, servizi, orologio):
    gruppi = {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}
    client.post("/api/regole", json={"tipo": "dominio", "valore": "esempio.it", "gruppo_id": gruppi["Clienti"]}, headers=auth)
    client.post("/api/regole", json={"tipo": "mittente", "valore": "rossi@esempio.it", "gruppo_id": gruppi["Fornitori"]}, headers=auth)
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    news = mail_per_oggetto(client, auth, "Webinar")  # notizie@newsletter.esempio.it: sottodominio
    assert news["gruppo"] == "Clienti" and news["origine_smistamento"] == "regola" and news["smistamento"] == "smistata"
    rossi = mail_per_oggetto(client, auth, "capannone")
    assert rossi["gruppo"] == "Fornitori"


def test_regole_mai_sul_mittente_interno_di_una_pec(client, auth, servizi):
    """Per una PEC vera le regole guardano la busta: il mittente interno lo scrive chi la manda."""
    gruppi = {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}
    client.post("/api/regole", json={"tipo": "dominio", "valore": "comune.esempio.it", "gruppo_id": gruppi["Clienti"]}, headers=auth)
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    pec = mail_per_oggetto(client, auth, "integrazione pratica")
    assert pec["gruppo"] == "PEC ed enti"
    assert pec["pec"] == {"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}


def test_regola_fissa_segue_il_gruppo_rinominato(client, auth):
    gruppi = {g["nome"]: g["id"] for g in client.get("/api/gruppi", headers=auth).json()}
    client.patch(f"/api/gruppi/{gruppi['Newsletter']}", json={"nome": "Pubblicità"}, headers=auth)
    client.post("/api/caselle", json={"tipo": "finta"}, headers=auth)
    assert mail_per_oggetto(client, auth, "Webinar")["gruppo"] == "Pubblicità"


def test_regole_fisse_unitarie():
    assert smista.regola_fissa("protocollo@pec.comune.esempio.it", False, False, "studio@esempio.it") == "pec"
    assert smista.regola_fissa("ufficio@esempio.legalmail.it", False, False, "studio@esempio.it") == "pec"
    assert smista.regola_fissa("bianchi@esempio.it", False, False, "studio@pec.esempio.it") == "pec"
    assert smista.regola_fissa("bianchi@esempio.it", False, True, "studio@esempio.it") == "pec"
    assert smista.regola_fissa("notizie@esempio.it", True, False, "studio@esempio.it") == "newsletter"
    assert smista.regola_fissa("bianchi@specchio.esempio.it", False, False, "studio@esempio.it") is None


def test_decisore_giu_il_resto_continua(client, auth, finta, servizi, decisore):
    decisore.giu = True
    assert servizi.lavoro.passo_decisore() == "guasto"
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["smistamento"] == "in_attesa"
    # Le regole hanno lavorato lo stesso, e lo scrittore pure (riassunti di una riga):
    assert mail_per_oggetto(client, auth, "integrazione pratica")["smistamento"] == "smistata"
    decisore.giu = False
    decisore.genera_giu = False
    smista_tutto(servizi)
    assert mail_per_oggetto(client, auth, "quadro elettrico")["smistamento"] == "smistata"


def test_risposta_non_valida_si_lascia_all_utente(client, auth, finta, servizi, decisore):
    decisore.non_valido = True
    for _ in range(200):
        if servizi.lavoro.passo_decisore() == "vuoto":
            break
    else:
        raise AssertionError("bloccato su una mail")
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["smistamento"] == "da_controllare" and m["gruppo"] == "Altro"
    # La proposta di una regola fissa resta valida.
    assert mail_per_oggetto(client, auth, "Webinar")["smistamento"] == "smistata"


def test_scelta_utente_su_da_rispondere_resta(client, auth, finta, servizi):
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    client.post(f"/api/mail/{m['id']}/rispondere", json={"da_rispondere": False}, headers=auth)
    smista_tutto(servizi)
    m = mail_per_oggetto(client, auth, "quadro elettrico")
    assert m["da_rispondere"] is False
    assert m["smistamento"] == "smistata"
