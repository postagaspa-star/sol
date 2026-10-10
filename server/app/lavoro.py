"""Il lavoro in sottofondo: tre giri indipendenti, così se uno si blocca gli altri vanno.

- posta: ogni `intervallo_controllo_minuti` legge le caselle (nuove e inviate), smista
  con le regole, e fuori dalla modalità prova sposta davvero nelle caselle le mail
  smistate da quando la prova è spenta (l'arretrato solo se l'utente lo chiede);
- decisore: una mail alla volta, le domande che mancano (gruppo, da rispondere, urgenza)
  e per le inviate "aspetta una risposta?";
- scrittore: una cosa alla volta, nell'ordine: riassunti chiesti dall'utente, bozze
  chieste dall'utente, bozze automatiche, riassunti di una riga, scadenze, riassunto di oggi.

Ogni passo è una funzione normale (sincrona) che fa una cosa e dice com'è andata:
"fatto" (c'è altro, continua), "vuoto" (niente da fare, aspetta), "guasto" (un servizio
non risponde, aspetta di più). I test chiamano i passi direttamente.

Niente resta appeso: una mail scritta male si salta (e si ricorda, per non riprovarla a
ogni giro), una casella che dà un errore inatteso va in "errore" e le altre continuano,
un lavoro di scrittura che fallisce finisce sempre in uno stato con una frase leggibile,
e i lavori in sottofondo hanno un tetto di tentativi con attese che crescono."""

import asyncio
import json
import logging
import re
import sqlite3
import threading
from datetime import datetime, timedelta

from . import clock, db, prompt, ricerca, smista
from .caselle import DURATA_COLLEGAMENTO, ErroreCasella, Riferimento
from .caselle import testo as testo_mail
from .decisore import DecisoreNonDisponibile, MailNonValida, RispostaNonValida
from .forme import cartella_di
from .scrittore import RichiestaRifiutata, ScrittoreInPausa, ScrittoreNonDisponibile

log = logging.getLogger("uvicorn.error")

FATTO, VUOTO, GUASTO = "fatto", "vuoto", "guasto"
TENTATIVI_DECISORE = 3
TENTATIVI_APPLICA = 3
TENTATIVI_BOZZA_AUTOMATICA = 3
TENTATIVI_SFONDO = 8  # riassunto di una riga e scadenze: con le attese sotto, circa tre ore
# Con almeno queste mail da smistare, i lavori di scrittura in sottofondo non usano lo
# scrittore locale: decisore e locale sono lo stesso modello e lo smistamento viene prima.
ARRETRATO_SMISTAMENTO = 3
ATTESE_MINUTI = (1, 2, 4, 8, 16, 32, 60)

ERRORE_RIASSUNTO = (
    "Non riesco a scrivere il riassunto adesso: i servizi di scrittura non rispondono. "
    "Riprova tra qualche minuto."
)
ERRORE_RIASSUNTO_RIFIUTATO = (
    "Non riesco a scrivere questo riassunto: prova con una richiesta più precisa."
)
ERRORE_RIASSUNTO_INATTESO = "Sol ha avuto un problema preparando il riassunto. Riprova."
ERRORE_BOZZA = (
    "Non sono riuscito a preparare la bozza: i servizi di scrittura non rispondono. "
    "Puoi riprovare tra qualche minuto o scriverla a mano."
)
ERRORE_BOZZA_INATTESO = "Sol ha avuto un problema preparando la bozza. Puoi riprovare o scriverla a mano."
ERRORE_CASELLA_INATTESO = (
    "Sol ha avuto un problema leggendo questa casella. Riprova più tardi; se succede ancora, "
    "avvisa chi ha installato Sol."
)
COLLEGAMENTO_SCADUTO = "Il collegamento non è stato completato in tempo. Usa «Ricollega» per riprovare."

# Un solo spostamento alla volta tra giro di posta e gesto dell'utente: due mosse insieme
# sulla stessa mail si pesterebbero i piedi (una annullerebbe l'altra).
LUCCHETTO_SPOSTA = threading.Lock()


def attesa(tentativi: int) -> timedelta:
    return timedelta(minutes=ATTESE_MINUTI[min(max(tentativi, 1), len(ATTESE_MINUTI)) - 1])


def riferimento(r: sqlite3.Row) -> Riferimento:
    return Riferimento(r["id_esterno"], r["id_messaggio"], r["filo"], r["riferimenti"] or "")


def risposta_arrivata(conn: sqlite3.Connection, inviata: sqlite3.Row) -> bool:
    """È arrivata una risposta vera (non automatica) dopo questa inviata?"""
    if inviata["filo"]:
        if conn.execute(
            "SELECT 1 FROM mail WHERE casella_id = ? AND filo = ? AND ricevuta_il > ? AND automatica = 0 LIMIT 1",
            (inviata["casella_id"], inviata["filo"], inviata["inviata_il"]),
        ).fetchone():
            return True
    if inviata["id_messaggio"]:
        if conn.execute(
            "SELECT 1 FROM mail WHERE casella_id = ? AND instr(riferimenti, ?) > 0 AND automatica = 0 LIMIT 1",
            (inviata["casella_id"], inviata["id_messaggio"]),
        ).fetchone():
            return True
    return False


