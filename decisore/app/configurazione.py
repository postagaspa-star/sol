"""Impostazioni lette dalle variabili d'ambiente.

Tutto ha un valore di base pensato per il NAS di casa (Intel N100, 4 core, niente GPU):
si cambia dal compose senza ricostruire l'immagine.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path


class ErroreConfigurazione(ValueError):
    """Impostazione sbagliata: il decisore non parte e lo dice in /salute (frase semplice)."""


def _intero(nome: str, base: int, minimo: int = 1) -> int:
    grezzo = os.environ.get(nome, "").strip()
    if not grezzo:
        return base
    try:
        valore = int(grezzo)
    except ValueError:
        raise ErroreConfigurazione(f"{nome} deve essere un numero intero.") from None
    if valore < minimo:
        raise ErroreConfigurazione(f"{nome} deve essere almeno {minimo}.")
    return valore


def _decimale(nome: str, base: float) -> float:
    grezzo = os.environ.get(nome, "").strip()
    if not grezzo:
        return base
    try:
        return float(grezzo)
    except ValueError:
        raise ErroreConfigurazione(f"{nome} deve essere un numero.") from None


def _testo(nome: str, base: str) -> str:
    return os.environ.get(nome, "").strip() or base


def _impostato(nome: str) -> bool:
    return bool(os.environ.get(nome, "").strip())


# Il modello è fissato a un commit preciso del repo Hugging Face: se qualcuno ricarica
# il file con lo stesso nome, noi scarichiamo comunque quello verificato (stessa sha256).
GGUF_REPO = "bartowski/Qwen_Qwen3.5-4B-GGUF"
GGUF_REVISIONE = "4168f45a16a1290d65a4ec0fa312ae917a4c15d6"
GGUF_FILE = "Qwen_Qwen3.5-4B-Q4_K_M.gguf"
GGUF_BYTE = 3013027808
GGUF_SHA256 = "13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983"

# SemIf costruisce i prompt con il tokenizzatore di riferimento (non con quello del GGUF)
# e controlla che i due coincidano: serve lo stesso commit usato da SemIf.
TOKENIZZATORE_REPO = "Qwen/Qwen3.5-4B"
TOKENIZZATORE_REVISIONE = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a"
TOKENIZZATORE_FILE = [
    "tokenizer.json", "tokenizer_config.json", "vocab.json", "merges.txt",
    "chat_template.jinja", "config.json",
]


# "insieme": tutte le domande prima della mail, risposte lette in fila (più veloce sul NAS);
# "sol": una domanda per volta dopo la mail, come SemIf ma in italiano;
# "semif": il prompt originale di SemIf (inglese, JSON), per confronto.
STILI = {"insieme", "sol", "semif"}


@dataclass(frozen=True)
class Configurazione:
    cartella_modelli: Path
    threads: int
    contesto: int
    max_token_mail: int
    stile_prompt: str
    temperatura: float
    gguf_url: str
    gguf_file: str
    gguf_byte: int
    gguf_sha256: str
    tokenizzatore_repo: str
    tokenizzatore_revisione: str
    nome_modello: str

    @property
    def percorso_gguf(self) -> Path:
        return self.cartella_modelli / self.gguf_file

    @property
    def cartella_tokenizzatore(self) -> Path:
        return self.cartella_modelli / f"tokenizzatore-{self.tokenizzatore_revisione[:12]}"


def leggi() -> Configurazione:
    stile = _testo("SOL_STILE_PROMPT", "insieme")
    if stile not in STILI:
        raise ErroreConfigurazione(f"SOL_STILE_PROMPT deve essere uno tra: {', '.join(sorted(STILI))}.")
    gguf_file = _testo("SOL_GGUF_FILE", GGUF_FILE)
    # Cambiare file senza dire quanto pesa e la sua impronta vorrebbe dire scaricare un file
    # che non torna mai con i valori di base: giri infiniti da 3 GB. Meglio non partire.
    cambiato = gguf_file != GGUF_FILE or _impostato("SOL_GGUF_URL")
    if cambiato and not (_impostato("SOL_GGUF_BYTE") and _impostato("SOL_GGUF_SHA256")):
        raise ErroreConfigurazione(
            "Hai cambiato il file del modello (SOL_GGUF_FILE o SOL_GGUF_URL) senza dire quanto pesa "
            "e la sua impronta: servono anche SOL_GGUF_BYTE e SOL_GGUF_SHA256.")
    impronta = _testo("SOL_GGUF_SHA256", GGUF_SHA256).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", impronta):
        raise ErroreConfigurazione("SOL_GGUF_SHA256 deve essere un'impronta sha256 (64 caratteri esadecimali).")
    return Configurazione(
        cartella_modelli=Path(_testo("SOL_CARTELLA_MODELLI", "/modelli")),
        # 3 thread di base: il quarto core resta a Pactum, legal-rag e Home Assistant.
        threads=_intero("SOL_THREADS", 3),
        # 4096 token bastano per una mail lunga più domande, e per /genera prompt + risposta.
        contesto=_intero("SOL_CONTESTO", 4096, minimo=512),
        # Per decidere basta l'inizio della mail: oltre questi token si taglia (0 = mai).
        max_token_mail=_intero("SOL_MAX_TOKEN_MAIL", 1000, minimo=0),
        stile_prompt=stile,
        temperatura=_decimale("SOL_TEMPERATURA", 0.5),
        gguf_url=_testo(
            "SOL_GGUF_URL",
            f"https://huggingface.co/{GGUF_REPO}/resolve/{GGUF_REVISIONE}/{gguf_file}",
        ),
        gguf_file=gguf_file,
        gguf_byte=_intero("SOL_GGUF_BYTE", GGUF_BYTE),
        gguf_sha256=impronta,
        tokenizzatore_repo=_testo("SOL_TOKENIZZATORE_REPO", TOKENIZZATORE_REPO),
        tokenizzatore_revisione=_testo("SOL_TOKENIZZATORE_REVISIONE", TOKENIZZATORE_REVISIONE),
        nome_modello=_testo("SOL_NOME_MODELLO", "Qwen3.5-4B Q4_K_M"),
    )
