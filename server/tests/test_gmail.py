import smtplib
import tracemalloc
from datetime import datetime, timedelta, timezone

import pytest

import finti_server
from app.caselle import ErroreCasella, Invio, Riferimento
from app.caselle import gmail as modulo_gmail
from app.caselle import imap as imap_util
from app.caselle.gmail import CasellaGmail, utf7_imap
from app.caselle.testo import html_a_testo
from finti_server import GmailFinto, messaggio, pec_vera

DAL = datetime(2026, 10, 10, 6, 0, tzinfo=timezone.utc)


@pytest.fixture
def server():
    return GmailFinto()


def _casella(server, password="abcd efgh ijkl mnop"):
    return CasellaGmail("studio@esempio.it", password, imap_factory=server.imap, smtp_factory=server.smtp)


def test_verifica_toglie_gli_spazi_dalla_password(server):
    assert _casella(server).verifica() == "studio@esempio.it"


def test_password_sbagliata(server):
    with pytest.raises(ErroreCasella) as e:
        _casella(server, "sbagliata").verifica()
    assert e.value.tipo == "credenziali"
    assert "Google ha rifiutato la password per le app" in e.value.messaggio


def test_rete_giu(server):
    server.rete_giu = True
    with pytest.raises(ErroreCasella) as e:
        _casella(server).verifica()
    assert e.value.tipo == "rete"


def test_nuove(server):
    server.aggiungi(messaggio(oggetto="Vecchia"), quando=DAL - timedelta(hours=3))
    msgid = server.aggiungi(
        messaggio(oggetto="Offerta capannone", testo="Ci serve l'offerta.", allegati=[("pianta.pdf", b"x" * 1000)],
                  intestazioni={"References": "<a@esempio.it>", "In-Reply-To": "<b@esempio.it>"}),
        quando=DAL + timedelta(hours=1), letta=True, filo="777",
    )
    server.aggiungi(messaggio(oggetto="Archiviata"), etichette=(), quando=DAL + timedelta(hours=1))
    mail = list(_casella(server).nuove(DAL, set()))
    assert len(mail) == 1
    m = mail[0]
    assert m.id_esterno == msgid and m.filo == "777"
    assert m.oggetto == "Offerta capannone"
    assert m.mittente_nome == "Mario Rossi" and m.mittente_indirizzo == "rossi@esempio.it"
    assert m.destinatari == ["studio@esempio.it"]
    assert m.corpo == "Ci serve l'offerta."
    assert m.letta is True
    assert m.ricevuta_il == DAL + timedelta(hours=1)
    # La dimensione è quella che dice BODYSTRUCTURE (la parte codificata), senza scaricarla.
    assert [a["nome"] for a in m.allegati] == ["pianta.pdf"] and m.allegati[0]["byte"] >= 1000
    assert "<a@esempio.it>" in m.riferimenti and "<b@esempio.it>" in m.riferimenti
    assert m.id_messaggio == "<m1@esempio.it>"
    # Già viste: non si riscaricano.
    assert list(_casella(server).nuove(DAL, {msgid})) == []


def test_allegati_mai_scaricati_e_memoria_bassa(server):
    """40 mail con 4 MB di allegato: si scaricano intestazioni, struttura e testo, mai gli allegati."""
    allegato = b"\x00" * (4 * 1024 * 1024)
    for i in range(40):
        server.aggiungi(messaggio(oggetto=f"Foto {i}", id_messaggio=f"<f{i}@esempio.it>", testo="Ecco le foto.",
                                  allegati=[("foto.jpg", allegato)]), quando=DAL + timedelta(minutes=i))
    c = _casella(server)
    tracemalloc.start()
    contate = 0
    for m in c.nuove(DAL, set()):  # una alla volta, come le salva il giro di posta
        contate += 1
        assert m.allegati[0]["nome"] == "foto.jpg" and m.allegati[0]["byte"] > 4_000_000
        assert m.corpo == "Ecco le foto."
    _, picco = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    assert contate == 40
    assert picco < 20 * 1024 * 1024, f"picco {picco / 1e6:.0f} MB"
    assert not any("BODY.PEEK[]" in r for r in server.richieste_fetch)
    assert not any("BODY.PEEK[2]" in r for r in server.richieste_fetch)  # la parte dell'allegato


def test_testo_lunghissimo_tagliato(server):
    server.aggiungi(messaggio(testo="Riga di prova. " * 100_000), quando=DAL + timedelta(minutes=1))
    m = next(_casella(server).nuove(DAL, set()))
    assert len(m.corpo) <= 100_000
    assert any(f"<0.{modulo_gmail.LIMITE_PARTE}>" in r for r in server.richieste_fetch)


