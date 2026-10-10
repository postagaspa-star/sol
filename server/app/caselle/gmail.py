"""Gmail con IMAP (lettura, etichette, letto) e SMTP (invio), con la password per le app.

Si usano le estensioni di Gmail: X-GM-MSGID (id stabile della mail in tutta la casella),
X-GM-THRID (il filo), X-GM-LABELS (le etichette). "Spostare" in Gmail = mettere
l'etichetta del gruppo e togliere quella di Posta in arrivo, come fa il pulsante
"Sposta in" del sito. Le cartelle speciali (Tutti i messaggi, Posta inviata) hanno nomi
tradotti nella lingua dell'account: si trovano dai loro segni (\\All, \\Sent).

Banda e memoria: una mail non si scarica mai intera. Per ogni mail nuova si chiedono le
intestazioni e la struttura (BODYSTRUCTURE: quali parti ci sono, nome e dimensione degli
allegati), poi solo la parte di testo, al massimo 256 KB. Le mail escono una alla volta:
chi le legge le salva prima di chiedere la successiva."""

import base64
import imaplib
import logging
import re
import smtplib
import ssl
from contextlib import contextmanager
from datetime import datetime, timedelta
from email.message import EmailMessage
from email.utils import formatdate, make_msgid

from . import imap as imap_util
from . import testo
from .base import Connettore, ErroreCasella, InviataGrezza, Invio, MailGrezza, Riferimento
from .testo import componi_inviata, componi_mail

log = logging.getLogger("uvicorn.error")

IMAP_HOST = "imap.gmail.com"
SMTP_HOST = "smtp.gmail.com"
TIMEOUT = 30  # secondi: nessun giro di posta resta appeso per sempre
LIMITE_PARTE = 256 * 1024
BLOCCO_ID = 200

PASSWORD_RIFIUTATA = (
    "Google ha rifiutato la password per le app. Controlla di averla copiata bene "
    "(16 lettere) e che sull'account sia attiva la verifica in due passaggi."
)
NON_RAGGIUNGIBILE = "Non riesco a raggiungere Gmail. Controlla la connessione del server e riprova tra poco."
MAIL_SPARITA = "Non trovo più questa mail nella casella: forse è stata cancellata o spostata a mano."
INVIO_AMBIGUO = (
    "La connessione con Gmail si è interrotta durante l'invio: la mail potrebbe essere partita. "
    "Prima di riprovare Sol controlla in Posta inviata."
)
RISPOSTA_STRANA = "Gmail ha risposto in un modo che Sol non capisce. Riprovo al prossimo controllo."


def utf7_imap(nome: str) -> str:
    """Nomi di cartelle ed etichette in IMAP: UTF-7 modificato (RFC 3501), per le
    lettere accentate dei gruppi ("Società")."""
    risultato, accumulo = [], []

    def scarica():
        if accumulo:
            b = "".join(accumulo).encode("utf-16-be")
            risultato.append("&" + base64.b64encode(b).decode("ascii").rstrip("=").replace("/", ",") + "-")
            accumulo.clear()

    for c in nome:
        if 0x20 <= ord(c) <= 0x7E:
            scarica()
            risultato.append("&-" if c == "&" else c)
        else:
            accumulo.append(c)
    scarica()
    return "".join(risultato)


def tra_virgolette(nome: str) -> str:
    nome = utf7_imap(nome)
    return '"' + nome.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _quota(valore: str) -> str:
    return '"' + valore.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _ok(risposta, frase: str = "Gmail ha risposto con un errore. Riprova tra poco."):
    tipo, dati = risposta
    if tipo != "OK":
        raise ErroreCasella(frase)
    return dati


def _riferimenti_per_risposta(originale: Riferimento) -> str:
    visti = []
    for rif in re.findall(r"<[^>]+>", originale.riferimenti or ""):
        if rif not in visti:
            visti.append(rif)
    if originale.id_messaggio and originale.id_messaggio not in visti:
        visti.append(originale.id_messaggio)
    return " ".join(visti)


