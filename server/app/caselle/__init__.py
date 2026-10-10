"""Dalla riga `caselle` del database al connettore giusto (finta, Gmail, Outlook).

I test sostituiscono i pezzi che parlano con la rete (imap_factory, smtp_factory,
graph_transport, accesso_factory) e il modo di lanciare i lavori in sottofondo."""

import logging
import sqlite3
import threading
from datetime import timedelta

from .. import clock, db
from ..cifratura import Cifrario, ErroreChiave
from .base import Connettore, ErroreCasella, InviataGrezza, Invio, MailGrezza, Riferimento
from .finta import CasellaFinta
from .gmail import CasellaGmail
from .outlook import AccessoMicrosoft, CasellaOutlook

log = logging.getLogger("uvicorn.error")

__all__ = [
    "Connettore", "ErroreCasella", "InviataGrezza", "Invio", "MailGrezza", "Riferimento", "FabbricaConnettori",
]

SEGRETO_ILLEGGIBILE = (
    "Il server non riesce più a leggere le credenziali di questa casella (la chiave del server è "
    "cambiata). Usa «Ricollega» dall'app."
)
ACCOUNT_DIVERSO = "Hai fatto l'accesso con un'altra casella ({trovato}). Ricollega usando {atteso}."
CODICE_SCADUTO = (
    "Il collegamento non è andato a buon fine: il codice è scaduto o l'accesso è stato annullato. "
    "Usa «Ricollega» per riprovare."
)
DURATA_COLLEGAMENTO = timedelta(minutes=15)


