"""SQLite: una connessione per richiesta (o per passo del lavoro in sottofondo),
schema creato e migrato all'avvio.

Le connessioni sono in autocommit: ogni istruzione è già salvata da sola, e dove
servono più istruzioni insieme si usa `transazione(conn)`. Così non dipendiamo da
quando FastAPI chiude le dipendenze rispetto all'invio della risposta."""

import hashlib
import json
import sqlite3
from contextlib import contextmanager

from fastapi import Request

from . import clock

GRUPPI_INIZIALI = [
    # (chiave, nome, descrizione per il decisore)
    ("clienti", "Clienti", "un cliente chiede, conferma o discute un lavoro, un'offerta, un sopralluogo o una perizia"),
    ("fornitori", "Fornitori", "un fornitore manda offerte, conferme d'ordine, consegne, listini o materiale tecnico"),
    ("amministrazione", "Amministrazione e fatture", "fatture, pagamenti, solleciti di pagamento, banca, commercialista, tasse"),
    ("pec", "PEC ed enti", "posta certificata, Comune, enti pubblici, pratiche, autorizzazioni, ordini professionali"),
    ("newsletter", "Newsletter", "newsletter, pubblicità, promozioni, avvisi automatici di siti e servizi"),
    ("altro", "Altro", "tutto quello che non rientra negli altri gruppi"),
]

IMPOSTAZIONI_INIZIALI = {
    "modalita_prova": True,
    "soglia_sicurezza": 0.85,
    "giorni_risposta": 2,
    "giorni_sollecito": 7,
    "intervallo_controllo_minuti": 5,
    "firma": "Cordiali saluti\nManuel",
    "tono": "cortese e diretto",
    "scrittori": ["groq", "cerebras", "locale"],
    "conserva_giorni": 180,
}

