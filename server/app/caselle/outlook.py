"""Outlook / Microsoft 365 con Microsoft Graph.

Accesso con il "codice dispositivo" di Microsoft (device code flow): l'utente apre
microsoft.com/devicelogin, scrive il codice e accede; il server non vede mai la
password. I token (con quello di rinnovo) stanno nel database cifrati; msal li
rinnova da solo quando scadono. Una sola app msal per casella, con timeout.

Permessi delegati: Mail.ReadWrite, Mail.Send, offline_access, User.Read. A msal si
passano solo gli altri tre: offline_access lo aggiunge lui (e rifiuta di riceverlo).

Si chiede a Graph l'id immutabile delle mail (Prefer: IdType="ImmutableId"): senza,
l'id cambia quando la mail viene spostata e Sol non la ritroverebbe più.

Banda: la lettura usa la "delta query" di Graph. Il primo giro elenca le mail da
attiva_dal (solo id e data); dai giri dopo Graph manda solo i cambiamenti. Il corpo si
scarica solo per le mail davvero nuove. Il punto da cui riprendere si salva solo dopo che
le mail lette sono nel database."""

import logging
from contextlib import contextmanager
from datetime import datetime, timezone

import httpx

from . import testo
from .base import Connettore, ErroreCasella, InviataGrezza, Invio, MailGrezza, Riferimento

log = logging.getLogger("uvicorn.error")

GRAPH = "https://graph.microsoft.com/v1.0"
PERMESSI = ["Mail.ReadWrite", "Mail.Send", "User.Read"]
CAMPI_MAIL = (
    "id,internetMessageId,conversationId,subject,from,toRecipients,ccRecipients,"
    "receivedDateTime,sentDateTime,isRead,body,hasAttachments,internetMessageHeaders"
)
PAGINE_MASSIME = 200
TIMEOUT = 30
# Errori di msal che vogliono dire "l'accesso non vale più" (password cambiata, accesso
# revocato): serve «Ricollega». Tutti gli altri sono problemi passeggeri di Microsoft.
ERRORI_REVOCA = {"invalid_grant", "interaction_required", "invalid_client", "unauthorized_client",
                 "consent_required", "login_required"}

ACCESSO_SCADUTO = "Microsoft non riconosce più l'accesso di Sol a questa casella. Usa «Ricollega» dall'app."
NON_RAGGIUNGIBILE = "Non riesco a raggiungere Outlook. Controlla la connessione del server e riprova tra poco."
MICROSOFT_NON_RISPONDE = "Microsoft non risponde adesso. Sol riprova da solo al prossimo controllo."
INVIO_AMBIGUO = (
    "Outlook non ha confermato l'invio: la mail potrebbe essere partita. "
    "Prima di riprovare Sol controlla in Posta inviata."
)


class AccessoMicrosoft:
    """Il pezzo che parla con login.microsoftonline.com (msal). Nei test si usa un finto."""

    def __init__(self, client_id: str, tenant: str, cache_serializzata: str | None = None, timeout: int = TIMEOUT):
        import msal

        self._cache = msal.SerializableTokenCache()
        if cache_serializzata:
            self._cache.deserialize(cache_serializzata)
        try:
            self._app = msal.PublicClientApplication(
                client_id, authority=f"https://login.microsoftonline.com/{tenant}", token_cache=self._cache,
                timeout=timeout,
            )
        except Exception as e:  # msal scarica la configurazione dell'autorità: può mancare la rete
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e

    def avvia(self) -> dict:
        try:
            flusso = self._app.initiate_device_flow(scopes=PERMESSI)
        except Exception as e:
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        if "user_code" not in flusso:
            raise ErroreCasella("Microsoft non ha dato il codice di accesso. Controlla la configurazione del server.")
        return flusso

    def completa(self, flusso: dict) -> dict:
        """Aspetta (bloccando) che l'utente finisca l'accesso o che il codice scada."""
        return self._app.acquire_token_by_device_flow(flusso)

    def token(self) -> str:
        account = self._app.get_accounts()
        if not account:
            raise ErroreCasella(ACCESSO_SCADUTO, "credenziali")
        try:
            risultato = self._app.acquire_token_silent_with_error(PERMESSI, account=account[0])
        except Exception as e:  # rete, timeout: passeggero
            raise ErroreCasella(MICROSOFT_NON_RISPONDE, "rete") from e
        if risultato is None:
            raise ErroreCasella(ACCESSO_SCADUTO, "credenziali")
        if "access_token" in risultato:
            return risultato["access_token"]
        if (risultato.get("error") or "").lower() in ERRORI_REVOCA:
            raise ErroreCasella(ACCESSO_SCADUTO, "credenziali")
        raise ErroreCasella(MICROSOFT_NON_RISPONDE, "rete")

    def serializza(self) -> str:
        return self._cache.serialize()

    def cache_cambiata(self) -> str | None:
        if self._cache.has_state_changed:
            self._cache.has_state_changed = False
            return self._cache.serialize()
        return None


