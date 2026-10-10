"""Abbinamento dei dispositivi (contratto, sezione 2).

Dal NAS: `docker exec sol python -m app.abbina` stampa un codice di 8 cifre, valido
15 minuti, una volta sola. Un codice nuovo annulla quelli ancora aperti. Nel database
c'è solo l'hash del codice e del token.

Contro chi prova i codici a caso: 10 tentativi sbagliati in 15 minuti, contati su tutto
il server (dietro Tailscale o Cloudflare tutte le richieste hanno lo stesso indirizzo),
fanno rispondere 429 e annullano tutti i codici ancora aperti: serve un codice nuovo.
Controllo e registrazione del tentativo stanno nella stessa transazione, così due
richieste insieme non possono superare il limite."""

import secrets
import sqlite3
import sys
from datetime import datetime, timedelta

from . import clock
from .config import (
    CIFRE_CODICE,
    DURATA_CODICE_MINUTI,
    FINESTRA_TENTATIVI_MINUTI,
    TENTATIVI_SBAGLIATI_MASSIMI,
    assicura_cartella_db,
    carica_settings,
)
from .db import connetti, hash_segreto, init_db, transazione

BLOCCATO = "bloccato"


def nuovo_codice(conn: sqlite3.Connection, ora: datetime | None = None) -> tuple[str, datetime]:
    ora = ora or clock.now()
    codice = f"{secrets.randbelow(10 ** CIFRE_CODICE):0{CIFRE_CODICE}d}"
    scade = ora + timedelta(minutes=DURATA_CODICE_MINUTI)
    with transazione(conn):
        conn.execute(
            "UPDATE codici_abbinamento SET annullato_il = ? WHERE usato_il IS NULL AND annullato_il IS NULL",
            (clock.iso(ora),),
        )
        conn.execute(
            "INSERT INTO codici_abbinamento (codice_hash, creato_il, scade_il) VALUES (?, ?, ?)",
            (hash_segreto(codice), clock.iso(ora), clock.iso(scade)),
        )
    return codice, scade


def _sbagli_recenti(conn: sqlite3.Connection, ora: datetime) -> int:
    dal = clock.iso(ora - timedelta(minutes=FINESTRA_TENTATIVI_MINUTI))
    return conn.execute("SELECT COUNT(*) AS n FROM tentativi_abbinamento WHERE il > ?", (dal,)).fetchone()["n"]


def abbina(conn: sqlite3.Connection, codice: str, nome: str, tipo: str, ora: datetime,
           ip: str = "sconosciuto") -> tuple[str, int] | str | None:
    """Consuma il codice e crea il dispositivo → (token, id).
    BLOCCATO se ci sono stati troppi tentativi sbagliati; None se il codice è sbagliato,
    scaduto o già usato (per chi prova sono la stessa cosa)."""
    with transazione(conn):
        if _sbagli_recenti(conn, ora) >= TENTATIVI_SBAGLIATI_MASSIMI:
            return BLOCCATO
        riga = conn.execute(
            "SELECT id FROM codici_abbinamento WHERE codice_hash = ? AND usato_il IS NULL"
            " AND annullato_il IS NULL AND scade_il > ?",
            (hash_segreto(codice), clock.iso(ora)),
        ).fetchone()
        if riga is None:
            conn.execute("INSERT INTO tentativi_abbinamento (ip, il) VALUES (?, ?)", (ip, clock.iso(ora)))
            if _sbagli_recenti(conn, ora) >= TENTATIVI_SBAGLIATI_MASSIMI:
                # Qualcuno sta provando a indovinare: nessun codice aperto resta buono.
                conn.execute(
                    "UPDATE codici_abbinamento SET annullato_il = ? WHERE usato_il IS NULL AND annullato_il IS NULL",
                    (clock.iso(ora),),
                )
            return None
        token = secrets.token_hex(32)  # 64 caratteri
        conn.execute("UPDATE codici_abbinamento SET usato_il = ? WHERE id = ?", (clock.iso(ora), riga["id"]))
        cur = conn.execute(
            "INSERT INTO dispositivi (nome, tipo, token_hash, abbinato_il) VALUES (?, ?, ?, ?)",
            (nome, tipo, hash_segreto(token), clock.iso(ora)),
        )
        return token, cur.lastrowid


def main() -> None:
    settings = carica_settings()
    assicura_cartella_db(settings.db_path)
    init_db(settings.db_path)
    conn = connetti(settings.db_path)
    try:
        codice, scade = nuovo_codice(conn)
    finally:
        conn.close()
    ora_scadenza = scade.astimezone(clock.FUSO).strftime("%H:%M")
    print(f"Codice di abbinamento: {codice}")
    print(f"Vale una volta sola, fino alle {ora_scadenza} ({DURATA_CODICE_MINUTI} minuti).")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