SCHEMA_1 = """
CREATE TABLE dispositivi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('android', 'pc')),
    token_hash TEXT NOT NULL UNIQUE,
    abbinato_il TEXT NOT NULL,
    ultimo_accesso TEXT
);
CREATE TABLE codici_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    codice_hash TEXT NOT NULL,
    creato_il TEXT NOT NULL,
    scade_il TEXT NOT NULL,
    usato_il TEXT,
    annullato_il TEXT
);
CREATE TABLE tentativi_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ip TEXT NOT NULL,
    il TEXT NOT NULL
);
CREATE TABLE caselle (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL CHECK (tipo IN ('outlook', 'gmail', 'finta')),
    indirizzo TEXT NOT NULL,
    nome TEXT NOT NULL,
    stato TEXT NOT NULL CHECK (stato IN ('collegata', 'in_collegamento', 'errore')),
    errore TEXT,
    -- 'credenziali' | 'rete' | 'altro': con password rifiutata non si riprova da soli
    -- (Google blocca chi sbaglia troppe volte), solo quando l'utente chiede un controllo
    errore_tipo TEXT,
    ultimo_controllo TEXT,
    attiva_dal TEXT NOT NULL,
    creata_il TEXT NOT NULL,
    -- password per le app (Gmail) o cache dei token Microsoft, sempre cifrati
    segreto_cifrato TEXT
);
CREATE TABLE gruppi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL UNIQUE COLLATE NOCASE,
    ordine INTEGER NOT NULL,
    -- i gruppi iniziali hanno una chiave fissa: le regole fisse (PEC, Newsletter)
    -- li ritrovano anche se l'utente li rinomina
    chiave TEXT UNIQUE,
    descrizione TEXT NOT NULL DEFAULT ''
);
CREATE TABLE regole (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL CHECK (tipo IN ('mittente', 'dominio')),
    valore TEXT NOT NULL,
    gruppo_id INTEGER NOT NULL REFERENCES gruppi(id) ON DELETE CASCADE,
    origine TEXT NOT NULL CHECK (origine IN ('imparata', 'manuale')),
    creata_il TEXT NOT NULL,
    UNIQUE (tipo, valore)
);
CREATE TABLE mail (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    casella_id INTEGER NOT NULL REFERENCES caselle(id) ON DELETE CASCADE,
    id_esterno TEXT NOT NULL,
    id_messaggio TEXT,
    filo TEXT,
    riferimenti TEXT NOT NULL DEFAULT '',
    mittente_nome TEXT NOT NULL DEFAULT '',
    mittente_indirizzo TEXT NOT NULL DEFAULT '',
    destinatari TEXT NOT NULL DEFAULT '[]',
    cc TEXT NOT NULL DEFAULT '[]',
    oggetto TEXT NOT NULL DEFAULT '',
    corpo TEXT NOT NULL DEFAULT '',
    ricevuta_il TEXT NOT NULL,
    letta INTEGER NOT NULL DEFAULT 0,
    risposta_data INTEGER NOT NULL DEFAULT 0,
    gruppo_id INTEGER REFERENCES gruppi(id) ON DELETE SET NULL,
    -- NULL finché il decisore non ha risposto
    da_rispondere INTEGER,
    urgenza TEXT,
    sicurezza REAL,
    smistamento TEXT NOT NULL DEFAULT 'in_attesa'
        CHECK (smistamento IN ('in_attesa', 'smistata', 'da_controllare', 'corretta')),
    origine_smistamento TEXT,
    probabilita TEXT,
    applicata_in_casella INTEGER NOT NULL DEFAULT 0,
    cartella_applicata TEXT,
    tentativi_applica INTEGER NOT NULL DEFAULT 0,
    allegati TEXT NOT NULL DEFAULT '[]',
    newsletter INTEGER NOT NULL DEFAULT 0,
    pec INTEGER NOT NULL DEFAULT 0,
    riassunto TEXT,
    scadenze_fatte INTEGER NOT NULL DEFAULT 0,
    seq_valutata INTEGER,
    tentativi_decisore INTEGER NOT NULL DEFAULT 0,
    UNIQUE (casella_id, id_esterno)
);
CREATE INDEX mail_ricevuta ON mail (ricevuta_il DESC, id DESC);
CREATE INDEX mail_smistamento ON mail (smistamento);
CREATE INDEX mail_filo ON mail (casella_id, filo);
CREATE TABLE inviate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    casella_id INTEGER NOT NULL REFERENCES caselle(id) ON DELETE CASCADE,
    id_esterno TEXT NOT NULL,
    id_messaggio TEXT,
    filo TEXT,
    riferimenti TEXT NOT NULL DEFAULT '',
    a TEXT NOT NULL DEFAULT '[]',
    oggetto TEXT NOT NULL DEFAULT '',
    corpo TEXT NOT NULL DEFAULT '',
    inviata_il TEXT NOT NULL,
    aspetta_risposta INTEGER,
    ignorata INTEGER NOT NULL DEFAULT 0,
    tentativi_decisore INTEGER NOT NULL DEFAULT 0,
    UNIQUE (casella_id, id_esterno)
);
CREATE TABLE bozze (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mail_id INTEGER REFERENCES mail(id) ON DELETE CASCADE,
    inviata_id INTEGER REFERENCES inviate(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL CHECK (tipo IN ('risposta', 'sollecito')),
    stato TEXT NOT NULL CHECK (stato IN ('in_preparazione', 'pronta', 'inviata', 'scartata', 'errore')),
    a TEXT NOT NULL DEFAULT '[]',
    cc TEXT NOT NULL DEFAULT '[]',
    oggetto TEXT NOT NULL DEFAULT '',
    testo TEXT NOT NULL DEFAULT '',
    istruzioni TEXT,
    fornitore TEXT,
    modificata INTEGER NOT NULL DEFAULT 0,
    creata_il TEXT NOT NULL,
    inviata_il TEXT,
    errore TEXT,
    tentativi INTEGER NOT NULL DEFAULT 0,
    -- chiesta dall'utente: passa davanti alle bozze automatiche nella coda
    richiesta_utente INTEGER NOT NULL DEFAULT 0,
    seq_pronta INTEGER
);
CREATE TABLE scadenze (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    mail_id INTEGER REFERENCES mail(id) ON DELETE CASCADE,
    descrizione TEXT NOT NULL,
    quando TEXT NOT NULL,
    ora TEXT,
    stato TEXT NOT NULL DEFAULT 'aperta' CHECK (stato IN ('aperta', 'fatta', 'scartata')),
    creata_il TEXT NOT NULL
);
CREATE TABLE riassunti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    richiesta TEXT NOT NULL,
    stato TEXT NOT NULL CHECK (stato IN ('in_coda', 'in_corso', 'pronto', 'errore')),
    testo_parziale TEXT,
    testo TEXT,
    mail_citate TEXT NOT NULL DEFAULT '[]',
    errore TEXT,
    creato_il TEXT NOT NULL
);
CREATE TABLE riassunto_oggi (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    giorno TEXT,
    testo TEXT NOT NULL DEFAULT '',
    aggiornato_il TEXT,
    mail_citate TEXT NOT NULL DEFAULT '[]',
    stato TEXT NOT NULL DEFAULT 'in_coda',
    da_rifare INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE impostazioni (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);
CREATE TABLE contatori (
    nome TEXT PRIMARY KEY,
    valore INTEGER NOT NULL
);
"""


