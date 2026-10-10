"""Lettura delle risposte IMAP di imaplib (FETCH con BODYSTRUCTURE, letterali {n}).

Serve a Gmail per non scaricare mai una mail intera: prima le intestazioni e la
struttura (BODYSTRUCTURE dice quali parti ci sono, con nome e dimensione degli allegati),
poi solo la parte di testo, e al massimo i primi 256 KB."""

import base64
import binascii
import email.header
import quopri
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from urllib.parse import unquote_to_bytes

MESI = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


class RispostaIllegibile(ValueError):
    pass


# --- token -----------------------------------------------------------------------------------


def _pezzi(dati):
    """imaplib restituisce i letterali come tuple (testo che finisce con {n}, letterale)."""
    for el in dati or []:
        if isinstance(el, tuple):
            yield ("t", bytes(el[0]))
            yield ("l", bytes(el[1]))
        elif isinstance(el, (bytes, bytearray)):
            yield ("t", bytes(el))


class _Lettore:
    def __init__(self, dati):
        self.pezzi = list(_pezzi(dati))
        self.i = 0
        self.pos = 0

    def _testo(self):
        while self.i < len(self.pezzi):
            tipo, valore = self.pezzi[self.i]
            if tipo == "t" and self.pos < len(valore):
                return valore
            if tipo == "l":
                raise RispostaIllegibile("letterale inatteso")
            self.i += 1
            self.pos = 0
        return None

    def token(self):
        """("(" | ")" | "atomo" | "stringa" | "fine", valore)"""
        while True:
            testo = self._testo()
            if testo is None:
                return ("fine", None)
            c = testo[self.pos : self.pos + 1]
            if c in (b" ", b"\r", b"\n"):
                self.pos += 1
                continue
            break
        if c == b"(":
            self.pos += 1
            return ("(", None)
        if c == b")":
            self.pos += 1
            return (")", None)
        if c == b'"':
            self.pos += 1
            uscita = bytearray()
            while self.pos < len(testo):
                ch = testo[self.pos : self.pos + 1]
                if ch == b"\\" and self.pos + 1 < len(testo):
                    uscita += testo[self.pos + 1 : self.pos + 2]
                    self.pos += 2
                    continue
                if ch == b'"':
                    self.pos += 1
                    return ("stringa", bytes(uscita))
                uscita += ch
                self.pos += 1
            raise RispostaIllegibile("stringa non chiusa")
        if c == b"{":
            m = re.match(rb"\{(\d+)\}\s*$", testo[self.pos :])
            if not m:
                raise RispostaIllegibile("letterale scritto male")
            self.i += 1
            self.pos = 0
            if self.i >= len(self.pezzi) or self.pezzi[self.i][0] != "l":
                raise RispostaIllegibile("manca il letterale")
            valore = self.pezzi[self.i][1]
            self.i += 1
            return ("stringa", valore)
        # Atomo: fino a spazio o parentesi; le quadre ([HEADER], [1.2]) si tengono intere.
        inizio = self.pos
        profondita = 0
        while self.pos < len(testo):
            ch = testo[self.pos : self.pos + 1]
            if ch == b"[":
                profondita += 1
            elif ch == b"]":
                profondita = max(0, profondita - 1)
            elif profondita == 0 and ch in (b" ", b"(", b")", b"\r", b"\n"):
                break
            self.pos += 1
        return ("atomo", testo[inizio : self.pos])


def _valore(lettore: _Lettore, tok=None):
    tipo, valore = tok or lettore.token()
    if tipo == "(":
        lista = []
        while True:
            t = lettore.token()
            if t[0] == ")":
                return lista
            if t[0] == "fine":
                raise RispostaIllegibile("lista non chiusa")
            lista.append(_valore(lettore, t))
    if tipo in ("atomo", "stringa"):
        if tipo == "atomo" and valore.upper() == b"NIL":
            return None
        return valore
    raise RispostaIllegibile(f"token inatteso {tipo}")


def leggi_fetch(dati) -> list[dict]:
    """Le risposte di un FETCH → [{"UID": b"5", "BODYSTRUCTURE": [...], "BODY[HEADER]": b"..."}].
    Le chiavi sono in maiuscolo; per BODY[...]<n> la chiave resta come la manda il server."""
    lettore = _Lettore(dati)
    risposte = []
    while True:
        tipo, valore = lettore.token()
        if tipo == "fine":
            return risposte
        if tipo != "atomo" or not valore.isdigit():
            raise RispostaIllegibile("manca il numero del messaggio")
        attributi = _valore(lettore)
        if not isinstance(attributi, list) or len(attributi) % 2:
            raise RispostaIllegibile("attributi scritti male")
        risposta = {}
        for k in range(0, len(attributi), 2):
            chiave = attributi[k]
            if isinstance(chiave, (bytes, bytearray)):
                risposta[bytes(chiave).decode("ascii", "replace").upper()] = attributi[k + 1]
        risposte.append(risposta)