class Lavoro:
    def __init__(self, db_path: str, fabbrica, decisore, scrittore):
        self.db_path = db_path
        self.fabbrica = fabbrica
        self.decisore = decisore
        self.scrittore = scrittore
        self.attesa_guasto = 60.0
        self._loop: asyncio.AbstractEventLoop | None = None
        self._sveglie: dict[str, asyncio.Event] = {}
        self._ultima_pulizia = None

    # ------------------------------------------------------------------ posta

    def giro_posta(self) -> str:
        self.chiudi_collegamenti_scaduti()
        with db.aperta(self.db_path) as conn:
            ids = [r["id"] for r in conn.execute(
                "SELECT id FROM caselle WHERE stato != 'in_collegamento'"
                " AND NOT (stato = 'errore' AND errore_tipo = 'credenziali')"
            )]
        for casella_id in ids:
            try:
                self.controlla_casella(casella_id)
            except Exception:  # una casella che esplode non ferma le altre
                log.exception("casella %s: errore inatteso nel controllo", casella_id)
                self._segna_errore_inatteso(casella_id)
        for passo in (self.applica_in_caselle, self.pulizia_se_serve):
            try:
                passo()
            except Exception:
                log.exception("giro di posta: errore inatteso in %s", passo.__name__)
        return VUOTO  # il giro di posta aspetta sempre l'intervallo

    def _segna_errore_inatteso(self, casella_id: int) -> None:
        with db.aperta(self.db_path) as conn:
            conn.execute(
                "UPDATE caselle SET stato = 'errore', errore = ?, errore_tipo = 'altro', ultimo_controllo = ?"
                " WHERE id = ? AND stato != 'in_collegamento'",
                (ERRORE_CASELLA_INATTESO, clock.adesso(), casella_id),
            )

    def chiudi_collegamenti_scaduti(self) -> None:
        limite = clock.iso(clock.now() - DURATA_COLLEGAMENTO)
        with db.aperta(self.db_path) as conn:
            conn.execute(
                "UPDATE caselle SET stato = 'errore', errore = ?, errore_tipo = 'credenziali', in_collegamento_dal = NULL"
                " WHERE stato = 'in_collegamento' AND COALESCE(in_collegamento_dal, creata_il) < ?",
                (COLLEGAMENTO_SCADUTO, limite),
            )

    def _dal(self, conn: sqlite3.Connection, casella: sqlite3.Row, imp: dict) -> datetime:
        """Da quando leggere: mai prima di attiva_dal, e mai prima del limite dell'ultima
        pulizia (alzare conserva_giorni non deve far rientrare come nuove le mail tolte)."""
        candidati = [clock.da_iso(casella["attiva_dal"]), clock.now() - timedelta(days=imp["conserva_giorni"])]
        limite = db.leggi_interno(conn, "limite_pulizia")
        if limite:
            candidati.append(clock.da_iso(limite))
        return max(candidati)

    def _viste(self, conn: sqlite3.Connection, casella_id: int, tipo: str) -> set[str]:
        tabella = "mail" if tipo == "mail" else "inviate"
        viste = {r[0] for r in conn.execute(f"SELECT id_esterno FROM {tabella} WHERE casella_id = ?", (casella_id,))}
        viste |= {r[0] for r in conn.execute(
            "SELECT id_esterno FROM mail_illeggibili WHERE casella_id = ? AND tipo = ?", (casella_id, tipo)
        )}
        return viste

    def controlla_casella(self, casella_id: int) -> int:
        with db.aperta(self.db_path) as conn:
            c = conn.execute("SELECT * FROM caselle WHERE id = ?", (casella_id,)).fetchone()
            if c is None or c["stato"] == "in_collegamento":
                return 0
            imp = db.impostazioni(conn)
            dal = self._dal(conn, c, imp)
            inizio = clock.now()
            contate = 0
            connettore = None
            try:
                connettore = self.fabbrica.per(c)
                with connettore.sessione():
                    for g in connettore.nuove(dal, self._viste(conn, casella_id, "mail")):
                        if self._salva_mail_sicura(conn, casella_id, g):
                            contate += 1
                    for i in connettore.inviate(dal, self._viste(conn, casella_id, "inviata")):
                        self._salva_inviata_sicura(conn, casella_id, i)
                    connettore.conferma_lettura()
            except ErroreCasella as e:
                self._registra_scartate(conn, casella_id, connettore)
                # Password o accesso rifiutati: "errore" (serve «Ricollega»). Un problema
                # passeggero (rete, Microsoft lento): resta collegata, con l'avviso.
                conn.execute(
                    "UPDATE caselle SET stato = CASE WHEN ? = 'credenziali' THEN 'errore' ELSE stato END,"
                    " errore = ?, errore_tipo = ?, ultimo_controllo = ? WHERE id = ? AND stato != 'in_collegamento'",
                    (e.tipo, e.messaggio, e.tipo, clock.adesso(), casella_id),
                )
                log.warning("casella %s: controllo fallito (%s)", casella_id, e.tipo)
                if contate:
                    self._sveglia_dopo_mail()
                return contate
            self._registra_scartate(conn, casella_id, connettore)
            conn.execute(
                "UPDATE caselle SET stato = 'collegata', errore = NULL, errore_tipo = NULL, ultimo_controllo = ?,"
                " letto_fino_al = ? WHERE id = ? AND stato != 'in_collegamento'",
                (clock.adesso(), clock.iso(inizio), casella_id),
            )
        if contate:
            self._sveglia_dopo_mail()
        return contate

    def _sveglia_dopo_mail(self) -> None:
        self.sveglia("decisore")
        self.sveglia("scrittore")

    def _registra_illeggibile(self, conn: sqlite3.Connection, casella_id: int, tipo: str, id_esterno: str,
                              motivo: str) -> None:
        cur = conn.execute(
            "INSERT OR IGNORE INTO mail_illeggibili (casella_id, tipo, id_esterno, motivo, il) VALUES (?, ?, ?, ?, ?)",
            (casella_id, tipo, str(id_esterno), motivo[:200], clock.adesso()),
        )
        if cur.rowcount:
            log.warning("casella %s: %s %s non leggibile (%s): saltata", casella_id, tipo, id_esterno, motivo)

    def _registra_scartate(self, conn: sqlite3.Connection, casella_id: int, connettore) -> None:
        for tipo, id_esterno, motivo in getattr(connettore, "scartate", None) or []:
            self._registra_illeggibile(conn, casella_id, tipo, id_esterno, motivo)
        if connettore is not None:
            connettore.scartate = []

    def _salva_mail_sicura(self, conn: sqlite3.Connection, casella_id: int, g) -> bool:
        try:
            return self._salva_mail(conn, casella_id, g)
        except Exception as e:
            log.exception("casella %s: mail %s non salvata", casella_id, getattr(g, "id_esterno", "?"))
            self._registra_illeggibile(conn, casella_id, "mail", getattr(g, "id_esterno", "?"), type(e).__name__)
            return False

    def _salva_inviata_sicura(self, conn: sqlite3.Connection, casella_id: int, i) -> None:
        try:
            self._salva_inviata(conn, casella_id, i)
        except Exception as e:
            log.exception("casella %s: inviata %s non salvata", casella_id, getattr(i, "id_esterno", "?"))
            self._registra_illeggibile(conn, casella_id, "inviata", getattr(i, "id_esterno", "?"), type(e).__name__)

    def _salva_mail(self, conn: sqlite3.Connection, casella_id: int, g) -> bool:
        g = testo_mail.pulisci_grezza(g)
        with db.transazione(conn):
            cur = conn.execute(
                "INSERT OR IGNORE INTO mail (casella_id, id_esterno, id_messaggio, filo, riferimenti, mittente_nome,"
                " mittente_indirizzo, destinatari, cc, oggetto, corpo, ricevuta_il, letta, allegati, newsletter, pec,"
                " pec_mittente_busta, automatica)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    casella_id, g.id_esterno, g.id_messaggio, g.filo, g.riferimenti or "", g.mittente_nome or "",
                    g.mittente_indirizzo or "", json.dumps(g.destinatari), json.dumps(g.cc),
                    g.oggetto or "", g.corpo or "", clock.iso(g.ricevuta_il), 1 if g.letta else 0,
                    json.dumps(g.allegati), 1 if g.newsletter else 0, 1 if g.pec else 0,
                    g.pec_busta if g.pec else None, 1 if g.automatica else 0,
                ),
            )
            if cur.rowcount == 0:
                return False
            # Il riassunto di oggi si rifà per le mail importanti (dopo la valutazione) e ogni ora:
            # rifarlo a ogni newsletter consumerebbe le richieste gratuite per niente.
            smista.smista_con_regole(conn, cur.lastrowid)
        return True

    def _salva_inviata(self, conn: sqlite3.Connection, casella_id: int, i) -> None:
        i = testo_mail.pulisci_inviata(i)
        with db.transazione(conn):
            cur = conn.execute(
                "INSERT OR IGNORE INTO inviate (casella_id, id_esterno, id_messaggio, filo, riferimenti, a, oggetto,"
                " corpo, inviata_il) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    casella_id, i.id_esterno, i.id_messaggio, i.filo, i.riferimenti or "", json.dumps(i.a),
                    i.oggetto or "", i.corpo or "", clock.iso(i.inviata_il),
                ),
            )
            if cur.rowcount == 0:
                return
            # Se l'utente ha risposto da Outlook o dal sito di Gmail, la mail non "aspetta" più lui.
            ids = set()
            if i.filo:
                ids |= {r[0] for r in conn.execute(
                    "SELECT id FROM mail WHERE casella_id = ? AND filo = ? AND ricevuta_il <= ? AND risposta_data = 0",
                    (casella_id, i.filo, clock.iso(i.inviata_il)),
                )}
            citate = re.findall(r"<[^>]+>", i.riferimenti or "")
            for id_messaggio in citate[:50]:
                ids |= {r[0] for r in conn.execute(
                    "SELECT id FROM mail WHERE casella_id = ? AND id_messaggio = ? AND risposta_data = 0",
                    (casella_id, id_messaggio),
                )}
            for mail_id in ids:
                conn.execute("UPDATE mail SET risposta_data = 1 WHERE id = ?", (mail_id,))
                # 15.13: la bozza automatica mai toccata dall'utente non serve più; se l'aveva
                # modificata resta (l'app avvisa prima di inviarla).
                conn.execute(
                    "UPDATE bozze SET stato = 'scartata', versione = versione + 1 WHERE mail_id = ? AND tipo = 'risposta'"
                    " AND stato IN ('in_preparazione', 'pronta', 'errore') AND modificata = 0 AND richiesta_utente = 0"
                    " AND in_invio_dal IS NULL",
                    (mail_id,),
                )

    # ------------------------------------------------------------------ spostamenti

    def applica_in_caselle(self) -> int:
        """Fuori dalla modalità prova: sposta davvero nelle caselle le mail segnate `applica`
        (smistate dopo lo spegnimento, o arretrato chiesto dall'utente). Impostazione e mail
        si rileggono prima di ogni spostamento: se l'utente riaccende la prova o corregge una
        mail nel frattempo, vale quello che ha fatto lui. Un solo collegamento per casella."""
        fatte = 0
        with db.aperta(self.db_path) as conn:
            caselle = [r[0] for r in conn.execute(
                "SELECT DISTINCT m.casella_id FROM mail m JOIN caselle c ON c.id = m.casella_id"
                " WHERE m.applica = 1 AND m.applicata_in_casella = 0 AND m.tentativi_applica < ?"
                " AND c.stato = 'collegata'",
                (TENTATIVI_APPLICA,),
            )]
            for casella_id in caselle:
                if smista.prova_accesa(conn):
                    break
                c = conn.execute("SELECT * FROM caselle WHERE id = ?", (casella_id,)).fetchone()
                try:
                    connettore = self.fabbrica.per(c)
                    with connettore.sessione():
                        fatte += self._applica_casella(conn, casella_id, connettore)
                except ErroreCasella as e:
                    log.warning("casella %s: spostamenti rimandati (%s)", casella_id, e.tipo)
        return fatte

    def _applica_casella(self, conn: sqlite3.Connection, casella_id: int, connettore) -> int:
        fatte, saltate = 0, [0]
        while True:
            if smista.prova_accesa(conn):
                return fatte
            with LUCCHETTO_SPOSTA:
                m = conn.execute(
                    "SELECT m.*, g.nome AS gruppo_nome FROM mail m JOIN gruppi g ON g.id = m.gruppo_id"
                    " WHERE m.casella_id = ? AND m.applica = 1 AND m.applicata_in_casella = 0"
                    " AND m.tentativi_applica < ? AND m.smistamento IN ('smistata', 'corretta')"
                    f" AND m.id NOT IN ({','.join('?' * len(saltate))}) ORDER BY m.id LIMIT 1",
                    (casella_id, TENTATIVI_APPLICA, *saltate),
                ).fetchone()
                if m is None:
                    return fatte
                cartella = cartella_di(m["gruppo_nome"])
                try:
                    spostata = connettore.sposta(riferimento(m), cartella, m["cartella_applicata"])
                except ErroreCasella as e:
                    conn.execute("UPDATE mail SET tentativi_applica = tentativi_applica + 1 WHERE id = ?", (m["id"],))
                    if e.tipo in ("rete", "credenziali"):
                        raise
                    saltate.append(m["id"])
                    continue
                # Se l'utente l'aveva già spostata altrove, Sol non l'ha toccata: la si segna
                # comunque come sistemata (cartella sconosciuta).
                conn.execute(
                    "UPDATE mail SET applicata_in_casella = 1, applica = 0, cartella_applicata = ? WHERE id = ?",
                    (cartella if spostata else None, m["id"]),
                )
                fatte += 1

    def pulizia_se_serve(self) -> None:
        oggi = clock.oggi()
        if self._ultima_pulizia == oggi:
            return
        self._ultima_pulizia = oggi
        with db.aperta(self.db_path) as conn:
            giorni = db.impostazioni(conn)["conserva_giorni"]
            limite = clock.iso(clock.now() - timedelta(days=giorni))
            with db.transazione(conn):
                conn.execute("DELETE FROM mail WHERE ricevuta_il < ?", (limite,))
                conn.execute("DELETE FROM inviate WHERE inviata_il < ?", (limite,))
                precedente = db.leggi_interno(conn, "limite_pulizia")
                db.salva_interno(conn, "limite_pulizia", max(limite, precedente) if precedente else limite)
            conn.execute(
                "DELETE FROM riassunti WHERE id NOT IN (SELECT id FROM riassunti ORDER BY id DESC LIMIT 50)"
            )
            ieri = clock.iso(clock.now() - timedelta(days=1))
            conn.execute("DELETE FROM tentativi_abbinamento WHERE il < ?", (ieri,))
            conn.execute("DELETE FROM codici_abbinamento WHERE scade_il < ?", (ieri,))

    # ------------------------------------------------------------------ decisore

    def _decisore_fallito(self, conn: sqlite3.Connection, mail_id: int) -> None:
        conn.execute("UPDATE mail SET tentativi_decisore = tentativi_decisore + 1 WHERE id = ?", (mail_id,))
        riga = conn.execute("SELECT tentativi_decisore FROM mail WHERE id = ?", (mail_id,)).fetchone()
        if riga and riga[0] >= TENTATIVI_DECISORE:
            smista.lascia_all_utente(conn, mail_id)

    def passo_decisore(self) -> str:
        with db.aperta(self.db_path) as conn:
            imp = db.impostazioni(conn)
            m = conn.execute(
                "SELECT * FROM mail WHERE (smistamento = 'in_attesa' OR da_rispondere IS NULL OR truffa_valutata = 0)"
                " AND tentativi_decisore < ? ORDER BY ricevuta_il DESC, id DESC LIMIT 1",
                (TENTATIVI_DECISORE,),
            ).fetchone()
            if m is not None:
                try:
                    smista.valuta(conn, m, self.decisore, float(imp["soglia_sicurezza"]))
                except DecisoreNonDisponibile:
                    return GUASTO
                except MailNonValida:
                    # Il problema è questa mail: la decide l'utente, senza altri tentativi.
                    conn.execute("UPDATE mail SET tentativi_decisore = ? WHERE id = ?", (TENTATIVI_DECISORE, m["id"]))
                    smista.lascia_all_utente(conn, m["id"])
                    return FATTO
                except RispostaNonValida:
                    self._decisore_fallito(conn, m["id"])
                    return FATTO
                except Exception:
                    log.exception("decisore: errore inatteso sulla mail %s", m["id"])
                    self._decisore_fallito(conn, m["id"])
                    return FATTO
                self.sveglia("scrittore")
                return FATTO
            i = conn.execute(
                "SELECT * FROM inviate WHERE aspetta_risposta IS NULL AND ignorata = 0 AND tentativi_decisore < ?"
                " ORDER BY inviata_il DESC, id DESC LIMIT 1",
                (TENTATIVI_DECISORE,),
            ).fetchone()
            if i is None:
                return VUOTO
            if risposta_arrivata(conn, i):
                # Già risposto: non serve chiedere al decisore (è lento), non andrà mai in lista.
                conn.execute("UPDATE inviate SET aspetta_risposta = 0 WHERE id = ?", (i["id"],))
                return FATTO
            stato = prompt.testo_mail("", "", i["oggetto"], prompt._taglia(i["corpo"], prompt.LIMITE_CORPO_DECISORE))
            stato = prompt.neutralizza(f"Inviata a: {', '.join(json.loads(i['a']))}\n" + stato)
            try:
                risposte = self.decisore.decidi(stato, [smista.DOMANDA_INVIATA])
            except DecisoreNonDisponibile:
                return GUASTO
            except Exception:
                log.warning("decisore: inviata %s non valutata", i["id"])
                conn.execute("UPDATE inviate SET tentativi_decisore = tentativi_decisore + 1 WHERE id = ?", (i["id"],))
                return FATTO
            conn.execute(
                "UPDATE inviate SET aspetta_risposta = ? WHERE id = ?",
                (1 if risposte["aspetta"]["scelta"] == "si" else 0, i["id"]),
            )
            return FATTO

    # ------------------------------------------------------------------ scrittore

    def _prossimo_lavoro(self, conn: sqlite3.Connection):
        ora = clock.adesso()
        r = conn.execute("SELECT * FROM riassunti WHERE stato = 'in_coda' ORDER BY id LIMIT 1").fetchone()
        if r is not None:
            return "richiesto", r
        b = conn.execute(
            "SELECT * FROM bozze WHERE stato = 'in_preparazione' AND richiesta_utente = 1 ORDER BY id LIMIT 1"
        ).fetchone()
        if b is not None:
            return "bozza", b
        # I riassunti di una riga prima delle bozze in sottofondo: costano poco e servono subito a
        # scorrere l'elenco. Col solo scrittore locale (~5 token/s) la prova sul NAS li mostrava
        # dopo un quarto d'ora, in coda a tutte le bozze.
        m = conn.execute(
            "SELECT * FROM mail WHERE riassunto IS NULL AND tentativi_riassunto < ?"
            " AND (prossimo_riassunto IS NULL OR prossimo_riassunto <= ?) ORDER BY ricevuta_il DESC, id DESC LIMIT 1",
            (TENTATIVI_SFONDO, ora),
        ).fetchone()
        if m is not None:
            return "riga", m
        b = conn.execute(
            "SELECT * FROM bozze WHERE stato = 'in_preparazione'"
            " AND (prossimo_tentativo IS NULL OR prossimo_tentativo <= ?) ORDER BY id LIMIT 1",
            (ora,),
        ).fetchone()
        if b is not None:
            return "bozza", b
        # Scadenze: dalle mail valutate, mai da quelle smistate tra le newsletter. Una mail
        # da_controllare (anche se proposta come newsletter) può essere una richiesta vera.
        m = conn.execute(
            "SELECT m.* FROM mail m LEFT JOIN gruppi g ON g.id = m.gruppo_id"
            " WHERE m.scadenze_fatte = 0 AND m.sospetta = 0 AND (m.smistamento = 'da_controllare'"
            " OR (m.smistamento IN ('smistata', 'corretta') AND (g.chiave IS NULL OR g.chiave != 'newsletter')))"
            " AND (m.prossimo_scadenze IS NULL OR m.prossimo_scadenze <= ?) ORDER BY m.ricevuta_il DESC LIMIT 1",
            (ora,),
        ).fetchone()
        if m is not None:
            return "scadenze", m
        r = conn.execute("SELECT * FROM riassunto_oggi WHERE id = 1").fetchone()
        oggi = clock.oggi().isoformat()
        vecchio = r["aggiornato_il"] is None or clock.now() - clock.da_iso(r["aggiornato_il"]) >= timedelta(hours=1)
        da_fare = r["giorno"] != oggi or r["da_rifare"] or vecchio
        if da_fare and (r["prossimo"] is None or r["prossimo"] <= ora):
            return "oggi", r
        return None

    def passo_scrittore(self) -> str:
        with db.aperta(self.db_path) as conn:
            imp = db.impostazioni(conn)
            lavoro = self._prossimo_lavoro(conn)
            if lavoro is None:
                return VUOTO
            tipo, riga = lavoro
            esegui = {
                "richiesto": self._riassunto_richiesto, "bozza": self._bozza, "riga": self._riassunto_riga,
                "scadenze": self._scadenze, "oggi": self._riassunto_oggi,
            }[tipo]
            dell_utente = tipo == "richiesto" or (tipo == "bozza" and riga["richiesta_utente"])
            escludi_locale = not dell_utente and self._arretrato_smistamento(conn) >= ARRETRATO_SMISTAMENTO
            try:
                return esegui(conn, riga, imp, escludi_locale)
            except ScrittoreInPausa:
                # Non si è provato nessuno scrittore: si rimanda senza contare il tentativo.
                self._rimanda(conn, tipo, riga)
                return GUASTO
            except ScrittoreNonDisponibile:
                self._fallito(conn, tipo, riga, "giu")
                return GUASTO
            except RichiestaRifiutata:
                self._fallito(conn, tipo, riga, "rifiutata")
                return FATTO
            except Exception:
                log.exception("scrittore: errore inatteso (%s)", tipo)
                self._fallito(conn, tipo, riga, "inatteso")
                return FATTO

    def _arretrato_smistamento(self, conn: sqlite3.Connection) -> int:
        return conn.execute(
            "SELECT COUNT(*) FROM mail WHERE smistamento = 'in_attesa' AND tentativi_decisore < ?", (TENTATIVI_DECISORE,)
        ).fetchone()[0]

    def _rimanda(self, conn: sqlite3.Connection, tipo: str, riga: sqlite3.Row) -> None:
        if tipo == "richiesto":
            conn.execute("UPDATE riassunti SET stato = 'in_coda' WHERE id = ? AND stato = 'in_corso'", (riga["id"],))
        elif tipo == "oggi":
            conn.execute("UPDATE riassunto_oggi SET stato = ? WHERE id = 1 AND stato = 'in_corso'",
                         ("pronto" if riga["testo"] else "in_coda",))

    def _fallito(self, conn: sqlite3.Connection, tipo: str, riga: sqlite3.Row, come: str) -> None:
        """Ogni fallimento porta a uno stato da cui si riparte o a uno stato finale leggibile."""
        ora = clock.now()
        if tipo == "richiesto":
            frase = {"giu": ERRORE_RIASSUNTO, "rifiutata": ERRORE_RIASSUNTO_RIFIUTATO}.get(come, ERRORE_RIASSUNTO_INATTESO)
            conn.execute("UPDATE riassunti SET stato = 'errore', errore = ? WHERE id = ?", (frase, riga["id"]))
        elif tipo == "bozza":
            tentativi = riga["tentativi"] + 1
            if riga["richiesta_utente"] or tentativi >= TENTATIVI_BOZZA_AUTOMATICA or come == "inatteso":
                conn.execute(
                    "UPDATE bozze SET stato = 'errore', errore = ?, tentativi = ? WHERE id = ? AND stato = 'in_preparazione'",
                    (ERRORE_BOZZA_INATTESO if come == "inatteso" else ERRORE_BOZZA, tentativi, riga["id"]),
                )
            else:
                conn.execute(
                    "UPDATE bozze SET tentativi = ?, prossimo_tentativo = ? WHERE id = ?",
                    (tentativi, clock.iso(ora + attesa(tentativi)), riga["id"]),
                )
        elif tipo == "riga":
            tentativi = riga["tentativi_riassunto"] + 1
            conn.execute(
                "UPDATE mail SET tentativi_riassunto = ?, prossimo_riassunto = ? WHERE id = ?",
                (tentativi, clock.iso(ora + attesa(tentativi)), riga["id"]),
            )
        elif tipo == "scadenze":
            tentativi = riga["tentativi_scadenze"] + 1
            conn.execute(
                "UPDATE mail SET tentativi_scadenze = ?, prossimo_scadenze = ?, scadenze_fatte = ? WHERE id = ?",
                (tentativi, clock.iso(ora + attesa(tentativi)), 1 if tentativi >= TENTATIVI_SFONDO else 0, riga["id"]),
            )
        elif tipo == "oggi":
            tentativi = riga["tentativi"] + 1
            conn.execute(
                "UPDATE riassunto_oggi SET tentativi = ?, prossimo = ? WHERE id = 1",
                (tentativi, clock.iso(ora + attesa(tentativi))),
            )
            oggi = clock.oggi().isoformat()
            # Se c'è già un riassunto di oggi resta visibile (con la sua ora); se non c'è,
            # l'app vede "errore" invece di un "in_corso" che non finisce mai.
            if riga["giorno"] != oggi or not riga["testo"]:
                conn.execute(
                    "UPDATE riassunto_oggi SET stato = 'errore', testo = '', mail_citate = '[]', giorno = ? WHERE id = 1",
                    (oggi,),
                )

    def _riassunto_richiesto(self, conn: sqlite3.Connection, r: sqlite3.Row, imp: dict,
                             escludi_locale: bool = False) -> str:
        conn.execute("UPDATE riassunti SET stato = 'in_corso' WHERE id = ?", (r["id"],))
        scelte = ricerca.seleziona(conn, r["richiesta"])
        if not scelte:
            conn.execute(
                "UPDATE riassunti SET stato = 'pronto', testo = ?, mail_citate = '[]' WHERE id = ?",
                ("Non ho trovato mail che riguardano questa richiesta.", r["id"]),
            )
            return FATTO
        incluse: list[int] = []
        testo = self.scrittore.scrivi(
            lambda limite: prompt.riassunto_richiesta(r["richiesta"], scelte, limite, incluse),
            imp["scrittori"], max_token=700, max_token_locale=300, escludi_locale=escludi_locale,
        )
        pulito, citate = prompt.citazioni(testo.testo, set(incluse) or {m["id"] for m in scelte})
        conn.execute(
            "UPDATE riassunti SET stato = 'pronto', testo = ?, mail_citate = ?, errore = NULL WHERE id = ?",
            (pulito, json.dumps(citate), r["id"]),
        )
        return FATTO

    def _bozza(self, conn: sqlite3.Connection, b: sqlite3.Row, imp: dict, escludi_locale: bool = False) -> str:
        base = None
        if b["rifai_da"] == "attuale" and (b["testo"] or "").strip():
            base = prompt.togli_firma(b["testo"], imp["firma"])
        if b["tipo"] == "risposta":
            m = conn.execute("SELECT * FROM mail WHERE id = ?", (b["mail_id"],)).fetchone()
            if m is None:
                conn.execute("DELETE FROM bozze WHERE id = ?", (b["id"],))
                return FATTO

            def costruisci(limite):
                return prompt.bozza_risposta(m, imp, b["istruzioni"], base, limite)
        else:
            i = conn.execute("SELECT * FROM inviate WHERE id = ?", (b["inviata_id"],)).fetchone()
            if i is None:
                conn.execute("DELETE FROM bozze WHERE id = ?", (b["id"],))
                return FATTO
            giorni = clock.giorni_trascorsi(i["inviata_il"])

            def costruisci(limite):
                return prompt.bozza_sollecito(i, giorni, imp, b["istruzioni"], base, limite)
        testo = self.scrittore.scrivi(costruisci, imp["scrittori"], max_token=600, max_token_locale=250,
                                      escludi_locale=escludi_locale)
        with db.transazione(conn):
            # Solo se nel frattempo non è stata scartata o rifatta di nuovo.
            conn.execute(
                "UPDATE bozze SET stato = 'pronta', testo = ?, fornitore = ?, errore = NULL, seq_pronta = ?,"
                " versione = versione + 1, prossimo_tentativo = NULL, tentativi = 0"
                " WHERE id = ? AND stato = 'in_preparazione' AND versione = ?",
                (prompt.testo_con_firma(testo.testo, imp["firma"]), testo.fornitore, db.prossimo_seq(conn),
                 b["id"], b["versione"]),
            )
        return FATTO

    def _riassunto_riga(self, conn: sqlite3.Connection, m: sqlite3.Row, imp: dict, escludi_locale: bool = False) -> str:
        testo = self.scrittore.scrivi(lambda limite: prompt.riassunto_riga(m, limite), imp["scrittori"], max_token=120,
                                      escludi_locale=escludi_locale)
        riga = prompt.pulisci_riga(testo.testo) or testo_mail.riga(m["oggetto"], 300)
        conn.execute("UPDATE mail SET riassunto = ? WHERE id = ?", (riga, m["id"]))
        return FATTO

    def _scadenze(self, conn: sqlite3.Connection, m: sqlite3.Row, imp: dict, escludi_locale: bool = False) -> str:
        oggi = clock.oggi()
        testo = self.scrittore.scrivi(lambda limite: prompt.scadenze(m, oggi, limite), imp["scrittori"],
                                      max_token=400, max_token_locale=250, escludi_locale=escludi_locale)
        with db.transazione(conn):
            for s in prompt.leggi_scadenze(testo.testo):
                # Una scadenza già passata non serve come promemoria; una tra più di due anni è un errore.
                if not (oggi.isoformat() <= s["quando"] <= (oggi + timedelta(days=730)).isoformat()):
                    continue
                gia = conn.execute(
                    "SELECT 1 FROM scadenze WHERE mail_id = ? AND quando = ? AND descrizione = ?",
                    (m["id"], s["quando"], s["descrizione"]),
                ).fetchone()
                if not gia:
                    conn.execute(
                        "INSERT INTO scadenze (mail_id, descrizione, quando, ora, creata_il) VALUES (?, ?, ?, ?, ?)",
                        (m["id"], s["descrizione"], s["quando"], s["ora"], clock.adesso()),
                    )
            conn.execute("UPDATE mail SET scadenze_fatte = 1 WHERE id = ?", (m["id"],))
        return FATTO

    def _riassunto_oggi(self, conn: sqlite3.Connection, r: sqlite3.Row, imp: dict, escludi_locale: bool = False) -> str:
        oggi = clock.oggi()
        mail = conn.execute(
            "SELECT * FROM mail WHERE ricevuta_il >= ? ORDER BY ricevuta_il DESC, id DESC LIMIT 60",
            (clock.iso(clock.inizio_giorno(oggi)),),
        ).fetchall()
        if not mail:
            testo, citate = "Oggi non è arrivata nessuna mail.", []
        else:
            importanti = sum(1 for m in mail if not m["sospetta"] and (m["da_rispondere"] or m["urgenza"] == "alta"))
            if not r["testo"] or r["giorno"] != oggi.isoformat():
                conn.execute("UPDATE riassunto_oggi SET stato = 'in_corso' WHERE id = 1")
            incluse: list[int] = []
            scritto = self.scrittore.scrivi(
                lambda limite: prompt.riassunto_oggi(mail, importanti, oggi, limite, incluse),
                imp["scrittori"], max_token=500, max_token_locale=300, escludi_locale=escludi_locale,
            )
            testo, citate = prompt.citazioni(scritto.testo, set(incluse) or {m["id"] for m in mail})
        conn.execute(
            "UPDATE riassunto_oggi SET giorno = ?, testo = ?, mail_citate = ?, aggiornato_il = ?, stato = 'pronto',"
            " da_rifare = 0, tentativi = 0, prossimo = NULL WHERE id = 1",
            (oggi.isoformat(), testo, json.dumps(citate), clock.adesso()),
        )
        return FATTO

    # ------------------------------------------------------------------ giri asincroni

    def sveglia(self, nome: str) -> None:
        if self._loop is None or nome not in self._sveglie:
            return
        try:
            self._loop.call_soon_threadsafe(self._sveglie[nome].set)
        except RuntimeError:
            pass  # il server si sta spegnendo

    def _intervallo_posta(self) -> float:
        with db.aperta(self.db_path) as conn:
            return 60.0 * float(db.impostazioni(conn)["intervallo_controllo_minuti"])

    async def _ciclo(self, nome: str, passo, attesa_vuoto, ferma: asyncio.Event) -> None:
        sveglia = self._sveglie[nome]
        while not ferma.is_set():
            sveglia.clear()
            try:
                esito = await asyncio.to_thread(passo)
            except Exception:
                log.exception("lavoro %s: errore inatteso", nome)
                esito = GUASTO
            if esito == FATTO:
                await asyncio.sleep(0)
                continue
            try:
                attesa_secondi = self.attesa_guasto if esito == GUASTO else await asyncio.to_thread(attesa_vuoto)
            except Exception:
                attesa_secondi = self.attesa_guasto
            try:
                await asyncio.wait_for(sveglia.wait(), timeout=attesa_secondi)
            except asyncio.TimeoutError:
                pass

    async def gira(self, ferma: asyncio.Event) -> None:
        self._loop = asyncio.get_running_loop()
        self._sveglie = {n: asyncio.Event() for n in ("posta", "decisore", "scrittore")}
        compiti = [
            asyncio.create_task(self._ciclo("posta", self.giro_posta, self._intervallo_posta, ferma)),
            asyncio.create_task(self._ciclo("decisore", self.passo_decisore, lambda: 30.0, ferma)),
            asyncio.create_task(self._ciclo("scrittore", self.passo_scrittore, lambda: 60.0, ferma)),
        ]
        await ferma.wait()
        for c in compiti:
            c.cancel()
        await asyncio.gather(*compiti, return_exceptions=True)
        self._loop = None