def _semina_1(conn: sqlite3.Connection) -> None:
    for ordine, (chiave, nome, descrizione) in enumerate(GRUPPI_INIZIALI, start=1):
        conn.execute(
            "INSERT INTO gruppi (nome, ordine, chiave, descrizione) VALUES (?, ?, ?, ?)",
            (nome, ordine, chiave, descrizione),
        )
    for chiave, valore in IMPOSTAZIONI_INIZIALI.items():
        conn.execute(
            "INSERT INTO impostazioni (chiave, valore) VALUES (?, ?)", (chiave, json.dumps(valore))
        )
    conn.execute("INSERT INTO riassunto_oggi (id) VALUES (1)")
    conn.execute("INSERT INTO contatori (nome, valore) VALUES ('novita', 0)")


# v1.1 (dopo la prima revisione, contratto sezione 15).
SCHEMA_2 = """
ALTER TABLE caselle ADD COLUMN letto_fino_al TEXT;
ALTER TABLE caselle ADD COLUMN cursore_posta TEXT;
ALTER TABLE caselle ADD COLUMN cursore_inviate TEXT;
ALTER TABLE caselle ADD COLUMN in_collegamento_dal TEXT;
ALTER TABLE mail ADD COLUMN pec_mittente_busta TEXT;
ALTER TABLE mail ADD COLUMN regola_fissa TEXT;
ALTER TABLE mail ADD COLUMN automatica INTEGER NOT NULL DEFAULT 0;
ALTER TABLE mail ADD COLUMN applica INTEGER NOT NULL DEFAULT 0;
ALTER TABLE mail ADD COLUMN tentativi_riassunto INTEGER NOT NULL DEFAULT 0;
ALTER TABLE mail ADD COLUMN prossimo_riassunto TEXT;
ALTER TABLE mail ADD COLUMN tentativi_scadenze INTEGER NOT NULL DEFAULT 0;
ALTER TABLE mail ADD COLUMN prossimo_scadenze TEXT;
ALTER TABLE mail ADD COLUMN incerti TEXT NOT NULL DEFAULT '[]';
ALTER TABLE bozze ADD COLUMN versione INTEGER NOT NULL DEFAULT 1;
ALTER TABLE bozze ADD COLUMN id_messaggio TEXT;
ALTER TABLE bozze ADD COLUMN bozza_esterna TEXT;
ALTER TABLE bozze ADD COLUMN in_invio_dal TEXT;
ALTER TABLE bozze ADD COLUMN invio_ambiguo INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bozze ADD COLUMN rifai_da TEXT;
ALTER TABLE bozze ADD COLUMN prossimo_tentativo TEXT;
ALTER TABLE riassunto_oggi ADD COLUMN tentativi INTEGER NOT NULL DEFAULT 0;
ALTER TABLE riassunto_oggi ADD COLUMN prossimo TEXT;
CREATE INDEX mail_applica ON mail (applica, applicata_in_casella);
CREATE TABLE mail_illeggibili (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    casella_id INTEGER NOT NULL REFERENCES caselle(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL CHECK (tipo IN ('mail', 'inviata')),
    id_esterno TEXT NOT NULL,
    motivo TEXT NOT NULL,
    il TEXT NOT NULL,
    UNIQUE (casella_id, tipo, id_esterno)
);
CREATE TABLE interno (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
)
"""

