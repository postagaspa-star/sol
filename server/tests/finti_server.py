"""Server finti per i connettori: Gmail (IMAP + SMTP) e Microsoft Graph. Nessuna rete."""

import email
import email.policy
import imaplib
import json
import re
import smtplib
from datetime import datetime, timezone
from email.message import EmailMessage
from email.utils import format_datetime

import httpx

MESI = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def messaggio(da="Mario Rossi <rossi@esempio.it>", a="studio@esempio.it", oggetto="Offerta", testo="Buongiorno",
              html=None, quando=None, id_messaggio="<m1@esempio.it>", intestazioni=None, allegati=()) -> bytes:
    m = EmailMessage()
    m["From"], m["To"], m["Subject"] = da, a, oggetto
    m["Message-ID"] = id_messaggio
    m["Date"] = format_datetime(quando or datetime(2026, 10, 10, 7, 30, tzinfo=timezone.utc))
    for k, v in (intestazioni or {}).items():
        m[k] = v
    if html is not None:
        m.set_content(testo)
        m.add_alternative(html, subtype="html")
    else:
        m.set_content(testo)
    for nome, dati in allegati:
        m.add_attachment(dati, maintype="application", subtype="octet-stream", filename=nome)
    return m.as_bytes()


def pec_vera(interno: bytes, gestore="posta-certificata@pec.esempio.it", oggetto="POSTA CERTIFICATA: Comunicazione",
             id_messaggio="<pec1@pec.esempio.it>") -> bytes:
    """Una busta PEC come la manda un gestore: testo, daticert.xml, postacert.eml (7bit)."""
    from email.mime.application import MIMEApplication
    from email.mime.message import MIMEMessage
    from email.mime.multipart import MIMEMultipart
    from email.mime.text import MIMEText

    busta = MIMEMultipart("mixed")
    busta["From"] = f'"Per conto di: mittente@pec.comune.esempio.it" <{gestore}>'
    busta["To"] = "studio@esempio.it"
    busta["Subject"] = oggetto
    busta["Message-ID"] = id_messaggio
    busta["Date"] = format_datetime(datetime(2026, 10, 10, 7, 30, tzinfo=timezone.utc))
    busta["X-Trasporto"] = "posta-certificata"
    busta.attach(MIMEText("Messaggio di posta certificata. Il messaggio originale è incluso in allegato.", "plain", "utf-8"))
    dati = MIMEApplication(b"<postacert tipo='posta-certificata'/>", "xml")
    dati.add_header("Content-Disposition", "attachment", filename="daticert.xml")
    busta.attach(dati)
    allegato = MIMEMessage(email.message_from_bytes(interno))
    allegato.add_header("Content-Disposition", "attachment", filename="postacert.eml")
    busta.attach(allegato)
    return busta.as_bytes()


def _internaldate(dt: datetime) -> str:
    return f'{dt.day:02d}-{MESI[dt.month - 1]}-{dt.year} {dt:%H:%M:%S} +0000'