def test_html_newsletter_e_risposte_automatiche(server):
    server.aggiungi(
        messaggio(testo="", html="<html><style>p{}</style><body><p>Ciao&nbsp;<b>Manuel</b></p></body></html>",
                  intestazioni={"List-Unsubscribe": "<mailto:via@esempio.it>"}),
        quando=DAL + timedelta(minutes=5),
    )
    server.aggiungi(messaggio(oggetto="Fuori ufficio", testo="Sono in ferie.", id_messaggio="<auto@esempio.it>",
                              intestazioni={"Auto-Submitted": "auto-replied"}), quando=DAL + timedelta(minutes=6))
    news, auto = list(_casella(server).nuove(DAL, set()))
    assert news.newsletter is True and news.automatica is False
    assert news.corpo == ""  # c'è una parte di testo semplice vuota: vince quella
    assert auto.automatica is True


def test_solo_html(server):
    from email.message import EmailMessage

    m = EmailMessage()
    m["From"], m["To"], m["Subject"], m["Message-ID"] = "a@esempio.it", "studio@esempio.it", "Solo html", "<h@esempio.it>"
    m.set_content("<p>Ciao <b>Manuel</b></p><ul><li>uno</li></ul>", subtype="html")
    server.aggiungi(m.as_bytes(), quando=DAL + timedelta(minutes=1))
    assert next(_casella(server).nuove(DAL, set())).corpo == "Ciao Manuel\n\n- uno"


def test_html_a_testo():
    assert html_a_testo("<p>Ciao&nbsp;<b>Manuel</b></p><script>x()</script><ul><li>uno</li><li>due</li></ul>") == "Ciao Manuel\n\n- uno\n- due"


def test_pec_vera_si_apre(server):
    interno = messaggio(da="Comune <protocollo@pec.comune.esempio.it>", oggetto="Integrazione pratica",
                        testo="Serve la relazione.", allegati=[("richiesta.pdf", b"%PDF" * 100)])
    server.aggiungi(pec_vera(interno), quando=DAL + timedelta(minutes=1))
    m = next(_casella(server).nuove(DAL, set()))
    assert m.pec is True and m.pec_busta == "posta-certificata@pec.esempio.it"
    assert m.mittente_indirizzo == "protocollo@pec.comune.esempio.it"
    assert m.oggetto == "Integrazione pratica"
    assert m.corpo == "Serve la relazione."
    assert [a["nome"] for a in m.allegati] == ["richiesta.pdf"]


def test_pec_finta_non_si_apre(server):
    """Un header X-Trasporto e un .eml allegato li può mettere chiunque: senza daticert.xml
    non è una PEC e la busta non si apre (contratto 15.8)."""
    from email.message import EmailMessage

    interno = EmailMessage()
    interno["From"] = "Mario Rossi <rossi@cliente.esempio.it>"
    interno["Subject"] = "Nuove coordinate bancarie"
    interno.set_content("Da oggi i pagamenti vanno su un altro conto.")
    busta = EmailMessage()
    busta["From"] = "promo@altro.esempio.it"
    busta["To"] = "studio@esempio.it"
    busta["Subject"] = "Comunicazione"
    busta["Message-ID"] = "<finta@altro.esempio.it>"
    busta["X-Trasporto"] = "posta-certificata"
    busta.set_content("vedi allegato")
    busta.add_attachment(interno)
    server.aggiungi(busta.as_bytes(), quando=DAL + timedelta(minutes=1))
    m = next(_casella(server).nuove(DAL, set()))
    assert m.pec is False and m.pec_busta is None
    assert m.mittente_indirizzo == "promo@altro.esempio.it" and m.oggetto == "Comunicazione"
    assert m.corpo == "vedi allegato"


def test_un_solo_accesso_per_controllo(server):
    for i in range(5):
        server.aggiungi(messaggio(oggetto=f"Mail {i}", id_messaggio=f"<s{i}@esempio.it>"), quando=DAL + timedelta(minutes=i))
    c = _casella(server)
    with c.sessione():
        assert len(list(c.nuove(DAL, set()))) == 5
        list(c.inviate(DAL, set()))
    assert server.login_imap == 1


def test_mail_illeggibile_si_salta(server, monkeypatch):
    for i in range(3):
        server.aggiungi(messaggio(oggetto=f"Mail {i}", id_messaggio=f"<x{i}@esempio.it>"), quando=DAL + timedelta(minutes=i))
    vera = modulo_gmail.componi_mail

    def esplode_sulla_seconda(msgid, *a, **kw):
        if msgid == server.messaggi[1]["msgid"]:
            raise ValueError("mail scritta male")
        return vera(msgid, *a, **kw)

    monkeypatch.setattr(modulo_gmail, "componi_mail", esplode_sulla_seconda)
    c = _casella(server)
    assert [m.oggetto for m in c.nuove(DAL, set())] == ["Mail 0", "Mail 2"]
    assert c.scartate == [("mail", server.messaggi[1]["msgid"], "ValueError")]


