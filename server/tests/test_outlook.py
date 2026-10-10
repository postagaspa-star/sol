from datetime import datetime, timezone

import pytest

from app.caselle import ErroreCasella, Invio, Riferimento
from app.caselle.outlook import AccessoMicrosoft, CasellaOutlook
from finti_server import AccessoFinto, GraphFinto

DAL = datetime(2026, 10, 10, 6, 0, tzinfo=timezone.utc)


@pytest.fixture
def graph():
    return GraphFinto()


def _casella(graph, salvate=None, cursori=None, salva_cursori=None):
    return CasellaOutlook(AccessoFinto(), (salvate.append if salvate is not None else None), graph.trasporto(),
                          cursori=cursori, salva_cursori=salva_cursori)


def test_verifica(graph):
    assert _casella(graph).verifica() == "studio@esempio.it"


def test_nuove_con_pagine_allegati_e_intestazioni(graph):
    graph.mail("m1", oggetto="Offerta", corpo="<p>Ciao <b>Manuel</b></p>", tipo_corpo="html",
               intestazioni=[("References", "<a@esempio.it>"), ("In-Reply-To", "<b@esempio.it>")])
    graph.mail("m2", oggetto="Newsletter", intestazioni=[("List-Unsubscribe", "<mailto:x@esempio.it>")])
    graph.mail("m3", oggetto="Con allegato", allegati=[{"name": "pianta.pdf", "size": 482113}])
    graph.mail("m4", oggetto="Ferie", intestazioni=[("Auto-Submitted", "auto-replied")])
    graph.mail("m5", oggetto="Già vista")
    graph.mail("m6", oggetto="Vecchia", quando="2026-10-09T07:30:00Z")
    mail = list(_casella(graph).nuove(DAL, {"m5"}))
    assert [m.id_esterno for m in mail] == ["m1", "m2", "m3", "m4"]
    m1 = mail[0]
    assert m1.corpo == "Ciao Manuel"
    assert m1.mittente_indirizzo == "rossi@esempio.it" and m1.mittente_nome == "Mario Rossi"
    assert m1.filo == "conv-1" and m1.id_messaggio == "<m1@esempio.it>"
    assert "<a@esempio.it>" in m1.riferimenti and "<b@esempio.it>" in m1.riferimenti
    assert m1.ricevuta_il == datetime(2026, 10, 10, 7, 30, tzinfo=timezone.utc)
    assert mail[1].newsletter is True and mail[0].newsletter is False
    assert mail[2].allegati == [{"nome": "pianta.pdf", "byte": 482113}]
    assert mail[3].automatica is True and mail[0].automatica is False
    # Elenco a pagine (solo id e data) seguendo nextLink, corpo solo per le nuove.
    assert graph.richieste.count(("GET", "/me/mailFolders/inbox/messages/delta")) == 3
    corpi = [p for m, p in graph.richieste if m == "GET" and p.startswith("/me/messages/") and p.count("/") == 3]
    assert sorted(corpi) == ["/me/messages/m1", "/me/messages/m2", "/me/messages/m3", "/me/messages/m4"]


def test_dal_secondo_giro_solo_le_novita(graph):
    for i in range(300):
        graph.mail(f"o{i}", oggetto=f"Mail {i}", quando="2026-10-10T08:00:00Z", corpo="x" * 2000)
    graph.pagina = 50
    salvati = {}
    c = _casella(graph, salva_cursori=salvati.update)
    assert len(list(c.nuove(DAL, set()))) == 300
    c.conferma_lettura()
    assert "inbox" in salvati
    graph.richieste.clear()
    graph.mail("nuova", oggetto="Nuova", quando="2026-10-10T08:05:00Z")
    c2 = _casella(graph, cursori=salvati, salva_cursori=salvati.update)
    viste = {f"o{i}" for i in range(300)}
    nuove = list(c2.nuove(DAL, viste))
    assert [m.id_esterno for m in nuove] == ["nuova"]
    # Una sola pagina di cambiamenti e un solo corpo: niente riscaricamento delle 300.
    assert graph.richieste.count(("GET", "/me/mailFolders/inbox/messages/delta")) == 1
    assert ("GET", "/me/mailFolders/inbox/messages") not in graph.richieste
    assert [p for m, p in graph.richieste if p.startswith("/me/messages/")] == ["/me/messages/nuova"]


