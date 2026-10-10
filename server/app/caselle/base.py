"""L'interfaccia comune delle caselle: finta, Gmail, Outlook fanno le stesse cose.

Nessun metodo cancella mail ricevute (contratto, sezione 0): si legge, si sposta o
etichetta, si segna letto, si invia una risposta. L'unica cosa che Sol può togliere è
una bozza che ha creato lui stesso in Outlook e che non è partita (`ripulisci_bozza_sol`),
perché non resti orfana nella cartella Bozze dell'utente."""

from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Iterator


class ErroreCasella(Exception):
    """Un problema con la casella vera. `messaggio` è già una frase per l'utente.
    tipo: 'credenziali' (password o accesso rifiutati: serve «Ricollega»), 'rete'
    (problema temporaneo: si riprova da soli), 'altro'.
    ambiguo: l'invio potrebbe essere partito lo stesso (sezione 15.7)."""

    def __init__(self, messaggio: str, tipo: str = "altro", ambiguo: bool = False, status: int | None = None):
        super().__init__(messaggio)
        self.messaggio = messaggio
        self.tipo = tipo
        self.ambiguo = ambiguo
        self.status = status


@dataclass
class MailGrezza:
    id_esterno: str  # id stabile nella casella (Gmail X-GM-MSGID, Outlook id immutabile)
    id_messaggio: str | None  # header Message-ID
    filo: str | None  # Gmail X-GM-THRID, Outlook conversationId
    riferimenti: str  # header References + In-Reply-To
    mittente_nome: str
    mittente_indirizzo: str
    destinatari: list[str]
    cc: list[str]
    oggetto: str
    corpo: str  # testo semplice
    ricevuta_il: datetime
    letta: bool = False
    allegati: list[dict] = field(default_factory=list)  # [{"nome", "byte"}]
    newsletter: bool = False  # c'è l'header List-Unsubscribe
    pec: bool = False  # PEC vera (busta con daticert.xml): mittente e testo sono della mail interna
    pec_busta: str | None = None  # mittente della busta PEC (il gestore)
    automatica: bool = False  # risposta automatica ("sono in ferie"): non conta come risposta


@dataclass
class InviataGrezza:
    id_esterno: str
    id_messaggio: str | None
    filo: str | None
    riferimenti: str
    a: list[str]
    oggetto: str
    corpo: str
    inviata_il: datetime


@dataclass
class Riferimento:
    """Quel che serve per agire su una mail già vista (spostarla, rispondere)."""

    id_esterno: str
    id_messaggio: str | None = None
    filo: str | None = None
    riferimenti: str = ""


@dataclass
class Invio:
    """Lo stato di un invio che deve sopravvivere a un errore o a un riavvio.
    id_messaggio: il Message-ID fisso della bozza (per cercarla in Posta inviata);
    esterno: in Outlook, l'id della bozza creata da Sol nella casella;
    salva(id_messaggio, esterno): li scrive nel database PRIMA di spedire."""

    id_messaggio: str | None
    esterno: str | None = None
    salva: Callable[[str | None, str | None], None] = lambda id_messaggio, esterno: None


class Connettore:
    tipo = "?"

    def __init__(self):
        # (tipo, id_esterno, motivo): mail che non si è riusciti a leggere. Si saltano,
        # si registrano e non si riprovano a ogni giro; le altre arrivano lo stesso.
        self.scartate: list[tuple[str, str, str]] = []

    def verifica(self) -> str:
        """Prova il collegamento; restituisce l'indirizzo della casella. Solleva ErroreCasella."""
        raise NotImplementedError

    def nuove(self, dal: datetime, gia_viste: set[str]) -> Iterator[MailGrezza]:
        """Le mail arrivate in Posta in arrivo da `dal`, escluse quelle già viste, una alla volta."""
        raise NotImplementedError

    def inviate(self, dal: datetime, gia_viste: set[str]) -> Iterator[InviataGrezza]:
        raise NotImplementedError

    def conferma_lettura(self) -> None:
        """Chiamato quando le mail lette sono al sicuro nel database (Outlook salva qui il
        punto da cui riprendere: se il server cade prima, la prossima volta si rilegge)."""

    @contextmanager
    def sessione(self):
        """Un blocco di operazioni con un solo collegamento (un solo accesso IMAP)."""
        yield self

    def sposta(self, mail: Riferimento, cartella: str, da_cartella: str | None = None) -> bool:
        """Sposta la mail nella cartella (Outlook) o le mette l'etichetta (Gmail), creandola
        se manca, ma SOLO se la mail è ancora in Posta in arrivo (o nella cartella dove l'ha
        messa Sol, `da_cartella`). Vero se l'ha spostata; falso se l'utente l'aveva già
        spostata, archiviata o eliminata con un altro programma (e allora non la tocca)."""
        raise NotImplementedError

    def segna_letta(self, mail: Riferimento, letta: bool) -> None:
        raise NotImplementedError

    def nuovo_id_messaggio(self) -> str | None:
        """Il Message-ID da dare alla risposta prima di spedirla (None se lo sceglie la casella)."""
        return None

    def invia_risposta(self, originale: Riferimento, a: list[str], cc: list[str], oggetto: str, testo: str,
                       invio: Invio) -> None:
        """Invia nello stesso filo (In-Reply-To/References) con il Message-ID di `invio`."""
        raise NotImplementedError

    def gia_inviata(self, id_messaggio: str) -> bool:
        """C'è in Posta inviata una mail con questo Message-ID? Solleva ErroreCasella se
        non si riesce a controllare."""
        raise NotImplementedError

    def ripulisci_bozza_sol(self, esterno: str) -> None:
        """Toglie una bozza creata da Sol e non partita (solo Outlook; per gli altri niente)."""

    def chiudi(self) -> None:
        pass
