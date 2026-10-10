"""Dal messaggio (RFC 5322) ai campi che servono a Sol: testo semplice, mittente,
allegati, segnali di newsletter, PEC e risposte automatiche.

Le mail arrivano da chiunque: niente qui deve poter sollevare un'eccezione per colpa di
una mail scritta male (un a-capo codificato nel nome, una data impossibile, un charset
inventato). Le intestazioni si leggono "grezze" e si decodificano a mano, e tutto quello
che finisce in una riga (oggetto, nomi) esce con gli spazi normalizzati.

I testi che vengono dalle mail non contengono mai sequenze `[#numero]` (contratto 15.9):
nei riassunti un `[#id]` è sempre un riferimento messo da Sol, mai uno scritto da altri."""

import base64
import email
import email.header
import email.policy
import email.utils
import html
import quopri
import re
from datetime import datetime, timezone
from email.message import Message
from email.parser import BytesHeaderParser
from html.parser import HTMLParser

from .base import InviataGrezza, MailGrezza

LIMITE_TESTO = 100_000  # caratteri del corpo che Sol tiene per mail
LIMITE_RIGA = 300  # oggetto
LIMITE_NOME = 100  # nomi visualizzati

_SPAZI = re.compile(r"[\s\x00-\x1f\x7f  ]+")
_RIFERIMENTO = re.compile(r"\[\s*#\s*(\d+)\s*\]")
_CONTROLLO = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

_BLOCCHI = {"p", "div", "br", "li", "tr", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "table", "blockquote", "hr"}
_DA_SALTARE = {"script", "style", "head", "title"}
NOMI_BUSTA_PEC = {"daticert.xml", "postacert.eml", "smime.p7s"}


# --- pulizia dei testi ------------------------------------------------------------------


def testo_sicuro(testo) -> str:
    """Una stringa che si può salvare: i byte non UTF-8 delle intestazioni (che il parser
    tiene come "surrogati") diventano caratteri veri o il segno di sostituzione."""
    s = str(testo or "")
    try:
        s.encode("utf-8")
        return s
    except UnicodeEncodeError:
        try:
            return s.encode("utf-8", "surrogateescape").decode("utf-8", "replace")
        except UnicodeEncodeError:
            return s.encode("utf-8", "replace").decode("utf-8")


def senza_riferimenti(testo: str) -> str:
    # "(#812)" e non "#812": così anche "[[#812]]" non diventa un "[#812]" nuovo.
    return _RIFERIMENTO.sub(r"(#\1)", testo or "")


def spazi(testo) -> str:
    return _SPAZI.sub(" ", testo_sicuro(testo)).strip()


def riga(testo, limite: int = LIMITE_RIGA) -> str:
    """Testo di una riga sola (oggetto, nome): niente a-capo, niente [#n], lunghezza massima."""
    return senza_riferimenti(spazi(testo))[:limite].strip()