def test_punto_di_ripresa_salvato_solo_dopo_la_conferma(graph):
    graph.mail("m1")
    salvati = {}
    c = _casella(graph, salva_cursori=salvati.update)
    list(c.nuove(DAL, set()))
    assert salvati == {}  # se il server cade adesso, la prossima volta si rilegge
    c.conferma_lettura()
    assert set(salvati) == {"inbox"}


def test_punto_di_ripresa_scaduto_si_riparte(graph):
    graph.mail("m1")
    c = _casella(graph, cursori={"inbox": "https://graph.microsoft.com/v1.0/me/scaduto"})
    assert [m.id_esterno for m in c.nuove(DAL, set())] == ["m1"]


def test_delta_non_accettata_si_elencano_gli_id(graph):
    graph.mail("m1")
    graph.delta_rifiutata = True
    assert [m.id_esterno for m in _casella(graph).nuove(DAL, set())] == ["m1"]


def test_inviate(graph):
    graph.inviata("s1", a="Neri@Esempio.it", quando="2026-10-10T08:00:00Z")
    inv = list(_casella(graph).inviate(DAL, set()))
    assert inv[0].a == ["neri@esempio.it"] and inv[0].filo == "conv-9" and inv[0].corpo == "Mi conferma?"


def test_sposta_crea_le_cartelle_annidate(graph):
    for i in (1, 2, 3):
        graph.mail(f"m{i}")
    c = _casella(graph)
    with c.sessione():
        assert c.sposta(Riferimento("m1"), "Sol/Clienti") is True
        assert c.sposta(Riferimento("m2"), "Sol/Fornitori") is True
        assert c.sposta(Riferimento("m3"), "Sol/Clienti") is True
    nomi = {(v["nome"], v["genitore"]) for v in graph.cartelle.values()}
    assert nomi == {("Sol", None), ("Clienti", "cartella-1"), ("Fornitori", "cartella-1")}
    assert graph.spostate == {"m1": "cartella-2", "m2": "cartella-3", "m3": "cartella-2"}


def test_sposta_solo_da_posta_in_arrivo(graph):
    graph.mail("m1")
    graph.mail("m2")
    graph.dove["m1"] = "cartella-archivio"  # l'utente l'ha archiviata da Outlook
    c = _casella(graph)
    assert c.sposta(Riferimento("m1"), "Sol/Clienti") is False
    assert "m1" not in graph.spostate
    assert c.sposta(Riferimento("non-esiste"), "Sol/Clienti") is False  # eliminata davvero
    # Da una cartella di Sol a un'altra di Sol sì (correzione dell'utente).
    assert c.sposta(Riferimento("m2"), "Sol/Clienti") is True
    assert c.sposta(Riferimento("m2"), "Sol/Fornitori", da_cartella="Sol/Clienti") is True
    assert graph.cartelle[graph.dove["m2"]]["nome"] == "Fornitori"


def test_nome_con_apostrofo(graph):
    graph.mail("m1")
    _casella(graph).sposta(Riferimento("m1"), "Sol/Dell'Orto")
    assert {v["nome"] for v in graph.cartelle.values()} == {"Sol", "Dell'Orto"}


def test_segna_letta(graph):
    graph.mail("m1")
    _casella(graph).segna_letta(Riferimento("m1"), True)
    assert graph.posta[0]["isRead"] is True


def test_invia_risposta_e_controllo_in_posta_inviata(graph):
    graph.mail("m1")
    salvati = []
    c = _casella(graph)
    c.invia_risposta(Riferimento("m1"), ["rossi@esempio.it"], [], "Re: Offerta", "Va bene.",
                     Invio(None, None, lambda idm, est: salvati.append((idm, est))))
    assert salvati == [("<bozza-1@esempio.it>", "bozza-1")]  # salvati PRIMA di spedire
    assert graph.spedite[0]["risponde_a"] == "m1"
    assert graph.spedite[0]["subject"] == "Re: Offerta"
    assert graph.spedite[0]["body"] == {"contentType": "Text", "content": "Va bene."}
    assert graph.bozze == {}
    assert c.gia_inviata("<bozza-1@esempio.it>") is True
    assert c.gia_inviata("<altra@esempio.it>") is False


