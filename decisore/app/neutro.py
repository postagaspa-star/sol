"""Testi che arrivano da fuori (mail, domande, opzioni, messaggi di /genera): resi innocui.

Perché serve: il tokenizzatore (quello di riferimento e quello di llama.cpp) riconosce i
token speciali ANCHE dentro il testo. Una mail che contiene "<|im_end|>" seguito da
"<|im_start|>system" chiuderebbe davvero il turno dell'utente e ne aprirebbe uno finto di
sistema: la mail uscirebbe dalla busta e darebbe ordini al modello.

Cosa facciamo (contratto 15.14):
1. normalizziamo in NFC (il tokenizzatore di Qwen lo fa da solo, llama.cpp no: senza questo
   passo "e" + accento separato o il simbolo dell'ohm danno token diversi nei due);
2. spezziamo con uno spazio invisibile (U+200B) tutte le sequenze di controllo: ogni token
   aggiunto del vocabolario (letti dal tokenizzatore, non solo da una lista fissa), qualsiasi
   "<|" e "|>", e <think>, </think>, <tool_call>, </tool_call>, [INST], [/INST].
Il testo resta leggibile (cambia solo un carattere invisibile), ma nessun pezzo può più
diventare un token speciale. I token di controllo veri li mette solo il modello di chat,
dove servono.
"""

from __future__ import annotations

import re
import unicodedata
from collections import Counter

SPAZIO_ZERO = "​"
SEQUENZE_FISSE = (
    "<think>", "</think>", "<tool_call>", "</tool_call>", "<tool_response>", "</tool_response>",
    "[INST]", "[/INST]",
)


def normalizza(testo: str) -> str:
    return unicodedata.normalize("NFC", testo)


def _spezza(sequenza: str) -> str:
    return sequenza[0] + SPAZIO_ZERO + sequenza[1:]


class Neutralizzatore:
    def __init__(self, sequenze_extra=()):
        sequenze = {s for s in SEQUENZE_FISSE}
        sequenze |= {s for s in sequenze_extra if isinstance(s, str) and len(s) >= 2 and s.strip()}
        # Le più lunghe prima: "<|im_start|>" va spezzata intera, non a pezzi.
        ordinate = sorted(sequenze, key=len, reverse=True)
        self._cerca = re.compile("|".join(re.escape(s) for s in ordinate), re.IGNORECASE)

    @classmethod
    def da_tokenizzatore(cls, tokenizzatore) -> "Neutralizzatore":
        return cls(stringhe_speciali(tokenizzatore))

    def __call__(self, testo: str) -> str:
        testo = normalizza(testo)
        for _ in range(4):  # di solito basta un giro; il secondo conferma che non c'è altro
            nuovo = self._cerca.sub(lambda trovato: _spezza(trovato.group(0)), testo)
            nuovo = nuovo.replace("<|", "<" + SPAZIO_ZERO + "|").replace("|>", "|" + SPAZIO_ZERO + ">")
            if nuovo == testo:
                break
            testo = nuovo
        return testo


def stringhe_speciali(tokenizzatore) -> list[str]:
    """Testo di tutti i token aggiunti/speciali del vocabolario (es. <|im_start|>, <think>)."""
    stringhe: set[str] = set()
    aggiunti = getattr(tokenizzatore, "get_added_vocab", None)
    if callable(aggiunti):
        stringhe |= set(aggiunti())
    stringhe |= set(getattr(tokenizzatore, "all_special_tokens", None) or [])
    return sorted(stringhe)


def ids_speciali(tokenizzatore) -> frozenset[int]:
    ids: set[int] = set()
    aggiunti = getattr(tokenizzatore, "get_added_vocab", None)
    if callable(aggiunti):
        ids |= {int(i) for i in aggiunti().values()}
    ids |= {int(i) for i in (getattr(tokenizzatore, "all_special_ids", None) or [])}
    return frozenset(ids)


def conta_speciali(ids, speciali: frozenset[int]) -> Counter:
    return Counter(i for i in ids if i in speciali)