# I testi consigliati dal decisore (decisore/app/domande_consigliate.json): con questi
# il decisore ha già pronta la parte fissa della domanda e la prima mail non aspetta.
DESCRIZIONI_2 = {
    "clienti": "un cliente (azienda o privato) chiede un preventivo, un intervento, un sopralluogo, oppure conferma o segue un lavoro dello studio",
    "fornitori": "un'azienda che VENDE allo studio manda offerte, listini, conferme d'ordine o consegne",
    "amministrazione": "fatture ricevute, solleciti di pagamento, banca, commercialista, tasse",
    "pec": "PEC o comunicazioni di Comune, Regione, Vigili del fuoco o altri enti pubblici",
    "newsletter": "newsletter, pubblicità, promozioni, webinar, mail inviate a molti destinatari",
    "altro": "mail personali o che non rientrano negli altri gruppi",
}


def _semina_2(conn: sqlite3.Connection) -> None:
    vecchie = {chiave: descrizione for chiave, _, descrizione in GRUPPI_INIZIALI}
    for chiave, descrizione in DESCRIZIONI_2.items():
        # Solo se l'utente non l'ha cambiata (oggi le API non lo permettono, domani forse).
        conn.execute(
            "UPDATE gruppi SET descrizione = ? WHERE chiave = ? AND descrizione = ?",
            (descrizione, chiave, vecchie[chiave]),
        )
    # Le mail già smistate prima della v1.1 non vanno spostate da sole (sezione 15.2).
    conn.execute("UPDATE mail SET applica = 0")


# Migrazioni: (versione, sql, funzione di semina o None). Si aggiunge in fondo,
# mai si modifica una già rilasciata: un database vecchio le fa tutte in ordine.
MIGRAZIONI = [
    (1, SCHEMA_1, _semina_1),
    (2, SCHEMA_2, _semina_2),
]


def connetti(db_path: str) -> sqlite3.Connection:
    # check_same_thread=False: FastAPI può spostare dipendenza e handler su thread
    # diversi del pool; la connessione resta comunque di una sola richiesta.
    conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 10000")
    return conn


@contextmanager
def transazione(conn: sqlite3.Connection):
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    else:
        conn.execute("COMMIT")


@contextmanager
def aperta(db_path: str):
    conn = connetti(db_path)
    try:
        yield conn
    finally:
        conn.close()


def init_db(db_path: str) -> None:
    conn = connetti(db_path)
    try:
        # WAL: il lavoro in sottofondo scrive mentre le app leggono, senza bloccarsi.
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("CREATE TABLE IF NOT EXISTS versione_schema (versione INTEGER NOT NULL)")
        riga = conn.execute("SELECT MAX(versione) AS v FROM versione_schema").fetchone()
        attuale = riga["v"] or 0
        for versione, sql, semina in MIGRAZIONI:
            if versione <= attuale:
                continue
            with transazione(conn):
                for istruzione in sql.split(";"):
                    if istruzione.strip():
                        conn.execute(istruzione)
                if semina:
                    semina(conn)
                conn.execute("INSERT INTO versione_schema (versione) VALUES (?)", (versione,))
    finally:
        conn.close()


def get_conn(request: Request):
    conn = connetti(request.app.state.settings.db_path)
    try:
        yield conn
    finally:
        conn.close()


def hash_segreto(segreto: str) -> str:
    """Nel database finisce solo l'hash di token e codici, mai il segreto."""
    return hashlib.sha256(segreto.encode("utf-8")).hexdigest()