def cerca_sezione(risposta: dict, sezione: str):
    """Il valore di BODY[<sezione>] anche quando il server aggiunge <origine> (fetch parziale)."""
    sezione = sezione.upper()
    for chiave, valore in risposta.items():
        if chiave.startswith("BODY[") and chiave[5:].split("]")[0] == sezione:
            return valore
    return None


def testo(valore) -> str:
    if valore is None:
        return ""
    if isinstance(valore, (bytes, bytearray)):
        return bytes(valore).decode("utf-8", "replace")
    return str(valore)


def leggi_data(valore) -> datetime | None:
    m = re.match(r"\s*(\d{1,2})-(\w{3})-(\d{4}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})", testo(valore))
    if not m or m.group(2) not in MESI:
        return None
    g, mese, anno, hh, mm, ss, segno, oz, om = m.groups()
    scarto = timedelta(hours=int(oz), minutes=int(om)) * (1 if segno == "+" else -1)
    try:
        return datetime(int(anno), MESI.index(mese) + 1, int(g), int(hh), int(mm), int(ss), tzinfo=timezone(scarto))
    except ValueError:
        return None


def lista_atomi(valore) -> set[str]:
    if not isinstance(valore, list):
        return set()
    return {testo(v) for v in valore if v is not None}


# --- BODYSTRUCTURE --------------------------------------------------------------------------------


@dataclass
class Parte:
    numero: str
    tipo: str
    sottotipo: str
    parametri: dict = field(default_factory=dict)
    codifica: str = "7bit"
    byte: int = 0
    disposizione: str = ""
    parametri_disposizione: dict = field(default_factory=dict)
    figli: list = field(default_factory=list)
    interno: "Parte | None" = None  # per message/rfc822: il corpo della mail allegata

    @property
    def contenuto(self) -> str:
        return f"{self.tipo}/{self.sottotipo}"

    @property
    def nome(self) -> str | None:
        return nome_file(self.parametri_disposizione, self.parametri)


def _parametri(valore) -> dict:
    if not isinstance(valore, list):
        return {}
    uscita = {}
    for k in range(0, len(valore) - 1, 2):
        chiave = testo(valore[k]).lower()
        uscita[chiave] = testo(valore[k + 1])
    return uscita


def nome_file(disposizione: dict, parametri: dict) -> str | None:
    for fonte in (disposizione, parametri):
        for base in ("filename", "name"):
            if fonte.get(base + "*"):
                return _rfc2231(fonte[base + "*"])
            pezzi = sorted(
                ((int(m.group(1)), k) for k in fonte if (m := re.fullmatch(rf"{base}\*(\d+)\*?", k))),
            )
            if pezzi:
                return _rfc2231("".join(fonte[k] for _, k in pezzi), codificato=any(k.endswith("*") for _, k in pezzi))
            if fonte.get(base):
                return _parole(fonte[base])
    return None


def _rfc2231(valore: str, codificato: bool = True) -> str:
    try:
        if codificato and valore.count("'") >= 2:
            charset, _, resto = valore.split("'", 2)
            return unquote_to_bytes(resto).decode(charset or "utf-8", "replace")
    except (LookupError, ValueError):
        pass
    return valore


def _parole(valore: str) -> str:
    try:
        return str(email.header.make_header(email.header.decode_header(valore)))
    except Exception:
        return valore


def _intero(valore) -> int:
    try:
        return int(testo(valore))
    except ValueError:
        return 0