def pulisci(testo: str) -> str:
    testo = _CONTROLLO.sub("", testo_sicuro(testo))
    righe = [re.sub(r"[ \t]+", " ", r).strip() for r in testo.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    testo = "\n".join(righe)
    return re.sub(r"\n{3,}", "\n\n", testo).strip()


def corpo_sicuro(testo: str) -> str:
    return senza_riferimenti(pulisci(testo))[:LIMITE_TESTO]


_ATTACCO_CITAZIONE = re.compile(r"^(il giorno\b|on\b)", re.IGNORECASE)
_FINE_ATTACCO = re.compile(r"(ha scritto|wrote)\s*:\s*$", re.IGNORECASE)
_MESSAGGIO_ORIGINALE = re.compile(r"^-{2,}\s*(messaggio originale|original message)\s*-{2,}$", re.IGNORECASE)
_DA = re.compile(r"^(da|from)\s*:\s*\S", re.IGNORECASE)
_INVIATO = re.compile(r"^(inviato|sent|data|date)\s*:\s*\S", re.IGNORECASE)


def togli_citazioni(testo: str) -> str:
    """Il testo nuovo di una risposta, senza la mail precedente citata sotto: righe con ">",
    "Il giorno ... ha scritto:", "Da: ... Inviato: ...". Al decisore serve solo la parte nuova
    (contratto 15.14). Se togliendo non resta niente, si tiene il testo com'era."""
    righe = (testo or "").splitlines()
    uscita = []
    for k, r in enumerate(righe):
        s = r.strip()
        if s.startswith(">"):
            continue
        if _ATTACCO_CITAZIONE.match(s) and (_FINE_ATTACCO.search(s) or (k + 1 < len(righe) and _FINE_ATTACCO.search(righe[k + 1].strip()))):
            break
        if _MESSAGGIO_ORIGINALE.match(s):
            break
        if _DA.match(s) and any(_INVIATO.match(x.strip()) for x in righe[k + 1 : k + 4]):
            break
        uscita.append(r)
    nuovo = "\n".join(uscita).strip()
    return nuovo or (testo or "").strip()


def anteprima(testo: str, lunghezza: int = 160) -> str:
    piatto = re.sub(r"\s+", " ", testo or "").strip()
    return piatto if len(piatto) <= lunghezza else piatto[: lunghezza - 1].rstrip() + "…"


def oggetto_risposta(oggetto: str) -> str:
    oggetto = riga(oggetto)
    if re.match(r"^(re|r|ris)\s*:", oggetto, re.IGNORECASE):
        return oggetto
    return f"Re: {oggetto}" if oggetto else "Re:"


class _Estrattore(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parti: list[str] = []
        self._salta = 0

    def handle_starttag(self, tag, attrs):
        if tag in _DA_SALTARE:
            self._salta += 1
        elif tag == "li":
            self.parti.append("\n- ")
        elif tag in _BLOCCHI:
            self.parti.append("\n")

    def handle_endtag(self, tag):
        if tag in _DA_SALTARE:
            self._salta = max(0, self._salta - 1)
        elif tag in _BLOCCHI and tag != "li":
            self.parti.append("\n")

    def handle_data(self, data):
        if not self._salta:
            self.parti.append(data)


def html_a_testo(sorgente: str) -> str:
    p = _Estrattore()
    try:
        p.feed(testo_sicuro(sorgente))
        p.close()
        testo = "".join(p.parti)
    except Exception:
        testo = re.sub(r"<[^>]+>", " ", sorgente or "")
    testo = html.unescape(testo).replace("\xa0", " ")
    return pulisci(testo)


# --- intestazioni -------------------------------------------------------------------------


def _decodifica(grezzo: str) -> str:
    """Decodifica le parole codificate (=?utf-8?q?...?=) senza mai sollevare."""
    try:
        return str(email.header.make_header(email.header.decode_header(grezzo)))
    except Exception:
        return grezzo


def _grezze(msg: Message, nome: str) -> list[str]:
    nome = nome.lower()
    try:
        return [str(v) for k, v in msg.raw_items() if k.lower() == nome]
    except Exception:
        return []


def intestazione(msg: Message, nome: str) -> str:
    valori = _grezze(msg, nome)
    return spazi(_decodifica(valori[0])) if valori else ""


def indirizzi(msg: Message, nome: str) -> list[tuple[str, str]]:
    """[(nome visualizzato, indirizzo)]. Si separa sulla forma grezza (le parole codificate
    non contengono virgole) e si decodifica dopo, nome per nome."""
    coppie = []
    for valore in _grezze(msg, nome):
        valore = re.sub(r"\r?\n[ \t]*", " ", valore)
        try:
            trovate = email.utils.getaddresses([valore])
        except Exception:
            trovate = []
        prima = len(coppie)
        for nome_grezzo, indirizzo in trovate:
            indirizzo = testo_sicuro(indirizzo).strip().lower()
            if not indirizzo or "@" not in indirizzo or any(c.isspace() for c in indirizzo):
                continue
            coppie.append((riga(_decodifica(nome_grezzo), LIMITE_NOME), indirizzo[:254]))
        if len(coppie) == prima:
            # Intestazione scritta male (es. "Per conto di: x <y>" senza virgolette): si
            # prende almeno l'indirizzo tra < >, o il primo che si trova.
            trovato = re.findall(r"<([^<>@\s]+@[^<>\s]+)>", valore) or re.findall(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", valore)
            if trovato:
                coppie.append(("", testo_sicuro(trovato[-1]).strip().lower()[:254]))
    return coppie


def _riferimenti(msg: Message) -> str:
    return " ".join(x for x in (intestazione(msg, "References"), intestazione(msg, "In-Reply-To")) if x).strip()


def _id_messaggio(msg: Message) -> str | None:
    return intestazione(msg, "Message-ID") or None


def _data(msg: Message, ripiego: datetime | None) -> datetime:
    dt = None
    grezza = intestazione(msg, "Date")
    if grezza:
        try:
            dt = email.utils.parsedate_to_datetime(grezza)
        except (TypeError, ValueError, IndexError, OverflowError):
            dt = None
    dt = ripiego or dt or datetime.now(timezone.utc)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def risposta_automatica(msg: Message) -> bool:
    """"Sono in ferie fino al...": non è una risposta vera e non va considerata tale."""
    automatica = intestazione(msg, "Auto-Submitted").lower()
    if automatica and automatica != "no":
        return True
    if _grezze(msg, "X-Autoreply") or _grezze(msg, "X-Autorespond"):
        return True
    return intestazione(msg, "Precedence").lower() in ("auto_reply", "auto-reply")


def newsletter(msg: Message) -> bool:
    return bool(_grezze(msg, "List-Unsubscribe"))


def busta_pec(msg: Message) -> bool:
    return bool(_grezze(msg, "X-Trasporto") or _grezze(msg, "X-Ricevuta"))


# --- corpo e allegati (messaggio intero) -------------------------------------------------------


def _foglie(msg: Message):
    """Le parti finali del messaggio. Un messaggio allegato (message/rfc822) è una foglia:
    non si guarda dentro."""
    try:
        if msg.get_content_maintype() == "multipart" and msg.is_multipart():
            for parte in msg.get_payload():
                yield from _foglie(parte)
            return
    except Exception:
        return
    yield msg


def _nome_file(parte: Message) -> str | None:
    try:
        nome = parte.get_filename()
    except Exception:
        nome = None
    if not nome and parte.get_content_type() == "message/rfc822":
        nome = "messaggio.eml"
    return riga(_decodifica(nome), 200) if nome else None


def _contenuto(parte: Message) -> str:
    try:
        contenuto = parte.get_content()
    except Exception:
        try:
            payload = parte.get_payload(decode=True) or b""
        except Exception:
            payload = b""
        try:
            contenuto = payload.decode(parte.get_content_charset() or "utf-8", errors="replace")
        except (LookupError, TypeError):
            contenuto = payload.decode("utf-8", errors="replace")
    if isinstance(contenuto, bytes):
        contenuto = contenuto.decode("utf-8", errors="replace")
    if not isinstance(contenuto, str):
        return ""
    if parte.get_content_subtype() == "html":
        return html_a_testo(contenuto)
    return pulisci(contenuto)


def _corpo(msg: Message) -> str:
    try:
        parte = msg.get_body(preferencelist=("plain", "html"))
    except Exception:
        parte = None
    if parte is None:
        try:
            if msg.get_content_maintype() == "text":
                parte = msg
        except Exception:
            parte = None
    return _contenuto(parte) if parte is not None else ""


def _allegati(msg: Message, corpo: Message | None = None) -> list[dict]:
    uscita = []
    for parte in _foglie(msg):
        if parte is corpo:
            continue
        nome = _nome_file(parte)
        try:
            disposizione = (parte.get_content_disposition() or "").lower()
        except Exception:
            disposizione = ""
        if not nome and disposizione != "attachment":
            continue
        try:
            payload = parte.get_payload(decode=True)
            if payload is None:
                payload = parte.as_bytes()
        except Exception:
            payload = b""
        uscita.append({"nome": nome or "allegato", "byte": len(payload or b"")})
    return uscita


def _messaggio_interno(msg: Message) -> Message | None:
    """Nella PEC il messaggio vero sta nell'allegato postacert.eml."""
    candidati = [p for p in _foglie(msg) if p.get_content_type() == "message/rfc822"]
    candidati.sort(key=lambda p: (_nome_file(p) or "").lower() != "postacert.eml")
    for parte in candidati:
        try:
            interni = parte.get_payload()
            if isinstance(interni, list) and interni:
                # Alcuni gestori codificano l'allegato in base64 (non previsto per
                # message/rfc822): il parser ne fa un messaggio vuoto, si decodifica a mano.
                codifica = (parte.get("Content-Transfer-Encoding") or "").strip().lower()
                if codifica == "base64":
                    grezzo = base64.b64decode(interni[0].as_bytes(), validate=False)
                    return email.message_from_bytes(grezzo, policy=email.policy.default)
                if codifica == "quoted-printable":
                    return email.message_from_bytes(quopri.decodestring(interni[0].as_bytes()),
                                                    policy=email.policy.default)
                return interni[0]
        except Exception:
            continue
    return None


def _corpo_parte(msg: Message) -> Message | None:
    try:
        return msg.get_body(preferencelist=("plain", "html"))
    except Exception:
        return None


def pec_vera(msg: Message, nomi_allegati) -> bool:
    """PEC vera = busta del gestore (X-Trasporto/X-Ricevuta) CON daticert.xml. Un header
    da solo lo può scrivere chiunque."""
    return busta_pec(msg) and any((n or "").lower() == "daticert.xml" for n in nomi_allegati)


def da_grezzo(raw: bytes, id_esterno: str, filo: str | None = None, letta: bool = False,
              ricevuta_il: datetime | None = None) -> MailGrezza:
    msg = email.message_from_bytes(raw, policy=email.policy.default)
    allegati_busta = _allegati(msg, _corpo_parte(msg))
    pec = pec_vera(msg, [a["nome"] for a in allegati_busta])
    interno = _messaggio_interno(msg) if pec else None
    contenuto = interno if interno is not None else msg
    mittenti_busta = indirizzi(msg, "From")
    mittenti = indirizzi(contenuto, "From") or mittenti_busta
    nome, indirizzo = mittenti[0] if mittenti else ("", "")
    if interno is not None:
        allegati = _allegati(interno, _corpo_parte(interno))
    else:
        allegati = [a for a in allegati_busta if not (pec and a["nome"].lower() in NOMI_BUSTA_PEC)]
    return MailGrezza(
        id_esterno=id_esterno,
        id_messaggio=_id_messaggio(msg),
        filo=filo,
        riferimenti=_riferimenti(msg),
        mittente_nome=nome,
        mittente_indirizzo=indirizzo,
        destinatari=[a for _, a in indirizzi(msg, "To")],
        cc=[a for _, a in indirizzi(msg, "Cc")],
        oggetto=intestazione(contenuto, "Subject") or intestazione(msg, "Subject"),
        corpo=_corpo(contenuto),
        ricevuta_il=_data(msg, ricevuta_il),
        letta=letta,
        allegati=allegati,
        newsletter=newsletter(msg),
        pec=pec,
        pec_busta=mittenti_busta[0][1] if pec and mittenti_busta else None,
        automatica=risposta_automatica(msg),
    )


def inviata_da_grezzo(raw: bytes, id_esterno: str, filo: str | None = None,
                      inviata_il: datetime | None = None) -> InviataGrezza:
    msg = email.message_from_bytes(raw, policy=email.policy.default)
    return InviataGrezza(
        id_esterno=id_esterno,
        id_messaggio=_id_messaggio(msg),
        filo=filo,
        riferimenti=_riferimenti(msg),
        a=[a for _, a in indirizzi(msg, "To")],
        oggetto=intestazione(msg, "Subject"),
        corpo=_corpo(msg),
        inviata_il=_data(msg, inviata_il),
    )


# --- dalle parti scaricate una per una (Gmail con BODYSTRUCTURE) -------------------------------


def intestazioni_da_byte(grezze: bytes) -> Message:
    return BytesHeaderParser(policy=email.policy.default).parsebytes(grezze or b"")


def componi_mail(id_esterno: str, filo: str | None, letta: bool, ricevuta_il: datetime | None,
                 intestazioni: bytes, corpo: str, allegati: list[dict], pec: bool,
                 interno: tuple[bytes, str, list[dict]] | None) -> MailGrezza:
    """La mail da intestazioni, testo (già decodificato) e allegati (nome e dimensione,
    senza averli scaricati). `interno` = (intestazioni, testo, allegati) della mail dentro
    una PEC vera."""
    msg = intestazioni_da_byte(intestazioni)
    mittenti_busta = indirizzi(msg, "From")
    contenuto, testo_vero, allegati_veri = msg, corpo, allegati
    if pec and interno is not None:
        contenuto = intestazioni_da_byte(interno[0])
        testo_vero, allegati_veri = interno[1], interno[2]
    elif pec:
        allegati_veri = [a for a in allegati if a["nome"].lower() not in NOMI_BUSTA_PEC]
    mittenti = indirizzi(contenuto, "From") or mittenti_busta
    nome, indirizzo = mittenti[0] if mittenti else ("", "")
    return MailGrezza(
        id_esterno=id_esterno,
        id_messaggio=_id_messaggio(msg),
        filo=filo,
        riferimenti=_riferimenti(msg),
        mittente_nome=nome,
        mittente_indirizzo=indirizzo,
        destinatari=[a for _, a in indirizzi(msg, "To")],
        cc=[a for _, a in indirizzi(msg, "Cc")],
        oggetto=intestazione(contenuto, "Subject") or intestazione(msg, "Subject"),
        corpo=corpo_sicuro(testo_vero),
        ricevuta_il=_data(msg, ricevuta_il),
        letta=letta,
        allegati=allegati_veri,
        newsletter=newsletter(msg),
        pec=pec,
        pec_busta=mittenti_busta[0][1] if pec and mittenti_busta else None,
        automatica=risposta_automatica(msg),
    )


def componi_inviata(id_esterno: str, filo: str | None, inviata_il: datetime | None, intestazioni: bytes,
                    corpo: str) -> InviataGrezza:
    msg = intestazioni_da_byte(intestazioni)
    return InviataGrezza(
        id_esterno=id_esterno,
        id_messaggio=_id_messaggio(msg),
        filo=filo,
        riferimenti=_riferimenti(msg),
        a=[a for _, a in indirizzi(msg, "To")],
        oggetto=intestazione(msg, "Subject"),
        corpo=corpo_sicuro(corpo),
        inviata_il=_data(msg, inviata_il),
    )


# --- pulizia finale, uguale per tutte le caselle ------------------------------------------------


def _indirizzo(a) -> str:
    a = testo_sicuro(a).strip().lower()
    return a[:254] if a and "@" in a and not any(c.isspace() for c in a) else ""


def pulisci_grezza(g: MailGrezza) -> MailGrezza:
    g.oggetto = riga(g.oggetto)
    g.mittente_nome = riga(g.mittente_nome, LIMITE_NOME)
    g.mittente_indirizzo = _indirizzo(g.mittente_indirizzo)
    g.destinatari = [x for x in (_indirizzo(a) for a in g.destinatari or []) if x]
    g.cc = [x for x in (_indirizzo(a) for a in g.cc or []) if x]
    g.corpo = corpo_sicuro(g.corpo)
    g.riferimenti = spazi(g.riferimenti)[:4000]
    g.id_messaggio = spazi(g.id_messaggio)[:500] or None
    g.pec_busta = _indirizzo(g.pec_busta) or None if g.pec_busta else None
    g.allegati = [
        {"nome": riga(a.get("nome"), 200) or "allegato", "byte": max(0, int(a.get("byte") or 0))}
        for a in (g.allegati or [])
        if isinstance(a, dict)
    ]
    return g


def pulisci_inviata(i: InviataGrezza) -> InviataGrezza:
    i.oggetto = riga(i.oggetto)
    i.a = [x for x in (_indirizzo(a) for a in i.a or []) if x]
    i.corpo = corpo_sicuro(i.corpo)
    i.riferimenti = spazi(i.riferimenti)[:4000]
    i.id_messaggio = spazi(i.id_messaggio)[:500] or None
    return i