def _data(valore: str | None) -> datetime:
    if not valore:
        return datetime.now(timezone.utc)
    dt = datetime.fromisoformat(valore.replace("Z", "+00:00"))
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _iso_graph(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _indirizzi(lista) -> list[str]:
    return [
        (r.get("emailAddress") or {}).get("address", "").lower()
        for r in (lista or [])
        if (r.get("emailAddress") or {}).get("address")
    ]


def _destinatari(indirizzi: list[str]) -> list[dict]:
    return [{"emailAddress": {"address": a}} for a in indirizzi]


def _intestazioni(messaggio: dict) -> dict[str, str]:
    return {(h.get("name") or "").lower(): h.get("value") or "" for h in messaggio.get("internetMessageHeaders") or []}


def _corpo(messaggio: dict) -> str:
    corpo = messaggio.get("body") or {}
    contenuto = (corpo.get("content") or "")[: 4 * testo.LIMITE_TESTO]
    if (corpo.get("contentType") or "").lower() == "html":
        return testo.html_a_testo(contenuto)
    return testo.pulisci(contenuto)


def _filtro_odata(valore: str) -> str:
    return valore.replace("'", "''")


def _automatica(intest: dict[str, str]) -> bool:
    automatica = intest.get("auto-submitted", "").strip().lower()
    if automatica and automatica != "no":
        return True
    if "x-autoreply" in intest or "x-autorespond" in intest:
        return True
    return intest.get("precedence", "").strip().lower() in ("auto_reply", "auto-reply")


class CasellaOutlook(Connettore):
    tipo = "outlook"

    def __init__(self, accesso, salva_cache=None, transport: httpx.BaseTransport | None = None,
                 cursori: dict | None = None, salva_cursori=None):
        super().__init__()
        self._accesso = accesso
        self._salva_cache = salva_cache
        self._transport = transport
        self._cursori = dict(cursori or {})
        self._nuovi_cursori: dict[str, str] = {}
        self._salva_cursori = salva_cursori
        self._aperto: httpx.Client | None = None
        self._cartelle: dict[str, str] = {}
        self._id_arrivo: str | None = None

    # --- collegamento -------------------------------------------------------------------

    def _nuovo_client(self) -> httpx.Client:
        token = self._accesso.token()
        self._forse_salva_cache()
        return httpx.Client(
            base_url=GRAPH,
            transport=self._transport,
            timeout=TIMEOUT,
            headers=[
                ("Authorization", f"Bearer {token}"),
                ("Prefer", 'IdType="ImmutableId"'),
                ("Prefer", 'outlook.body-content-type="text"'),
                ("Prefer", "odata.maxpagesize=50"),
            ],
        )

    @contextmanager
    def sessione(self):
        if self._aperto is not None:
            yield self
            return
        with self._nuovo_client() as client:
            self._aperto = client
            try:
                yield self
            finally:
                self._aperto = None

    @contextmanager
    def _client(self):
        if self._aperto is not None:
            yield self._aperto
            return
        with self._nuovo_client() as client:
            yield client

    def _forse_salva_cache(self) -> None:
        nuova = self._accesso.cache_cambiata()
        if nuova and self._salva_cache:
            self._salva_cache(nuova)

    def _chiedi(self, client: httpx.Client, metodo: str, url: str, **kw) -> dict:
        try:
            r = client.request(metodo, url, **kw)
        except httpx.TransportError as e:
            raise ErroreCasella(NON_RAGGIUNGIBILE, "rete") from e
        if r.status_code == 401:
            raise ErroreCasella(ACCESSO_SCADUTO, "credenziali", status=401)
        if r.status_code == 403:
            raise ErroreCasella(
                "Microsoft non dà a Sol il permesso per questa operazione. Ricollega la casella accettando i permessi.",
                "credenziali", status=403,
            )
        if r.status_code == 404:
            raise ErroreCasella(
                "Non trovo più questa mail nella casella: forse è stata cancellata o spostata a mano.", status=404
            )
        if r.status_code == 429 or r.status_code >= 500:
            raise ErroreCasella(MICROSOFT_NON_RISPONDE, "rete", status=r.status_code)
        if r.status_code >= 400:
            log.warning("Graph %s %s -> %s", metodo, url.split("?")[0], r.status_code)
            raise ErroreCasella("Outlook ha rifiutato l'operazione. Riprova tra poco.", status=r.status_code)
        if not r.content:
            return {}
        try:
            return r.json()
        except ValueError:
            return {}

    # --- lettura -----------------------------------------------------------------------------

    def _elenco_classico(self, client: httpx.Client, cartella: str, dal: datetime):
        dati = self._chiedi(
            client, "GET", f"/me/mailFolders/{cartella}/messages",
            params={"$filter": f"receivedDateTime ge {_iso_graph(dal)}", "$select": "id,receivedDateTime", "$top": "50"},
        )
        for _ in range(PAGINE_MASSIME):
            yield from dati.get("value") or []
            prossima = dati.get("@odata.nextLink")
            if not prossima:
                return
            dati = self._chiedi(client, "GET", prossima)

    def _delta(self, client: httpx.Client, cartella: str, dal: datetime):
        """Gli id cambiati dall'ultima lettura (o, la prima volta, da `dal`)."""
        link = self._cursori.get(cartella)
        dati = None
        if link:
            try:
                dati = self._chiedi(client, "GET", link)
            except ErroreCasella as e:
                if e.status not in (400, 404, 410):
                    raise
                log.info("Outlook: il punto di ripresa di %s non vale più, rileggo da capo", cartella)
                dati = None
        if dati is None:
            try:
                dati = self._chiedi(
                    client, "GET", f"/me/mailFolders/{cartella}/messages/delta",
                    params={"$select": "id,receivedDateTime", "$filter": f"receivedDateTime ge {_iso_graph(dal)}"},
                )
            except ErroreCasella as e:
                if e.status != 400:
                    raise
                # Se il filtro sulla delta non fosse accettato, si elencano solo gli id.
                yield from self._elenco_classico(client, cartella, dal)
                return
        for _ in range(PAGINE_MASSIME):
            yield from dati.get("value") or []
            if dati.get("@odata.deltaLink"):
                self._nuovi_cursori[cartella] = dati["@odata.deltaLink"]
                return
            prossima = dati.get("@odata.nextLink")
            if not prossima:
                return
            dati = self._chiedi(client, "GET", prossima)

    def _nuovi_id(self, client, cartella: str, dal: datetime, gia_viste: set[str]):
        for voce in self._delta(client, cartella, dal):
            mid = voce.get("id")
            if not mid or "@removed" in voce or mid in gia_viste:
                continue
            ricevuta = voce.get("receivedDateTime")
            if ricevuta and _data(ricevuta) < dal:
                continue
            yield mid

    def _completa(self, client, mid: str):
        try:
            return self._chiedi(client, "GET", f"/me/messages/{mid}", params={"$select": CAMPI_MAIL})
        except ErroreCasella as e:
            if e.status == 404:
                return None  # cancellata nel frattempo
            raise

    def _mail(self, client, m: dict) -> MailGrezza:
        intest = _intestazioni(m)
        mittente = (m.get("from") or {}).get("emailAddress") or {}
        allegati = []
        if m.get("hasAttachments"):
            dati = self._chiedi(client, "GET", f"/me/messages/{m['id']}/attachments", params={"$select": "name,size"})
            for a in dati.get("value") or []:
                allegati.append({"nome": a.get("name") or "allegato", "byte": int(a.get("size") or 0)})
        return MailGrezza(
            id_esterno=m["id"],
            id_messaggio=m.get("internetMessageId"),
            filo=m.get("conversationId"),
            riferimenti=" ".join(x for x in (intest.get("references", ""), intest.get("in-reply-to", "")) if x),
            mittente_nome=testo.riga(mittente.get("name") or "", testo.LIMITE_NOME),
            mittente_indirizzo=(mittente.get("address") or "").lower(),
            destinatari=_indirizzi(m.get("toRecipients")),
            cc=_indirizzi(m.get("ccRecipients")),
            oggetto=testo.riga(m.get("subject") or ""),
            corpo=_corpo(m),
            ricevuta_il=_data(m.get("receivedDateTime")),
            letta=bool(m.get("isRead")),
            allegati=allegati,
            newsletter="list-unsubscribe" in intest,
            automatica=_automatica(intest),
        )

    def _scorri(self, cartella: str, dal: datetime, gia_viste: set[str], converti, tipo: str):
        with self._client() as client:
            for mid in self._nuovi_id(client, cartella, dal, gia_viste):
                m = self._completa(client, mid)
                if m is None:
                    continue
                try:
                    elemento = converti(client, m)
                except ErroreCasella:
                    raise
                except Exception as e:  # una mail scritta male non ferma le altre
                    log.warning("Outlook: %s %s saltata (%s)", tipo, mid, type(e).__name__)
                    self.scartate.append((tipo, mid, type(e).__name__))
                    continue
                yield elemento

    def nuove(self, dal: datetime, gia_viste: set[str]):
        yield from self._scorri("inbox", dal, gia_viste, self._mail, "mail")

    def _inviata(self, client, m: dict) -> InviataGrezza:
        intest = _intestazioni(m)
        return InviataGrezza(
            id_esterno=m["id"],
            id_messaggio=m.get("internetMessageId"),
            filo=m.get("conversationId"),
            riferimenti=" ".join(x for x in (intest.get("references", ""), intest.get("in-reply-to", "")) if x),
            a=_indirizzi(m.get("toRecipients")),
            oggetto=testo.riga(m.get("subject") or ""),
            corpo=_corpo(m),
            inviata_il=_data(m.get("sentDateTime") or m.get("receivedDateTime")),
        )

    def inviate(self, dal: datetime, gia_viste: set[str]):
        yield from self._scorri("sentitems", dal, gia_viste, self._inviata, "inviata")

    def conferma_lettura(self) -> None:
        if self._nuovi_cursori and self._salva_cursori:
            self._salva_cursori(dict(self._nuovi_cursori))
        self._cursori.update(self._nuovi_cursori)
        self._nuovi_cursori = {}

    def verifica(self) -> str:
        with self._client() as client:
            io = self._chiedi(client, "GET", "/me", params={"$select": "mail,userPrincipalName"})
        return (io.get("mail") or io.get("userPrincipalName") or "").lower()

    # --- cartelle e spostamenti --------------------------------------------------------------

    def _cartella(self, client: httpx.Client, percorso: str, crea: bool = True) -> str | None:
        """L'id della cartella "Sol/Clienti", creando i pezzi che mancano."""
        if percorso in self._cartelle:
            return self._cartelle[percorso]
        genitore = None
        for parte in [p for p in percorso.split("/") if p.strip()]:
            raccolta = "/me/mailFolders" if genitore is None else f"/me/mailFolders/{genitore}/childFolders"
            trovate = self._chiedi(
                client, "GET", raccolta,
                params={"$filter": f"displayName eq '{_filtro_odata(parte)}'", "$select": "id,displayName"},
            ).get("value") or []
            if trovate:
                genitore = trovate[0]["id"]
            elif crea:
                genitore = self._chiedi(client, "POST", raccolta, json={"displayName": parte})["id"]
            else:
                return None
        if genitore is None:
            raise ErroreCasella("Il nome della cartella non va bene.")
        self._cartelle[percorso] = genitore
        return genitore

    def _posta_in_arrivo(self, client) -> str:
        if self._id_arrivo is None:
            self._id_arrivo = self._chiedi(client, "GET", "/me/mailFolders/inbox", params={"$select": "id"})["id"]
        return self._id_arrivo

    def sposta(self, mail: Riferimento, cartella: str, da_cartella: str | None = None) -> bool:
        with self._client() as client:
            try:
                dove = self._chiedi(client, "GET", f"/me/messages/{mail.id_esterno}",
                                    params={"$select": "id,parentFolderId"}).get("parentFolderId")
            except ErroreCasella as e:
                if e.status == 404:
                    return False  # eliminata davvero: non c'è niente da spostare
                raise
            ammesse = {self._posta_in_arrivo(client)}
            if da_cartella:
                nostra = self._cartella(client, da_cartella, crea=False)
                if nostra:
                    ammesse.add(nostra)
            if dove not in ammesse:
                return False  # già sistemata dall'utente (archivio, Posta eliminata...): non si tocca
            destinazione = self._cartella(client, cartella)
            if dove != destinazione:
                self._chiedi(client, "POST", f"/me/messages/{mail.id_esterno}/move", json={"destinationId": destinazione})
            return True

    def segna_letta(self, mail: Riferimento, letta: bool) -> None:
        with self._client() as client:
            self._chiedi(client, "PATCH", f"/me/messages/{mail.id_esterno}", json={"isRead": letta})

    # --- invio -----------------------------------------------------------------------------------

    def invia_risposta(self, originale: Riferimento, a: list[str], cc: list[str], oggetto: str, testo_mail: str,
                       invio: Invio) -> None:
        """createReply prepara la risposta nello stesso filo (In-Reply-To, conversazione);
        poi si mettono destinatari, oggetto e testo della bozza approvata, e si invia.
        La bozza creata nella casella si riusa ai tentativi successivi e, se l'invio
        fallisce di sicuro, si toglie: in Bozze non restano doppioni orfani."""
        with self._client() as client:
            bozza_id = invio.esterno
            if bozza_id:
                try:
                    d = self._chiedi(client, "GET", f"/me/messages/{bozza_id}", params={"$select": "id,isDraft"})
                    if not d.get("isDraft"):
                        bozza_id = None
                except ErroreCasella as e:
                    if e.status != 404:
                        raise
                    bozza_id = None
            if not bozza_id:
                creata = self._chiedi(client, "POST", f"/me/messages/{originale.id_esterno}/createReply")
                bozza_id = creata["id"]
                id_messaggio = creata.get("internetMessageId")
                if not id_messaggio:
                    id_messaggio = self._chiedi(client, "GET", f"/me/messages/{bozza_id}",
                                                params={"$select": "internetMessageId"}).get("internetMessageId")
                invio.salva(id_messaggio, bozza_id)  # prima di spedire: serve per non mandarla due volte
            try:
                self._chiedi(
                    client, "PATCH", f"/me/messages/{bozza_id}",
                    json={
                        "subject": testo.riga(oggetto) or "Re:",
                        "body": {"contentType": "Text", "content": testo_mail},
                        "toRecipients": _destinatari(a),
                        "ccRecipients": _destinatari(cc),
                    },
                )
            except ErroreCasella as e:
                self._togli_bozza(client, bozza_id)
                invio.salva(None, None)
                raise ErroreCasella(e.messaggio, e.tipo, ambiguo=False, status=e.status) from e
            try:
                self._chiedi(client, "POST", f"/me/messages/{bozza_id}/send")
            except ErroreCasella as e:
                if e.status is None or e.status >= 500:
                    raise ErroreCasella(INVIO_AMBIGUO, "rete", ambiguo=True, status=e.status) from e
                self._togli_bozza(client, bozza_id)
                invio.salva(None, None)
                raise

    def gia_inviata(self, id_messaggio: str) -> bool:
        with self._client() as client:
            dati = self._chiedi(
                client, "GET", "/me/mailFolders/sentitems/messages",
                params={"$filter": f"internetMessageId eq '{_filtro_odata(id_messaggio)}'", "$select": "id", "$top": "1"},
            )
        return bool(dati.get("value"))

    def _togli_bozza(self, client, bozza_id: str) -> None:
        """Toglie una bozza creata da Sol, solo se è ancora una bozza (mai una mail vera)."""
        try:
            d = self._chiedi(client, "GET", f"/me/messages/{bozza_id}", params={"$select": "id,isDraft"})
            if d.get("isDraft"):
                self._chiedi(client, "DELETE", f"/me/messages/{bozza_id}")
        except ErroreCasella:
            log.warning("Outlook: non sono riuscito a togliere una bozza di Sol non partita")

    def ripulisci_bozza_sol(self, esterno: str) -> None:
        with self._client() as client:
            self._togli_bozza(client, esterno)