def struttura(bs, numero: str = "") -> Parte:
    """Da BODYSTRUCTURE (liste annidate) all'albero delle parti, con i numeri IMAP."""
    if not isinstance(bs, list) or not bs:
        raise RispostaIllegibile("struttura vuota")
    if isinstance(bs[0], list):
        figli_grezzi = []
        k = 0
        while k < len(bs) and isinstance(bs[k], list):
            figli_grezzi.append(bs[k])
            k += 1
        sottotipo = testo(bs[k]).lower() if k < len(bs) else "mixed"
        estensioni = bs[k + 1 :]
        disposizione = estensioni[1] if len(estensioni) > 1 else None
        parte = Parte(numero=numero, tipo="multipart", sottotipo=sottotipo)
        if isinstance(disposizione, list) and disposizione:
            parte.disposizione = testo(disposizione[0]).lower()
            parte.parametri_disposizione = _parametri(disposizione[1] if len(disposizione) > 1 else None)
        for n, figlio in enumerate(figli_grezzi, start=1):
            parte.figli.append(struttura(figlio, f"{numero}.{n}" if numero else str(n)))
        return parte
    tipo = testo(bs[0]).lower()
    sottotipo = testo(bs[1]).lower() if len(bs) > 1 else ""
    parte = Parte(
        numero=numero or "1",
        tipo=tipo,
        sottotipo=sottotipo,
        parametri=_parametri(bs[2] if len(bs) > 2 else None),
        codifica=testo(bs[5]).lower() if len(bs) > 5 else "7bit",
        byte=_intero(bs[6]) if len(bs) > 6 else 0,
    )
    if tipo == "text":
        estensioni = bs[8:]  # dopo le righe
    elif tipo == "message" and sottotipo == "rfc822" and len(bs) > 8:
        estensioni = bs[10:]  # dopo busta, corpo e righe
        interno = bs[8]
        if isinstance(interno, list) and interno:
            # Le parti della mail allegata si numerano sotto il numero del messaggio
            # (2.1, 2.2...); se è di una parte sola, il suo corpo è "2.1".
            if isinstance(interno[0], list):
                parte.interno = struttura(interno, parte.numero)
            else:
                parte.interno = struttura(interno, f"{parte.numero}.1")
    else:
        estensioni = bs[7:]
    disposizione = estensioni[1] if len(estensioni) > 1 else None
    if isinstance(disposizione, list) and disposizione:
        parte.disposizione = testo(disposizione[0]).lower()
        parte.parametri_disposizione = _parametri(disposizione[1] if len(disposizione) > 1 else None)
    return parte


def foglie(parte: Parte):
    """Le parti finali; una mail allegata conta come una foglia (non ci si entra)."""
    if parte.tipo == "multipart":
        for figlio in parte.figli:
            yield from foglie(figlio)
    else:
        yield parte


def testo_principale(radice: Parte) -> Parte | None:
    """Il testo della mail: il primo text/plain che non è un allegato, altrimenti il primo text/html."""
    candidati = [
        p for p in foglie(radice)
        if p.tipo == "text" and p.sottotipo in ("plain", "html") and p.disposizione != "attachment" and not p.nome
    ]
    for sottotipo in ("plain", "html"):
        for p in candidati:
            if p.sottotipo == sottotipo:
                return p
    return None


def allegati(radice: Parte, escluse: set[str] = frozenset()) -> list[Parte]:
    uscita = []
    for p in foglie(radice):
        if p.numero in escluse:
            continue
        if p.disposizione == "attachment" or p.nome or p.contenuto == "message/rfc822":
            uscita.append(p)
        elif p.tipo not in ("text",) and p.disposizione != "inline":
            uscita.append(p)
    return uscita


def nome_allegato(p: Parte) -> str:
    if p.nome:
        return p.nome
    return "messaggio.eml" if p.contenuto == "message/rfc822" else "allegato"


def decodifica(grezzo: bytes, codifica: str, charset: str | None) -> str:
    """Testo di una parte scaricata (anche tagliata a metà: il base64 si taglia al
    multiplo di 4)."""
    grezzo = bytes(grezzo or b"")
    codifica = (codifica or "").lower()
    if codifica == "base64":
        pulito = re.sub(rb"[^A-Za-z0-9+/=]", b"", grezzo)
        pulito = pulito[: len(pulito) - len(pulito) % 4]
        try:
            grezzo = base64.b64decode(pulito)
        except (binascii.Error, ValueError):
            grezzo = b""
    elif codifica == "quoted-printable":
        grezzo = quopri.decodestring(grezzo)
    try:
        return grezzo.decode(charset or "utf-8", "replace")
    except (LookupError, TypeError):
        return grezzo.decode("utf-8", "replace")


def data_imap(dt: datetime) -> str:
    return f"{dt.day:02d}-{MESI[dt.month - 1]}-{dt.year}"


_LISTA = re.compile(rb'\((?P<segni>[^)]*)\) (?P<sep>"(?:[^"\\]|\\.)*"|NIL) (?P<nome>.+)$')


def leggi_lista(righe) -> list[tuple[set[str], str]]:
    cartelle = []
    for riga in righe or []:
        if isinstance(riga, tuple):
            riga = riga[0] + b'"' + riga[1] + b'"'
        if not isinstance(riga, bytes):
            continue
        m = _LISTA.match(riga.strip())
        if not m:
            continue
        nome = m.group("nome").decode("utf-8", "replace").strip()
        if nome.startswith('"') and nome.endswith('"'):
            nome = nome[1:-1].replace('\\"', '"').replace("\\\\", "\\")
        segni = set(m.group("segni").decode("ascii", "replace").split())
        cartelle.append((segni, nome))
    return cartelle