def _senza_virgolette(nome: str) -> str:
    nome = nome.strip()
    if nome.startswith('"') and nome.endswith('"'):
        nome = nome[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    return nome


def _q(valore: str) -> str:
    return '"' + str(valore).replace("\\", "\\\\").replace('"', '\\"') + '"'


def _byte(parte) -> bytes:
    payload = parte.get_payload(decode=False)
    if isinstance(payload, list):
        return b"".join(p.as_bytes() for p in payload)
    if isinstance(payload, bytes):
        return payload
    return (payload or "").encode("utf-8", "surrogateescape")


def _testa(msg) -> bytes:
    grezzo = msg.as_bytes()
    fine = grezzo.find(b"\n\n")
    if fine < 0:
        return grezzo
    return grezzo[: fine + 2]


def _parametri(parte) -> str:
    params = parte.get_params() or []
    coppie = [(k, v) for k, v in params[1:] if v is not None]
    if not coppie:
        return "NIL"
    return "(" + " ".join(f"{_q(k.upper())} {_q(email.utils.collapse_rfc2231_value(v))}" for k, v in coppie) + ")"


def _disposizione(parte) -> str:
    grezza = parte.get("Content-Disposition")
    if not grezza:
        return "NIL"
    tipo = grezza.split(";")[0].strip()
    nome = parte.get_filename()
    params = f"({_q('FILENAME')} {_q(nome)})" if nome else "NIL"
    return f"({_q(tipo.upper())} {params})"


def struttura(parte, numero: str, mappa: dict) -> str:
    """BODYSTRUCTURE come la manda un server IMAP, e mappa numero -> byte della parte."""
    tipo, sotto = parte.get_content_maintype(), parte.get_content_subtype()
    if tipo == "multipart":
        figli = "".join(
            struttura(f, f"{numero}.{n}" if numero else str(n), mappa) for n, f in enumerate(parte.get_payload(), start=1)
        )
        return f"({figli} {_q(sotto.upper())})"
    numero = numero or "1"
    corpo = _byte(parte)
    mappa[numero] = corpo
    cte = (parte.get("Content-Transfer-Encoding") or "7bit").strip().upper()
    comune = f"{_q(tipo.upper())} {_q(sotto.upper())} {_parametri(parte)} NIL NIL {_q(cte)} {len(corpo)}"
    if tipo == "text":
        return f"({comune} {corpo.count(b'\n') + 1} NIL {_disposizione(parte)} NIL)"
    if tipo == "message" and sotto == "rfc822":
        interni = parte.get_payload()
        interno = interni[0] if isinstance(interni, list) and interni else email.message_from_bytes(corpo)
        mappa[f"{numero}.HEADER"] = _testa(interno)
        if interno.get_content_maintype() == "multipart":
            struttura_interna = struttura(interno, numero, mappa)
        else:
            struttura_interna = struttura(interno, f"{numero}.1", mappa)
        mappa[numero] = interno.as_bytes()
        return f"({comune} NIL {struttura_interna} {corpo.count(b'\n') + 1} NIL {_disposizione(parte)} NIL)"
    return f"({comune} NIL {_disposizione(parte)} NIL)"


class GmailFinto:
    def __init__(self, password="abcdefghijklmnop", tutti="[Gmail]/Tutti i messaggi", inviata="[Gmail]/Posta inviata"):
        self.password = password
        self.tutti, self.inviata = tutti, inviata
        self.messaggi: list[dict] = []
        self.etichette: set[str] = set()
        self.creazioni: list[str] = []
        self.inviati: list[EmailMessage] = []
        self.login_imap = 0
        self.rete_giu = False
        self.richieste_fetch: list[str] = []  # le voci chieste, per controllare cosa si scarica
        self.ricerca_giu = False  # per provare il "non riesco a controllare la Posta inviata"

    def aggiungi(self, raw: bytes, etichette=("\\Inbox",), quando=None, letta=False, filo=None) -> str:
        uid = len(self.messaggi) + 1
        msgid = str(1780000000000000000 + uid)
        msg = email.message_from_bytes(raw, policy=email.policy.compat32)
        mappa: dict = {}
        self.messaggi.append({
            "uid": uid, "msgid": msgid, "thrid": filo or str(1790000000000000000 + uid), "raw": raw,
            "etichette": set(etichette), "flags": {"\\Seen"} if letta else set(),
            "quando": quando or datetime(2026, 10, 10, 7, 30, tzinfo=timezone.utc),
            "struttura": struttura(msg, "", mappa), "parti": mappa, "testa": _testa(msg),
            "message_id": (msg.get("Message-ID") or "").strip(),
        })
        return msgid

    def per_msgid(self, msgid: str) -> dict:
        return next(m for m in self.messaggi if m["msgid"] == msgid)

    def imap(self):
        if self.rete_giu:
            raise OSError("rete giù")
        return _SessioneImap(self)

    def smtp(self):
        if self.rete_giu:
            raise OSError("rete giù")
        return _SessioneSmtp(self)


class _SessioneImap:
    def __init__(self, server: GmailFinto):
        self.s = server
        self.cartella: list[dict] = []
        self.dentro = False

    def login(self, utente, password):
        if password != self.s.password:
            raise imaplib.IMAP4.error(b"[AUTHENTICATIONFAILED] Invalid credentials (Failure)")
        self.s.login_imap += 1
        self.dentro = True
        return "OK", [b"Logged in"]

    def logout(self):
        return "BYE", [b""]

    def _righe_lista(self):
        righe = [
            b'(\\HasNoChildren) "/" "INBOX"',
            b'(\\HasChildren \\Noselect) "/" "[Gmail]"',
            f'(\\All \\HasNoChildren) "/" "{self.s.tutti}"'.encode(),
            f'(\\HasNoChildren \\Sent) "/" "{self.s.inviata}"'.encode(),
        ]
        righe += [f'(\\HasNoChildren) "/" "{e}"'.encode() for e in sorted(self.s.etichette)]
        return righe

    def list(self, directory='""', pattern="*"):
        if pattern == "*":
            return "OK", self._righe_lista()
        cercata = _senza_virgolette(pattern)
        trovate = [r for r in self._righe_lista() if r.endswith(f'"{cercata}"'.encode())]
        return "OK", trovate or [None]

    def create(self, nome):
        nome = _senza_virgolette(nome)
        self.s.creazioni.append(nome)
        if nome in self.s.etichette:
            return "NO", [b"[ALREADYEXISTS] Duplicate folder name"]
        self.s.etichette.add(nome)
        return "OK", [b"Success"]

    def select(self, nome, readonly=False):
        nome = _senza_virgolette(nome)
        if nome == "INBOX":
            self.cartella = [m for m in self.s.messaggi if "\\Inbox" in m["etichette"]]
        elif nome == self.s.tutti:
            self.cartella = [m for m in self.s.messaggi if "\\Trash" not in m["etichette"]]
        elif nome == self.s.inviata:
            self.cartella = [m for m in self.s.messaggi if "\\Sent" in m["etichette"]]
        else:
            return "NO", [b"Unknown mailbox"]
        return "OK", [str(len(self.cartella)).encode()]

    def _voce(self, m: dict, voce: str):
        """(testo, letterale o None) per una voce di FETCH."""
        if voce == "UID":
            return f"UID {m['uid']}", None
        if voce == "X-GM-MSGID":
            return f"X-GM-MSGID {m['msgid']}", None
        if voce == "X-GM-THRID":
            return f"X-GM-THRID {m['thrid']}", None
        if voce == "FLAGS":
            return f"FLAGS ({' '.join(sorted(m['flags']))})", None
        if voce == "INTERNALDATE":
            return f'INTERNALDATE "{_internaldate(m["quando"])}"', None
        if voce == "RFC822.SIZE":
            return f"RFC822.SIZE {len(m['raw'])}", None
        if voce == "X-GM-LABELS":
            nomi = " ".join(e if e.startswith("\\") else _q(e) for e in sorted(m["etichette"]))
            return f"X-GM-LABELS ({nomi})", None
        if voce == "BODYSTRUCTURE":
            return f"BODYSTRUCTURE {m['struttura']}", None
        sezione = re.fullmatch(r"BODY\.PEEK\[(.*?)\](?:<(\d+)\.(\d+)>)?", voce)
        if sezione:
            nome, inizio, quanti = sezione.group(1), sezione.group(2), sezione.group(3)
            if nome == "":
                dati = m["raw"]
            elif nome == "HEADER":
                dati = m["testa"]
            else:
                dati = m["parti"].get(nome, b"")
            chiave = f"BODY[{nome}]"
            if inizio is not None:
                dati = dati[int(inizio) : int(inizio) + int(quanti)]
                chiave += f"<{inizio}>"
            return chiave, dati
        raise imaplib.IMAP4.error(f"voce sconosciuta {voce}".encode())

    def uid(self, comando, *argomenti):
        comando = comando.upper()
        if comando == "SEARCH":
            criterio = argomenti[1]
            if criterio == "SINCE":
                g, mese, anno = argomenti[2].split("-")
                dal = datetime(int(anno), MESI.index(mese) + 1, int(g), tzinfo=timezone.utc)
                uids = [m["uid"] for m in self.cartella if m["quando"] >= dal]
            elif criterio == "X-GM-MSGID":
                uids = [m["uid"] for m in self.cartella if m["msgid"] == str(argomenti[2])]
            elif criterio == "HEADER" and argomenti[2].lower() == "message-id":
                if self.s.ricerca_giu:
                    raise OSError("rete giù durante la ricerca")
                cercato = _senza_virgolette(argomenti[3])
                uids = [m["uid"] for m in self.cartella if m["message_id"] == cercato]
            else:
                return "BAD", [b"criterio sconosciuto"]
            return "OK", [" ".join(map(str, uids)).encode()]
        if comando == "FETCH":
            uids = {int(u) for u in str(argomenti[0]).split(",")}
            voci = re.findall(r"BODY\.PEEK\[[^\]]*\](?:<\d+\.\d+>)?|[A-Z0-9.\-]+", argomenti[1].strip("()").upper())
            self.s.richieste_fetch.append(argomenti[1])
            dati = []
            for n, m in enumerate(self.cartella, start=1):
                if m["uid"] not in uids:
                    continue
                testa, letterali = f"{n} (", []
                for k, voce in enumerate(voci):
                    testo, letterale = self._voce(m, voce)
                    spazio = "" if k == 0 else " "
                    if letterale is None:
                        testa += spazio + testo
                    else:
                        letterali.append((testa + spazio + f"{testo} {{{len(letterale)}}}", letterale))
                        testa = ""
                if letterali:
                    for prefisso, letterale in letterali:
                        dati.append((prefisso.encode(), letterale))
                    dati.append((testa + ")").encode())
                else:
                    dati.append((testa + ")").encode())
            return "OK", dati
        if comando == "STORE":
            uid, azione, valore = argomenti
            m = next(x for x in self.s.messaggi if x["uid"] == int(uid))
            nomi = [_senza_virgolette(x) for x in re.findall(r'"(?:[^"\\]|\\.)*"|\\\w+', valore.strip("()"))]
            if azione.endswith("X-GM-LABELS"):
                for nome in nomi:
                    (m["etichette"].add if azione.startswith("+") else m["etichette"].discard)(nome)
            elif azione.endswith("FLAGS"):
                for nome in nomi:
                    (m["flags"].add if azione.startswith("+") else m["flags"].discard)(nome)
            return "OK", [b""]
        return "BAD", [b"comando sconosciuto"]


class _SessioneSmtp:
    def __init__(self, server: GmailFinto):
        self.s = server

    def login(self, utente, password):
        if password != self.s.password:
            raise smtplib.SMTPAuthenticationError(535, b"5.7.8 Username and Password not accepted")

    def send_message(self, msg):
        from app import clock

        self.s.inviati.append(msg)
        # Come Gmail: la mail spedita compare anche in Posta inviata.
        self.s.aggiungi(msg.as_bytes(), etichette=("\\Sent",), quando=clock.now())

    def quit(self):
        pass


# --- Microsoft Graph -----------------------------------------------------------------------


class GraphFinto:
    """Un pezzo di Microsoft Graph in memoria, per httpx.MockTransport."""

    INBOX = "cartella-inbox"
    INVIATA = "cartella-inviata"

    def __init__(self):
        self.posta: list[dict] = []
        self.inviate: list[dict] = []
        self.cartelle: dict[str, dict] = {}  # id -> {nome, genitore}
        self.richieste: list[tuple[str, str]] = []
        self.spostate: dict[str, str] = {}
        self.dove: dict[str, str] = {}  # mail -> cartella (assente = Posta in arrivo)
        self.bozze: dict[str, dict] = {}
        self.spedite: list[dict] = []
        self.cancellate: list[str] = []
        self.allegati: dict[str, list[dict]] = {}
        self.codice_forzato: int | None = None
        self.codice_invio: int | None = None  # per far fallire solo il /send
        self.pagina = 2
        self.contatore = 0
        self.delta_rifiutata = False

    def trasporto(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.gestisci)

    def _segno(self) -> int:
        self.contatore += 1
        return self.contatore

    def mail(self, id_, oggetto="Offerta", da="rossi@esempio.it", nome="Mario Rossi", quando="2026-10-10T07:30:00Z",
             corpo="Buongiorno", tipo_corpo="text", intestazioni=(), allegati=None, conversazione="conv-1"):
        self.posta.append({
            "id": id_, "internetMessageId": f"<{id_}@esempio.it>", "conversationId": conversazione,
            "subject": oggetto, "from": {"emailAddress": {"name": nome, "address": da}},
            "toRecipients": [{"emailAddress": {"address": "studio@esempio.it"}}], "ccRecipients": [],
            "receivedDateTime": quando, "isRead": False, "body": {"contentType": tipo_corpo, "content": corpo},
            "hasAttachments": bool(allegati), "internetMessageHeaders": [{"name": k, "value": v} for k, v in intestazioni],
            "_segno": self._segno(),
        })
        if allegati:
            self.allegati[id_] = allegati

    def inviata(self, id_, a="neri@esempio.it", oggetto="Offerta compressore", quando="2026-10-01T08:00:00Z",
                corpo="Mi conferma?", conversazione="conv-9", id_messaggio=None):
        self.inviate.append({
            "id": id_, "internetMessageId": id_messaggio or f"<{id_}@esempio.it>", "conversationId": conversazione,
            "subject": oggetto, "toRecipients": [{"emailAddress": {"address": a}}], "sentDateTime": quando,
            "receivedDateTime": quando, "body": {"contentType": "text", "content": corpo}, "_segno": self._segno(),
        })

    @staticmethod
    def _pubblico(m: dict) -> dict:
        return {k: v for k, v in m.items() if not k.startswith("_")}

    def _pagine(self, request, elementi, delta_link: str | None):
        top = self.pagina
        salta = int(request.url.params.get("$skip", "0"))
        pezzo = elementi[salta : salta + top]
        corpo = {"value": pezzo}
        if salta + top < len(elementi):
            corpo["@odata.nextLink"] = str(request.url.copy_set_param("$skip", str(salta + top)))
        elif delta_link:
            corpo["@odata.deltaLink"] = delta_link
        return httpx.Response(200, json=corpo)

    def _in_cartella(self, cartella: str) -> list[dict]:
        if cartella == "inbox":
            return [m for m in self.posta if self.dove.get(m["id"], self.INBOX) == self.INBOX]
        return list(self.inviate)

    def gestisci(self, request: httpx.Request) -> httpx.Response:
        metodo, percorso = request.method, request.url.path.removeprefix("/v1.0")
        self.richieste.append((metodo, percorso))
        assert request.headers["authorization"] == "Bearer token-finto"
        assert 'IdType="ImmutableId"' in request.headers.get("prefer", "")
        assert request.extensions.get("timeout", {}).get("read"), "ogni chiamata deve avere un timeout"
        if self.codice_forzato:
            return httpx.Response(self.codice_forzato, json={"error": {"code": "x"}})
        if metodo == "GET" and percorso == "/me":
            return httpx.Response(200, json={"mail": "Studio@Esempio.it", "userPrincipalName": "studio@esempio.it"})
        if metodo == "GET" and percorso == "/me/mailFolders/inbox":
            return httpx.Response(200, json={"id": self.INBOX})
        m = re.fullmatch(r"/me/mailFolders/(inbox|sentitems)/messages/delta", percorso)
        if metodo == "GET" and m:
            cartella = m.group(1)
            if self.delta_rifiutata:
                return httpx.Response(400, json={"error": {"code": "ErrorInvalidRequest"}})
            token = request.url.params.get("$deltatoken")
            elementi = self._in_cartella(cartella)
            if token is None:
                filtro = request.url.params["$filter"]
                assert filtro.startswith("receivedDateTime ge ")
                dal = filtro.removeprefix("receivedDateTime ge ")
                elementi = [x for x in elementi if x["receivedDateTime"] >= dal]
            else:
                elementi = [x for x in elementi if x["_segno"] > int(token)]
            voci = [{"id": x["id"], "receivedDateTime": x["receivedDateTime"]} for x in elementi]
            link = f"https://graph.microsoft.com/v1.0/me/mailFolders/{cartella}/messages/delta?$deltatoken={self.contatore}"
            return self._pagine(request, voci, link)
        if metodo == "GET" and percorso in ("/me/mailFolders/inbox/messages", "/me/mailFolders/sentitems/messages"):
            cartella = percorso.split("/")[3]
            filtro = request.url.params.get("$filter", "")
            if filtro.startswith("internetMessageId eq "):
                cercato = filtro.removeprefix("internetMessageId eq ").strip("'")
                trovate = [{"id": x["id"]} for x in self._in_cartella(cartella) if x["internetMessageId"] == cercato]
                return httpx.Response(200, json={"value": trovate})
            elementi = [{"id": x["id"], "receivedDateTime": x["receivedDateTime"]} for x in self._in_cartella(cartella)]
            return self._pagine(request, elementi, None)
        m = re.fullmatch(r"/me/messages/([^/]+)/attachments", percorso)
        if metodo == "GET" and m:
            return httpx.Response(200, json={"value": self.allegati.get(m.group(1), [])})
        if percorso == "/me/mailFolders" or re.fullmatch(r"/me/mailFolders/[^/]+/childFolders", percorso):
            genitore = None if percorso == "/me/mailFolders" else percorso.split("/")[3]
            if metodo == "GET":
                nome = re.search(r"displayName eq '(.*)'", request.url.params["$filter"]).group(1).replace("''", "'")
                trovate = [{"id": i, "displayName": c["nome"]} for i, c in self.cartelle.items()
                           if c["nome"] == nome and c["genitore"] == genitore]
                return httpx.Response(200, json={"value": trovate})
            if metodo == "POST":
                nuovo = f"cartella-{len(self.cartelle) + 1}"
                self.cartelle[nuovo] = {"nome": json.loads(request.content)["displayName"], "genitore": genitore}
                return httpx.Response(201, json={"id": nuovo})
        m = re.fullmatch(r"/me/messages/([^/]+)/move", percorso)
        if metodo == "POST" and m:
            destinazione = json.loads(request.content)["destinationId"]
            self.spostate[m.group(1)] = destinazione
            self.dove[m.group(1)] = destinazione
            return httpx.Response(201, json={"id": m.group(1)})
        m = re.fullmatch(r"/me/messages/([^/]+)/createReply", percorso)
        if metodo == "POST" and m:
            if not any(x["id"] == m.group(1) for x in self.posta + self.inviate):
                return httpx.Response(404, json={"error": {"code": "ErrorItemNotFound"}})
            bozza = f"bozza-{len(self.bozze) + len(self.spedite) + len(self.cancellate) + 1}"
            self.bozze[bozza] = {"risponde_a": m.group(1), "internetMessageId": f"<{bozza}@esempio.it>"}
            return httpx.Response(201, json={"id": bozza, "internetMessageId": f"<{bozza}@esempio.it>", "isDraft": True})
        m = re.fullmatch(r"/me/messages/([^/]+)/send", percorso)
        if metodo == "POST" and m:
            if self.codice_invio:
                return httpx.Response(self.codice_invio, json={"error": {"code": "x"}})
            bozza = self.bozze.pop(m.group(1))
            self.spedite.append(bozza)
            self.inviata(f"spedita-{len(self.spedite)}", a=(bozza.get("toRecipients") or [{}])[0].get("emailAddress", {}).get("address", ""),
                         oggetto=bozza.get("subject", ""), quando="2026-10-10T08:30:00Z",
                         corpo=(bozza.get("body") or {}).get("content", ""), id_messaggio=bozza["internetMessageId"])
            return httpx.Response(202)
        m = re.fullmatch(r"/me/messages/([^/]+)", percorso)
        if m:
            mid = m.group(1)
            if metodo == "GET":
                if mid in self.bozze:
                    return httpx.Response(200, json={"id": mid, "isDraft": True,
                                                     "internetMessageId": self.bozze[mid]["internetMessageId"]})
                for x in self.posta:
                    if x["id"] == mid:
                        return httpx.Response(200, json={**self._pubblico(x), "parentFolderId": self.dove.get(mid, self.INBOX)})
                for x in self.inviate:
                    if x["id"] == mid:
                        return httpx.Response(200, json={**self._pubblico(x), "parentFolderId": self.INVIATA})
            if metodo == "PATCH":
                dati = json.loads(request.content)
                if mid in self.bozze:
                    self.bozze[mid].update(dati)
                    return httpx.Response(200, json={"id": mid})
                for x in self.posta:
                    if x["id"] == mid:
                        x.update(dati)
                        return httpx.Response(200, json={"id": mid})
            if metodo == "DELETE" and mid in self.bozze:
                del self.bozze[mid]
                self.cancellate.append(mid)
                return httpx.Response(204)
        return httpx.Response(404, json={"error": {"code": "ErrorItemNotFound"}})


class AccessoFinto:
    """Al posto di msal: il codice per l'utente, l'accesso completato, i token."""

    def __init__(self, cache=None, riesce=True):
        self.cache = cache or ""
        self.riesce = riesce
        self.cambiata = False
        self.token_chiesti = 0
        self.errore_token = None  # ErroreCasella da sollevare al prossimo token()

    def avvia(self):
        return {"user_code": "ABCD-EFGH", "verification_uri": "https://microsoft.com/devicelogin", "expires_in": 900}

    def completa(self, flusso):
        if not self.riesce:
            return {"error": "expired_token"}
        self.cache = '{"token": "cache-finta"}'
        return {"access_token": "token-finto"}

    def token(self):
        self.token_chiesti += 1
        if self.errore_token is not None:
            raise self.errore_token
        self.cambiata = True  # come quando msal rinnova il token
        return "token-finto"

    def serializza(self):
        return self.cache

    def cache_cambiata(self):
        if self.cambiata:
            self.cambiata = False
            return self.cache + "-rinnovata"
        return None