def test_inviate_con_nome_tradotto(server):
    server.aggiungi(messaggio(da="studio@esempio.it", a="Neri <neri@esempio.it>", oggetto="Offerta compressore",
                              testo="Mi conferma?"), etichette=("\\Sent",), quando=DAL + timedelta(hours=2), filo="55")
    inviate = list(_casella(server).inviate(DAL, set()))
    assert len(inviate) == 1
    assert inviate[0].a == ["neri@esempio.it"] and inviate[0].filo == "55"
    assert inviate[0].corpo == "Mi conferma?"


def test_sposta_crea_etichetta_e_toglie_posta_in_arrivo(server):
    msgid = server.aggiungi(messaggio(), quando=DAL)
    c = _casella(server)
    assert c.sposta(Riferimento(msgid), "Sol/Clienti") is True
    m = server.per_msgid(msgid)
    assert m["etichette"] == {"Sol/Clienti"}
    assert server.creazioni == ["Sol/Clienti"]
    # Cambio di gruppo: via la vecchia etichetta (anche se non è più in Posta in arrivo).
    assert c.sposta(Riferimento(msgid), "Sol/Fornitori", da_cartella="Sol/Clienti") is True
    assert m["etichette"] == {"Sol/Fornitori"}


def test_sposta_solo_da_posta_in_arrivo(server):
    archiviata = server.aggiungi(messaggio(id_messaggio="<a@esempio.it>"), etichette=("Lavori 2026",), quando=DAL)
    cestinata = server.aggiungi(messaggio(id_messaggio="<c@esempio.it>"), etichette=("\\Trash",), quando=DAL)
    c = _casella(server)
    assert c.sposta(Riferimento(archiviata), "Sol/Clienti") is False
    assert server.per_msgid(archiviata)["etichette"] == {"Lavori 2026"}
    assert c.sposta(Riferimento(cestinata), "Sol/Clienti") is False
    assert server.per_msgid(cestinata)["etichette"] == {"\\Trash"}


def test_etichetta_con_lettere_accentate(server):
    msgid = server.aggiungi(messaggio(), quando=DAL)
    _casella(server).sposta(Riferimento(msgid), "Sol/Società")
    assert server.per_msgid(msgid)["etichette"] == {"Sol/Societ&AOA-"}
    assert utf7_imap("Sol/Società & Co") == "Sol/Societ&AOA- &- Co"


def test_segna_letta(server):
    msgid = server.aggiungi(messaggio(), quando=DAL)
    _casella(server).segna_letta(Riferimento(msgid), True)
    assert server.per_msgid(msgid)["flags"] == {"\\Seen"}
    _casella(server).segna_letta(Riferimento(msgid), False)
    assert server.per_msgid(msgid)["flags"] == set()
    with pytest.raises(ErroreCasella) as e:
        _casella(server).segna_letta(Riferimento("123"), True)
    assert "Non trovo più" in e.value.messaggio


def test_invia_risposta_nello_stesso_filo(server):
    rif = Riferimento("1", "<m1@esempio.it>", "777", "<a@esempio.it> <m0@esempio.it>")
    _casella(server).invia_risposta(rif, ["rossi@esempio.it"], ["neri@esempio.it"], "Re: Offerta\nseconda riga",
                                    "Va bene.\n\nManuel", Invio("<fisso-1@esempio.it>"))
    inviato = server.inviati[0]
    assert inviato["From"] == "studio@esempio.it"
    assert inviato["To"] == "rossi@esempio.it" and inviato["Cc"] == "neri@esempio.it"
    assert inviato["Subject"] == "Re: Offerta seconda riga"  # un oggetto non va mai a capo
    assert inviato["In-Reply-To"] == "<m1@esempio.it>"
    assert inviato["References"] == "<a@esempio.it> <m0@esempio.it> <m1@esempio.it>"
    assert inviato["Message-ID"] == "<fisso-1@esempio.it>"
    assert inviato.get_content().strip() == "Va bene.\n\nManuel"
    assert _casella(server).gia_inviata("<fisso-1@esempio.it>") is True
    assert _casella(server).gia_inviata("<altro@esempio.it>") is False


def test_invio_ambiguo_e_non_ambiguo(server, monkeypatch):
    originale = finti_server._SessioneSmtp.send_message

    def cade_dopo(self, msg):
        originale(self, msg)
        raise smtplib.SMTPServerDisconnected("connessione chiusa prima del 250")

    monkeypatch.setattr(finti_server._SessioneSmtp, "send_message", cade_dopo)
    with pytest.raises(ErroreCasella) as e:
        _casella(server).invia_risposta(Riferimento("1"), ["a@esempio.it"], [], "Re: x", "y", Invio("<i1@esempio.it>"))
    assert e.value.ambiguo is True and "potrebbe essere partita" in e.value.messaggio

    def rifiuta(self, msg):
        raise smtplib.SMTPRecipientsRefused({"a@esempio.it": (550, b"no")})

    monkeypatch.setattr(finti_server._SessioneSmtp, "send_message", rifiuta)
    with pytest.raises(ErroreCasella) as e:
        _casella(server).invia_risposta(Riferimento("1"), ["a@esempio.it"], [], "Re: x", "y", Invio("<i2@esempio.it>"))
    assert e.value.ambiguo is False


