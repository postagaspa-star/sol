"""Scaricamento del modello nel volume /modelli al primo avvio.

Il modello (3 GB) non sta nell'immagine Docker: così l'immagine resta piccola, si
ricostruisce in fretta e il file sopravvive agli aggiornamenti del container.
Lo scaricamento riprende da dove si era fermato (file .parziale) e si controlla
dimensione e sha256 prima di usarlo.

Se il file sul server non è quello atteso (dimensione annunciata diversa, o arrivano più
byte del previsto) ci si ferma SUBITO, senza scaricare gigabyte inutili, e l'errore è
"definitivo": riprovare non serve, va corretta la configurazione.
"""

from __future__ import annotations

import hashlib
import logging
import os
import shutil
import urllib.request
from pathlib import Path

log = logging.getLogger("decisore.scaricatore")

BLOCCO = 1024 * 1024


class ErroreScaricamento(RuntimeError):
    """Il messaggio è già una frase per l'utente. `definitivo`: riprovare non serve."""

    def __init__(self, frase: str, definitivo: bool = False):
        super().__init__(frase)
        self.definitivo = definitivo


def _hash_file(percorso: Path, hasher) -> None:
    with percorso.open("rb") as flusso:
        for blocco in iter(lambda: flusso.read(8 * BLOCCO), b""):
            hasher.update(blocco)


def _dimensione_annunciata(risposta, partenza: int) -> int | None:
    """Dimensione TOTALE del file secondo il server (Content-Range o Content-Length)."""
    intestazioni = getattr(risposta, "headers", None)
    if not intestazioni:
        return None
    intervallo = intestazioni.get("Content-Range") or ""
    if "/" in intervallo:
        totale = intervallo.rsplit("/", 1)[1].strip()
        return int(totale) if totale.isdigit() else None
    lunghezza = (intestazioni.get("Content-Length") or "").strip()
    return partenza + int(lunghezza) if lunghezza.isdigit() else None


def _file_sbagliato(parziale: Path) -> ErroreScaricamento:
    parziale.unlink(missing_ok=True)
    return ErroreScaricamento(
        "Il file del modello sul server non ha la dimensione attesa: non è quello giusto. "
        "Controlla SOL_GGUF_URL, SOL_GGUF_BYTE e SOL_GGUF_SHA256.", definitivo=True)


def scarica(url: str, destinazione: Path, byte_attesi: int, sha256_atteso: str,
            avanzamento=None, apri=urllib.request.urlopen) -> Path:
    """Scarica `url` in `destinazione` verificando dimensione e sha256.

    Se il file finale esiste già con la dimensione giusta non si riscarica: la sha256
    la ricalcola comunque SemIf a ogni caricamento, e il Motore la confronta.
    """
    destinazione = Path(destinazione)
    if destinazione.is_file() and destinazione.stat().st_size == byte_attesi:
        return destinazione
    destinazione.parent.mkdir(parents=True, exist_ok=True)
    parziale = destinazione.with_name(destinazione.name + ".parziale")
    hasher = hashlib.sha256()
    gia = parziale.stat().st_size if parziale.exists() else 0
    if gia > byte_attesi:
        parziale.unlink()
        gia = 0
    if gia:
        _hash_file(parziale, hasher)
    if gia == byte_attesi:
        # Parziale già completo (es. scaricamento interrotto dopo l'ultimo byte, o file
        # messo a mano): chiedere altri byte darebbe errore 416, basta verificarlo.
        if not sha256_atteso or hasher.hexdigest() == sha256_atteso:
            os.replace(parziale, destinazione)
            return destinazione
        parziale.unlink()
        gia = 0
        hasher = hashlib.sha256()
    richiesta = urllib.request.Request(url, headers={"User-Agent": "sol-decisore/1.0"})
    if gia:
        richiesta.add_header("Range", f"bytes={gia}-")
    with apri(richiesta, timeout=60) as risposta:
        codice = getattr(risposta, "status", None) or risposta.getcode()
        if gia and codice != 206:
            # Il server non ha accettato la ripresa: si ricomincia da capo.
            log.info("ripresa non accettata (HTTP %s), riparto da zero", codice)
            gia = 0
            hasher = hashlib.sha256()
        annunciata = _dimensione_annunciata(risposta, gia)
        if annunciata is not None and annunciata != byte_attesi:
            raise _file_sbagliato(parziale)
        scritti = gia
        with parziale.open("ab" if gia else "wb") as uscita:
            while True:
                # Mai chiedere più di quanto manca (+1 byte per accorgersi se ce n'è di troppo).
                blocco = risposta.read(min(BLOCCO, byte_attesi - scritti + 1))
                if not blocco:
                    break
                if scritti + len(blocco) > byte_attesi:
                    uscita.close()
                    raise _file_sbagliato(parziale)
                uscita.write(blocco)
                hasher.update(blocco)
                scritti += len(blocco)
                if avanzamento is not None:
                    avanzamento(scritti, byte_attesi)
    if scritti < byte_attesi:
        # Connessione caduta: il parziale resta, al prossimo tentativo si riprende da lì.
        raise ErroreScaricamento("Lo scaricamento del modello si è interrotto: riprendo da dove ero rimasto.")
    if sha256_atteso and hasher.hexdigest() != sha256_atteso:
        parziale.unlink(missing_ok=True)
        log.error("sha256 sbagliata: %s invece di %s", hasher.hexdigest(), sha256_atteso)
        raise ErroreScaricamento("Il file del modello scaricato è rovinato (impronta diversa): lo riscarico.")
    os.replace(parziale, destinazione)
    return destinazione


def prepara_tokenizzatore(repo: str, revisione: str, file: list[str], cartella: Path) -> Path:
    """Scarica i file del tokenizzatore di riferimento (pochi MB) a un commit fissato."""
    cartella = Path(cartella)
    if cartella.is_dir() and all((cartella / nome).is_file() for nome in file):
        return cartella
    from huggingface_hub import hf_hub_download

    temporanea = cartella.with_name(cartella.name + ".parziale")
    shutil.rmtree(temporanea, ignore_errors=True)
    temporanea.mkdir(parents=True)
    for nome in file:
        hf_hub_download(repo_id=repo, filename=nome, revision=revisione, local_dir=str(temporanea))
    # hf_hub_download lascia una cartella .cache di servizio: non serve.
    shutil.rmtree(temporanea / ".cache", ignore_errors=True)
    shutil.rmtree(cartella, ignore_errors=True)
    os.replace(temporanea, cartella)
    return cartella