class FabbricaConnettori:
    def __init__(self, db_path: str, cifrario: Cifrario, ms_client_id: str | None, ms_tenant: str):
        self.db_path = db_path
        self.cifrario = cifrario
        self.ms_client_id = ms_client_id
        self.ms_tenant = ms_tenant
        self.imap_factory = None
        self.smtp_factory = None
        self.graph_transport = None
        self.accesso_factory = lambda cache: AccessoMicrosoft(self.ms_client_id, self.ms_tenant, cache)
        self.in_sottofondo = lambda funzione, *argomenti: threading.Thread(
            target=funzione, args=argomenti, daemon=True
        ).start()
        # Chiamata quando una casella Outlook finisce il collegamento (sveglia il controllo).
        self.dopo_collegamento = lambda casella_id: None
        # Una sola app msal per casella: ricrearla a ogni operazione rifarebbe ogni volta la
        # scoperta dell'autorità su Internet.
        self._accessi: dict[int, object] = {}
        self._lock = threading.Lock()

    def _segreto(self, casella: sqlite3.Row) -> str | None:
        if not casella["segreto_cifrato"]:
            return None
        try:
            return self.cifrario.decifra(casella["segreto_cifrato"])
        except ErroreChiave as e:
            raise ErroreCasella(SEGRETO_ILLEGGIBILE, "credenziali") from e

    def gmail(self, indirizzo: str, password_app: str) -> CasellaGmail:
        return CasellaGmail(indirizzo, password_app, self.imap_factory, self.smtp_factory)

    def dimentica(self, casella_id: int) -> None:
        with self._lock:
            self._accessi.pop(casella_id, None)

    def _accesso(self, casella: sqlite3.Row):
        with self._lock:
            accesso = self._accessi.get(casella["id"])
        if accesso is None:
            accesso = self.accesso_factory(self._segreto(casella))
            with self._lock:
                self._accessi[casella["id"]] = accesso
        return accesso

    def per(self, casella: sqlite3.Row) -> Connettore:
        tipo = casella["tipo"]
        if tipo == "finta":
            return CasellaFinta(casella["id"], clock.da_iso(casella["creata_il"]))
        if tipo == "gmail":
            return self.gmail(casella["indirizzo"], self._segreto(casella) or "")
        if tipo == "outlook":
            if not self.ms_client_id:
                raise ErroreCasella("Il collegamento con Outlook non è configurato sul server.")
            casella_id = casella["id"]
            cursori = {}
            if casella["cursore_posta"]:
                cursori["inbox"] = casella["cursore_posta"]
            if casella["cursore_inviate"]:
                cursori["sentitems"] = casella["cursore_inviate"]
            return CasellaOutlook(
                self._accesso(casella),
                lambda cache: self._salva_cache(casella_id, cache),
                self.graph_transport,
                cursori=cursori,
                salva_cursori=lambda nuovi: self._salva_cursori(casella_id, nuovi),
            )
        raise ErroreCasella("Tipo di casella sconosciuto.")

    def _salva_cache(self, casella_id: int, cache: str) -> None:
        with db.aperta(self.db_path) as conn:
            conn.execute(
                "UPDATE caselle SET segreto_cifrato = ? WHERE id = ?", (self.cifrario.cifra(cache), casella_id)
            )

    def _salva_cursori(self, casella_id: int, nuovi: dict) -> None:
        colonne = {"inbox": "cursore_posta", "sentitems": "cursore_inviate"}
        with db.aperta(self.db_path) as conn:
            for cartella, link in nuovi.items():
                if cartella in colonne:
                    conn.execute(f"UPDATE caselle SET {colonne[cartella]} = ? WHERE id = ?", (link, casella_id))

    # --- collegamento Outlook ---------------------------------------------------------------

    def avvia_outlook(self, casella_id: int, ricollega: bool = False) -> dict:
        """Chiede a Microsoft il codice per l'utente e lascia in sottofondo l'attesa
        dell'accesso. Restituisce il flusso (user_code, verification_uri, expires_in)."""
        if not self.ms_client_id:
            raise ErroreCasella("Il collegamento con Outlook non è ancora configurato sul server.")
        accesso = self.accesso_factory(None)
        flusso = accesso.avvia()
        self.in_sottofondo(self._completa_outlook, casella_id, accesso, flusso, ricollega)
        return flusso

    def _completa_outlook(self, casella_id: int, accesso, flusso: dict, ricollega: bool = False) -> None:
        try:
            risultato = accesso.completa(flusso)
            if not risultato or "access_token" not in risultato:
                raise ErroreCasella(CODICE_SCADUTO, "credenziali")
            indirizzo = CasellaOutlook(accesso, None, self.graph_transport).verifica()
            with db.aperta(self.db_path) as conn:
                c = conn.execute("SELECT * FROM caselle WHERE id = ?", (casella_id,)).fetchone()
                if c is None or c["stato"] != "in_collegamento":
                    return  # eliminata, o chiusa per tempo scaduto, mentre aspettavamo
                if ricollega and c["indirizzo"] and c["indirizzo"].lower() != indirizzo.lower():
                    raise ErroreCasella(ACCOUNT_DIVERSO.format(trovato=indirizzo, atteso=c["indirizzo"]), "credenziali")
                doppia = conn.execute(
                    "SELECT 1 FROM caselle WHERE indirizzo = ? AND id != ? AND tipo != 'finta'",
                    (indirizzo, casella_id),
                ).fetchone()
                if doppia:
                    raise ErroreCasella("Questa casella è già collegata a Sol.", "credenziali")
                # Ricollegando si tiene tutto: attiva_dal, mail, bozze e il punto di ripresa.
                conn.execute(
                    "UPDATE caselle SET stato = 'collegata', errore = NULL, errore_tipo = NULL, indirizzo = ?,"
                    " segreto_cifrato = ?, in_collegamento_dal = NULL"
                    + ("" if ricollega else ", attiva_dal = ?")
                    + " WHERE id = ?",
                    (indirizzo, self.cifrario.cifra(accesso.serializza()))
                    + (() if ricollega else (clock.adesso(),))
                    + (casella_id,),
                )
            with self._lock:
                self._accessi[casella_id] = accesso
            self.dopo_collegamento(casella_id)
        except ErroreCasella as e:
            self._errore_collegamento(casella_id, e.messaggio)
        except Exception:
            log.exception("collegamento Outlook della casella %s fallito", casella_id)
            self._errore_collegamento(casella_id, "Il collegamento con Outlook non è riuscito. Usa «Ricollega» per riprovare.")

    def _errore_collegamento(self, casella_id: int, frase: str) -> None:
        with db.aperta(self.db_path) as conn:
            conn.execute(
                "UPDATE caselle SET stato = 'errore', errore = ?, errore_tipo = 'credenziali', in_collegamento_dal = NULL"
                " WHERE id = ? AND stato = 'in_collegamento'",
                (frase, casella_id),
            )