class CasellaGmail(Connettore):
    tipo = "gmail"

    def __init__(self, indirizzo: str, password_app: str, imap_factory=None, smtp_factory=None):
        super().__init__()
        self.indirizzo = indirizzo
        # Google mostra la password per le app a gruppi di 4 con gli spazi: si tolgono.
        self._password = "".join((password_app or "").split())
        self._imap_factory = imap_factory or (lambda: imaplib.IMAP4_SSL(IMAP_HOST, 993, timeout=TIMEOUT))
        self._smtp_factory = smtp_factory or (
            lambda: smtplib.SMTP_SSL(SMTP_HOST, 465, timeout=TIMEOUT, context=ssl.create_default_context())
        )
        self._aperta = None
        self._speciali: dict[str, str] = {}
        self._etichette_pronte: set[str] = set()

    # --- collegamento -----------------------------------------------------------------

    @contextmanager
    def _connessione(self):
        try:
            imap = self._imap_factory()
        except (OSError, imaplib.IMAP4.error) as e:
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        try:
            try:
                imap.login(self.indirizzo, self._password)
            except imaplib.IMAP4.abort as e:
                raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
            except imaplib.IMAP4.error as e:
                raise ErroreCasella(PASSWORD_RIFIUTATA, "credenziali") from e
            except OSError as e:
                raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
            imap._sol_selezionata = None
            yield imap
        finally:
            try:
                imap.logout()
            except Exception:
                pass

    @contextmanager
    def _traduci_errori(self):
        try:
            yield
        except ErroreCasella:
            raise
        except imaplib.IMAP4.abort as e:
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        except OSError as e:
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        except imaplib.IMAP4.error as e:
            raise ErroreCasella("Gmail ha rifiutato l'operazione. Riprova tra poco.") from e
        except imap_util.RispostaIllegibile as e:
            raise ErroreCasella(RISPOSTA_STRANA) from e

    @contextmanager
    def sessione(self):
        """Un solo accesso IMAP per tutto il blocco (lettura, spostamenti)."""
        if self._aperta is not None:
            yield self
            return
        with self._connessione() as imap:
            self._aperta = imap
            try:
                yield self
            finally:
                self._aperta = None

    @contextmanager
    def _imap(self):
        if self._aperta is not None:
            with self._traduci_errori():
                yield self._aperta
            return
        with self._connessione() as imap:
            with self._traduci_errori():
                yield imap

    def _smtp(self):
        try:
            smtp = self._smtp_factory()
        except (OSError, smtplib.SMTPException) as e:
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        try:
            smtp.login(self.indirizzo, self._password)
        except smtplib.SMTPAuthenticationError as e:
            self._chiudi_smtp(smtp)
            raise ErroreCasella(PASSWORD_RIFIUTATA, "credenziali") from e
        except (OSError, smtplib.SMTPException) as e:
            self._chiudi_smtp(smtp)
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        return smtp

    @staticmethod
    def _chiudi_smtp(smtp) -> None:
        try:
            smtp.quit()
        except Exception:
            pass

    def verifica(self) -> str:
        with self._imap():
            pass
        self._chiudi_smtp(self._smtp())
        return self.indirizzo

    # --- cartelle -------------------------------------------------------------------------

    def _seleziona(self, imap, nome: str, sola_lettura: bool) -> None:
        chiave = (nome, sola_lettura)
        if getattr(imap, "_sol_selezionata", None) == chiave:
            return
        _ok(imap.select("INBOX" if nome == "INBOX" else tra_virgolette(nome), readonly=sola_lettura))
        imap._sol_selezionata = chiave

    def _cartella_speciale(self, imap, segno: str, ripiego: str) -> str:
        if segno not in self._speciali:
            nome = ripiego
            for segni, trovata in imap_util.leggi_lista(_ok(imap.list())):
                if segno in segni:
                    nome = trovata
                    break
            self._speciali[segno] = nome
        return self._speciali[segno]

    def _assicura_etichetta(self, imap, etichetta: str) -> None:
        if etichetta in self._etichette_pronte:
            return
        tipo, dati = imap.list('""', tra_virgolette(etichetta))
        if not (tipo == "OK" and any(d for d in (dati or []) if d)):
            tipo, dati = imap.create(tra_virgolette(etichetta))
            if tipo != "OK" and b"ALREADYEXISTS" not in b" ".join(d for d in (dati or []) if isinstance(d, bytes)):
                raise ErroreCasella(f"Gmail non mi ha lasciato creare l'etichetta «{etichetta}».")
        self._etichette_pronte.add(etichetta)

    def _uid_da_msgid(self, imap, msgid: str) -> str | None:
        dati = _ok(imap.uid("SEARCH", None, "X-GM-MSGID", msgid))
        uids = (dati[0] or b"").split() if dati else []
        return uids[0].decode() if uids else None

    def _fetch_uno(self, imap, uid: str, voci: str) -> dict:
        risposte = imap_util.leggi_fetch(_ok(imap.uid("FETCH", uid, voci)))
        for r in risposte:
            if imap_util.testo(r.get("UID")) == str(uid):
                return r
        if len(risposte) == 1:
            return risposte[0]
        raise imap_util.RispostaIllegibile(f"nessuna risposta per la mail {uid}")

    # --- lettura ----------------------------------------------------------------------------

    def _candidati(self, imap, dal: datetime, gia_viste: set[str]) -> list[tuple[str, str]]:
        # SINCE guarda solo il giorno (e nel fuso del server di Google): si prende un
        # giorno in più e poi si filtra all'ora esatta con INTERNALDATE.
        dati = _ok(imap.uid("SEARCH", None, "SINCE", imap_util.data_imap(dal - timedelta(days=1))))
        uids = [u.decode() for u in (dati[0] or b"").split()] if dati else []
        coppie = []
        for i in range(0, len(uids), BLOCCO_ID):
            blocco = ",".join(uids[i : i + BLOCCO_ID])
            for r in imap_util.leggi_fetch(_ok(imap.uid("FETCH", blocco, "(UID X-GM-MSGID)"))):
                uid, msgid = imap_util.testo(r.get("UID")), imap_util.testo(r.get("X-GM-MSGID"))
                if uid and msgid and msgid not in gia_viste:
                    coppie.append((uid, msgid))
        return coppie

    def _testo_parte(self, imap, uid: str, parte) -> str:
        if parte is None:
            return ""
        r = self._fetch_uno(imap, uid, f"(UID BODY.PEEK[{parte.numero}]<0.{LIMITE_PARTE}>)")
        grezzo = imap_util.cerca_sezione(r, parte.numero) or b""
        contenuto = imap_util.decodifica(grezzo, parte.codifica, parte.parametri.get("charset"))
        return testo.html_a_testo(contenuto) if parte.sottotipo == "html" else contenuto

    def _struttura(self, r: dict):
        try:
            return imap_util.struttura(r.get("BODYSTRUCTURE"))
        except imap_util.RispostaIllegibile:
            log.warning("Gmail: struttura della mail illeggibile, tengo solo le intestazioni")
            return None

    def _mail(self, imap, uid: str, msgid: str, dal: datetime) -> MailGrezza | None:
        r = self._fetch_uno(
            imap, uid, "(UID X-GM-MSGID X-GM-THRID FLAGS INTERNALDATE RFC822.SIZE BODYSTRUCTURE BODY.PEEK[HEADER])"
        )
        quando = imap_util.leggi_data(r.get("INTERNALDATE"))
        if quando is not None and quando < dal:
            return None
        intestazioni = imap_util.cerca_sezione(r, "HEADER") or b""
        radice = self._struttura(r)
        principale = imap_util.testo_principale(radice) if radice else None
        corpo = self._testo_parte(imap, uid, principale)
        parti = imap_util.allegati(radice, {principale.numero} if principale else set()) if radice else []
        allegati = [{"nome": imap_util.nome_allegato(p), "byte": p.byte} for p in parti]
        pec = testo.pec_vera(testo.intestazioni_da_byte(intestazioni), [a["nome"] for a in allegati])
        interno = None
        if pec:
            eml = sorted(
                (p for p in parti if p.contenuto == "message/rfc822"),
                key=lambda p: (p.nome or "").lower() != "postacert.eml",
            )
            if eml and eml[0].interno is not None:
                busta = eml[0]
                testa = self._fetch_uno(imap, uid, f"(UID BODY.PEEK[{busta.numero}.HEADER])")
                principale_int = imap_util.testo_principale(busta.interno)
                interno = (
                    imap_util.cerca_sezione(testa, f"{busta.numero}.HEADER") or b"",
                    self._testo_parte(imap, uid, principale_int),
                    [
                        {"nome": imap_util.nome_allegato(p), "byte": p.byte}
                        for p in imap_util.allegati(busta.interno, {principale_int.numero} if principale_int else set())
                    ],
                )
        letta = "\\Seen" in imap_util.lista_atomi(r.get("FLAGS"))
        filo = imap_util.testo(r.get("X-GM-THRID")) or None
        return componi_mail(msgid, filo, letta, quando, intestazioni, corpo, allegati, pec, interno)

    def _inviata(self, imap, uid: str, msgid: str, dal: datetime) -> InviataGrezza | None:
        r = self._fetch_uno(imap, uid, "(UID X-GM-MSGID X-GM-THRID INTERNALDATE BODYSTRUCTURE BODY.PEEK[HEADER])")
        quando = imap_util.leggi_data(r.get("INTERNALDATE"))
        if quando is not None and quando < dal:
            return None
        radice = self._struttura(r)
        principale = imap_util.testo_principale(radice) if radice else None
        corpo = self._testo_parte(imap, uid, principale)
        filo = imap_util.testo(r.get("X-GM-THRID")) or None
        return componi_inviata(msgid, filo, quando, imap_util.cerca_sezione(r, "HEADER") or b"", corpo)

    def _scorri(self, imap, dal, gia_viste, leggi, tipo: str):
        for uid, msgid in self._candidati(imap, dal, gia_viste):
            try:
                elemento = leggi(imap, uid, msgid, dal)
            except ErroreCasella:
                raise
            except (imaplib.IMAP4.abort, OSError):
                raise
            except Exception as e:  # una mail scritta male non ferma le altre
                log.warning("Gmail: %s %s saltata (%s)", tipo, msgid, type(e).__name__)
                self.scartate.append((tipo, msgid, type(e).__name__))
                continue
            if elemento is not None:
                yield elemento

    def nuove(self, dal: datetime, gia_viste: set[str]):
        with self._imap() as imap:
            self._seleziona(imap, "INBOX", True)
            yield from self._scorri(imap, dal, gia_viste, self._mail, "mail")

    def inviate(self, dal: datetime, gia_viste: set[str]):
        with self._imap() as imap:
            self._seleziona(imap, self._cartella_speciale(imap, "\\Sent", "[Gmail]/Sent Mail"), True)
            yield from self._scorri(imap, dal, gia_viste, self._inviata, "inviata")

    def gia_inviata(self, id_messaggio: str) -> bool:
        with self._imap() as imap:
            self._seleziona(imap, self._cartella_speciale(imap, "\\Sent", "[Gmail]/Sent Mail"), True)
            dati = _ok(imap.uid("SEARCH", None, "HEADER", "Message-ID", _quota(id_messaggio)))
            return bool(dati and (dati[0] or b"").split())

    # --- azioni -------------------------------------------------------------------------------

    def _in_tutti(self, imap, mail: Riferimento) -> str | None:
        """L'UID nella cartella Tutti i messaggi; None se la mail non c'è più (cestino,
        spam o cancellata): in Gmail il cestino non fa parte di Tutti i messaggi."""
        self._seleziona(imap, self._cartella_speciale(imap, "\\All", "[Gmail]/All Mail"), False)
        return self._uid_da_msgid(imap, mail.id_esterno)

    def sposta(self, mail: Riferimento, cartella: str, da_cartella: str | None = None) -> bool:
        with self._imap() as imap:
            uid = self._in_tutti(imap, mail)
            if uid is None:
                return False
            etichette = imap_util.lista_atomi(self._fetch_uno(imap, uid, "(UID X-GM-LABELS)").get("X-GM-LABELS"))
            in_arrivo = "\\Inbox" in etichette
            nostra = bool(da_cartella) and (utf7_imap(da_cartella) in etichette or da_cartella in etichette)
            if not (in_arrivo or nostra):
                return False  # l'utente l'ha già sistemata altrove: non si tocca
            self._assicura_etichetta(imap, cartella)
            _ok(imap.uid("STORE", uid, "+X-GM-LABELS", f"({tra_virgolette(cartella)})"))
            if nostra and da_cartella != cartella:
                imap.uid("STORE", uid, "-X-GM-LABELS", f"({tra_virgolette(da_cartella)})")
            if in_arrivo:
                _ok(imap.uid("STORE", uid, "-X-GM-LABELS", "(\\Inbox)"))
            return True

    def segna_letta(self, mail: Riferimento, letta: bool) -> None:
        with self._imap() as imap:
            uid = self._in_tutti(imap, mail)
            if uid is None:
                raise ErroreCasella(MAIL_SPARITA)
            _ok(imap.uid("STORE", uid, "+FLAGS" if letta else "-FLAGS", "(\\Seen)"))

    def nuovo_id_messaggio(self) -> str:
        return make_msgid(domain=self.indirizzo.split("@")[-1] or None)

    def invia_risposta(self, originale: Riferimento, a: list[str], cc: list[str], oggetto: str, testo_mail: str,
                       invio: Invio) -> None:
        msg = EmailMessage()
        msg["From"] = self.indirizzo
        msg["To"] = ", ".join(a)
        if cc:
            msg["Cc"] = ", ".join(cc)
        msg["Subject"] = testo.riga(oggetto) or "Re:"
        msg["Date"] = formatdate(localtime=True)
        msg["Message-ID"] = invio.id_messaggio or self.nuovo_id_messaggio()
        if originale.id_messaggio:
            msg["In-Reply-To"] = originale.id_messaggio
            msg["References"] = _riferimenti_per_risposta(originale)
        msg.set_content(testo_mail)
        smtp = self._smtp()  # se si ferma qui, la mail di sicuro non è partita
        try:
            smtp.send_message(msg)
        except (smtplib.SMTPRecipientsRefused, smtplib.SMTPSenderRefused) as e:
            raise ErroreCasella("Gmail ha rifiutato l'indirizzo del destinatario. Controlla che sia scritto bene.") from e
        except smtplib.SMTPDataError as e:
            raise ErroreCasella("Gmail non ha accettato la mail. Riprova tra poco.") from e
        except (smtplib.SMTPException, OSError) as e:
            # Connessione caduta a metà: Gmail può averla presa lo stesso.
            raise ErroreCasella(INVIO_AMBIGUO, "rete", ambiguo=True) from e
        finally:
            self._chiudi_smtp(smtp)
        # Gmail salva da solo una copia in Posta inviata, nello stesso filo.