def test_controllo_posta_inviata_non_riuscito(server):
    server.ricerca_giu = True
    with pytest.raises(ErroreCasella):
        _casella(server).gia_inviata("<x@esempio.it>")


def test_invia_password_sbagliata(server):
    with pytest.raises(ErroreCasella) as e:
        _casella(server, "sbagliata").invia_risposta(Riferimento("1"), ["a@esempio.it"], [], "Re: x", "y", Invio(None))
    assert e.value.tipo == "credenziali" and e.value.ambiguo is False


def test_timeout_su_imap_e_smtp(monkeypatch):
    visti = []

    class Finto:
        def __init__(self, *a, **kw):
            visti.append(kw.get("timeout"))
            raise OSError("niente rete nei test")

    monkeypatch.setattr(modulo_gmail.imaplib, "IMAP4_SSL", Finto)
    monkeypatch.setattr(modulo_gmail.smtplib, "SMTP_SSL", Finto)
    c = CasellaGmail("studio@esempio.it", "x")
    with pytest.raises(ErroreCasella):
        c.verifica()
    with pytest.raises(ErroreCasella):
        c.invia_risposta(Riferimento("1"), ["a@esempio.it"], [], "Re: x", "y", Invio(None))
    assert visti == [30, 30]


# --- lettura delle risposte IMAP ------------------------------------------------------------


def test_lettori_imap():
    assert imap_util.leggi_data("10-Oct-2026 09:30:00 +0200") == datetime(2026, 10, 10, 7, 30, tzinfo=timezone.utc)
    assert imap_util.leggi_data(" 1-Jan-2026 00:00:00 -0100") == datetime(2026, 1, 1, 1, 0, tzinfo=timezone.utc)
    assert imap_util.leggi_data("boh") is None
    cartelle = imap_util.leggi_lista([b'(\\HasNoChildren \\Sent) "/" "[Gmail]/Posta inviata"', b'(\\All) "/" Archivio'])
    assert cartelle == [({"\\HasNoChildren", "\\Sent"}, "[Gmail]/Posta inviata"), ({"\\All"}, "Archivio")]
    # FLAGS dopo il letterale (alcuni server lo fanno) e letterali dentro la struttura.
    r = imap_util.leggi_fetch([(b"1 (UID 5 X-GM-MSGID 9 BODY[HEADER] {3}", b"abc"), b" FLAGS (\\Seen))"])[0]
    assert r["UID"] == b"5" and imap_util.lista_atomi(r["FLAGS"]) == {"\\Seen"} and r["BODY[HEADER]"] == b"abc"
    r = imap_util.leggi_fetch([(b'2 (UID 7 BODYSTRUCTURE ("TEXT" "PLAIN" ("NAME" {6}', b'n"o.tx'), b") NIL NIL \"7BIT\" 3 1))"])[0]
    assert imap_util.struttura(r["BODYSTRUCTURE"]).parametri["name"] == 'n"o.tx'


def test_struttura_con_mail_allegata():
    interno = messaggio(oggetto="Dentro", testo="ciao", allegati=[("a.pdf", b"x")])
    grezzo = pec_vera(interno)
    mappa = {}
    import email

    testo_struttura = finti_server.struttura(email.message_from_bytes(grezzo), "", mappa)
    risposta = imap_util.leggi_fetch([f"1 (UID 1 BODYSTRUCTURE {testo_struttura})".encode()])[0]
    radice = imap_util.struttura(risposta["BODYSTRUCTURE"])
    nomi = [imap_util.nome_allegato(p) for p in imap_util.allegati(radice, {"1"})]
    assert nomi == ["daticert.xml", "postacert.eml"]
    eml = imap_util.allegati(radice, {"1"})[1]
    assert eml.numero == "3" and eml.interno is not None
    assert imap_util.testo_principale(eml.interno).numero == "3.1"


def test_decodifica_tagliata_a_meta():
    import base64

    pezzo = base64.b64encode("Città è bella".encode())[:-3]
    assert imap_util.decodifica(pezzo, "base64", "utf-8").startswith("Città è")
    assert imap_util.decodifica(b"caf=C3=A8", "quoted-printable", "utf-8") == "cafè"
    assert imap_util.decodifica(b"ciao", "7bit", "charset-inventato") == "ciao"