def prossimo_seq(conn: sqlite3.Connection) -> int:
    """Contatore unico delle novità: ogni mail valutata e ogni bozza pronta ne prende
    uno. Il cursore di /api/novita è questo numero, così una mail valutata DOPO che
    il telefono ha già visto il suo id fa comunque scattare la notifica."""
    conn.execute("UPDATE contatori SET valore = valore + 1 WHERE nome = 'novita'")
    return conn.execute("SELECT valore FROM contatori WHERE nome = 'novita'").fetchone()["valore"]


def seq_attuale(conn: sqlite3.Connection) -> int:
    return conn.execute("SELECT valore FROM contatori WHERE nome = 'novita'").fetchone()["valore"]


def impostazioni(conn: sqlite3.Connection) -> dict:
    valori = dict(IMPOSTAZIONI_INIZIALI)
    for riga in conn.execute("SELECT chiave, valore FROM impostazioni"):
        valori[riga["chiave"]] = json.loads(riga["valore"])
    return valori


def salva_impostazione(conn: sqlite3.Connection, chiave: str, valore) -> None:
    conn.execute(
        "INSERT INTO impostazioni (chiave, valore) VALUES (?, ?)"
        " ON CONFLICT (chiave) DO UPDATE SET valore = excluded.valore",
        (chiave, json.dumps(valore)),
    )


def gruppo_per_chiave(conn: sqlite3.Connection, chiave: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM gruppi WHERE chiave = ?", (chiave,)).fetchone()


def segna_riassunto_oggi_da_rifare(conn: sqlite3.Connection) -> None:
    conn.execute("UPDATE riassunto_oggi SET da_rifare = 1 WHERE id = 1")


def adesso() -> str:
    return clock.adesso()


def leggi_interno(conn: sqlite3.Connection, chiave: str, base=None):
    """Valori interni del server (mai esposti dalle API), es. il limite dell'ultima pulizia."""
    riga = conn.execute("SELECT valore FROM interno WHERE chiave = ?", (chiave,)).fetchone()
    return json.loads(riga["valore"]) if riga else base


def salva_interno(conn: sqlite3.Connection, chiave: str, valore) -> None:
    conn.execute(
        "INSERT INTO interno (chiave, valore) VALUES (?, ?) ON CONFLICT (chiave) DO UPDATE SET valore = excluded.valore",
        (chiave, json.dumps(valore)),
    )


INTERROTTO_RIAVVIO_CASELLA = (
    "Il collegamento si è interrotto perché il server si è riavviato. Usa «Ricollega» per riprovare."
)
INTERROTTO_RIAVVIO_INVIO = (
    "L'invio si è interrotto. Prima di riprovare Sol controlla in Posta inviata se la mail è già partita."
)


def ripristina_dopo_riavvio(db_path: str) -> None:
    """All'avvio nessun lavoro è in corso: quello che risulta "in corso" è rimasto a metà
    (riavvio, intoppo) e va rimesso in uno stato da cui si riparte. Senza, l'app
    ricaricherebbe ogni 2 secondi un riassunto "in_corso" per sempre."""
    conn = connetti(db_path)
    try:
        with transazione(conn):
            conn.execute("UPDATE riassunti SET stato = 'in_coda' WHERE stato = 'in_corso'")
            conn.execute("UPDATE riassunto_oggi SET stato = 'in_coda' WHERE stato = 'in_corso'")
            # Il thread che aspettava l'accesso a Microsoft non c'è più: non finirà mai.
            conn.execute(
                "UPDATE caselle SET stato = 'errore', errore = ?, errore_tipo = 'credenziali', in_collegamento_dal = NULL"
                " WHERE stato = 'in_collegamento'",
                (INTERROTTO_RIAVVIO_CASELLA,),
            )
            # Un invio a metà: forse è partito, forse no. Al prossimo tentativo si controlla.
            conn.execute(
                "UPDATE bozze SET stato = 'errore', errore = ?, invio_ambiguo = 1, in_invio_dal = NULL"
                " WHERE in_invio_dal IS NOT NULL",
                (INTERROTTO_RIAVVIO_INVIO,),
            )
    finally:
        conn.close()