def test_invio_fallito_di_sicuro_toglie_la_bozza(graph):
    graph.mail("m1")
    graph.codice_invio = 400
    salvati = []
    with pytest.raises(ErroreCasella) as e:
        _casella(graph).invia_risposta(Riferimento("m1"), ["rossi@esempio.it"], [], "Re: x", "y",
                                       Invio(None, None, lambda idm, est: salvati.append((idm, est))))
    assert e.value.ambiguo is False
    assert graph.bozze == {} and graph.cancellate == ["bozza-1"]  # niente orfane in Bozze
    assert salvati[-1] == (None, None)


def test_invio_ambiguo_tiene_la_bozza_e_si_riusa(graph):
    graph.mail("m1")
    graph.codice_invio = 503
    salvati = []
    c = _casella(graph)
    with pytest.raises(ErroreCasella) as e:
        c.invia_risposta(Riferimento("m1"), ["rossi@esempio.it"], [], "Re: x", "y",
                         Invio(None, None, lambda idm, est: salvati.append((idm, est))))
    assert e.value.ambiguo is True and "potrebbe essere partita" in e.value.messaggio
    assert list(graph.bozze) == ["bozza-1"]
    graph.codice_invio = None
    c.invia_risposta(Riferimento("m1"), ["rossi@esempio.it"], [], "Re: x", "testo nuovo",
                     Invio("<bozza-1@esempio.it>", "bozza-1", lambda idm, est: salvati.append((idm, est))))
    assert graph.richieste.count(("POST", "/me/messages/m1/createReply")) == 1  # la stessa bozza
    assert graph.spedite[0]["body"]["content"] == "testo nuovo"


def test_ripulisci_tocca_solo_le_bozze(graph):
    graph.mail("m1")
    c = _casella(graph)
    c.ripulisci_bozza_sol("m1")  # una mail vera: non si tocca
    assert graph.cancellate == [] and len(graph.posta) == 1


def test_errori_leggibili(graph):
    graph.codice_forzato = 401
    with pytest.raises(ErroreCasella) as e:
        _casella(graph).verifica()
    assert e.value.tipo == "credenziali" and "Ricollega" in e.value.messaggio
    graph.codice_forzato = 503
    with pytest.raises(ErroreCasella) as e:
        list(_casella(graph).nuove(DAL, set()))
    assert e.value.tipo == "rete"
    graph.codice_forzato = None
    with pytest.raises(ErroreCasella) as e:
        _casella(graph).segna_letta(Riferimento("non-esiste"), True)
    assert "Non trovo più" in e.value.messaggio


def test_token_rinnovato_viene_salvato(graph):
    salvate = []
    _casella(graph, salvate).verifica()
    assert salvate == ["-rinnovata"]


# --- msal ------------------------------------------------------------------------------------


class _AppFinta:
    def __init__(self, risultato=None, eccezione=None, account=True):
        self.risultato, self.eccezione, self.account = risultato, eccezione, account

    def get_accounts(self):
        return [{"username": "studio@esempio.it"}] if self.account else []

    def acquire_token_silent_with_error(self, permessi, account):
        if self.eccezione:
            raise self.eccezione
        return self.risultato


def _accesso(app_finta) -> AccessoMicrosoft:
    a = object.__new__(AccessoMicrosoft)
    a._app = app_finta
    return a


@pytest.mark.parametrize("risultato, tipo", [
    (None, "credenziali"),
    ({"error": "invalid_grant", "error_description": "AADSTS50173"}, "credenziali"),
    ({"error": "interaction_required"}, "credenziali"),
    ({"error": "temporarily_unavailable"}, "rete"),
    ({"error": "server_error"}, "rete"),
])
def test_token_distingue_revoca_da_problemi_passeggeri(risultato, tipo):
    with pytest.raises(ErroreCasella) as e:
        _accesso(_AppFinta(risultato)).token()
    assert e.value.tipo == tipo


def test_token_rete_giu_e_passeggero():
    with pytest.raises(ErroreCasella) as e:
        _accesso(_AppFinta(eccezione=ConnectionError("giù"))).token()
    assert e.value.tipo == "rete"
    assert _accesso(_AppFinta({"access_token": "abc"})).token() == "abc"
    with pytest.raises(ErroreCasella) as e:
        _accesso(_AppFinta(account=False)).token()
    assert e.value.tipo == "credenziali"


def test_msal_con_timeout(monkeypatch):
    import msal

    visti = {}

    class AppCatturata:
        def __init__(self, client_id, **kw):
            visti.update(kw)

    monkeypatch.setattr(msal, "PublicClientApplication", AppCatturata)
    AccessoMicrosoft("id-app", "organizations")
    assert visti["timeout"] == 30
