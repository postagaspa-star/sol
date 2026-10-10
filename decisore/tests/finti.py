"""Tokenizzatore e motore finti: permettono di provare la logica senza il modello vero.

Il tokenizzatore finto si comporta come quello vero di Qwen nei punti che contano:
- modello di chat ChatML (<|im_start|>ruolo ... <|im_end|>, <think></think> vuoto);
- i token speciali scritti nel testo vengono riconosciuti come token speciali (come fa
  `encode` di Hugging Face e `llama_tokenize(parse_special=True)` di llama.cpp);
- `encode` normalizza in NFC (come il normalizer di Qwen), `codifica_come_llama` no
  (come llama.cpp): così si vedono le differenze sui caratteri scomposti.
"""

from __future__ import annotations

import re
import time
import unicodedata

import numpy

VOCABOLARIO = 1 << 17  # basta per i caratteri usati nei test (ord < 131072)

LETTERE = "ABCDEFGHIJKLMNOP"
# " A", " B"... sono token singoli, come nel vocabolario vero di Qwen.
BASE_SPAZIO_LETTERA = 0x1D000
SPECIALI = {
    "<|im_start|>": 0x1E000,
    "<|im_end|>": 0x1E001,
    "<|endoftext|>": 0x1E002,
    "<think>": 0x1E003,
    "</think>": 0x1E004,
    "<tool_call>": 0x1E005,
    "</tool_call>": 0x1E006,
    "<|fim_prefix|>": 0x1E007,
}
DA_ID = {v: k for k, v in SPECIALI.items()}
_RE_SPECIALI = re.compile("|".join(re.escape(s) for s in sorted(SPECIALI, key=len, reverse=True)))


def token_spazio(lettera: str) -> int:
    return BASE_SPAZIO_LETTERA + LETTERE.index(lettera)


def _pezzo(testo: str, nfc: bool) -> list[int]:
    if nfc:
        testo = unicodedata.normalize("NFC", testo)
    ids, i = [], 0
    while i < len(testo):
        if testo[i] == " " and i + 1 < len(testo) and testo[i + 1] in LETTERE:
            ids.append(token_spazio(testo[i + 1]))
            i += 2
        else:
            ids.append(ord(testo[i]))
            i += 1
    return ids


def _codifica(testo: str, nfc: bool) -> list[int]:
    ids, posizione = [], 0
    for trovato in _RE_SPECIALI.finditer(testo):
        ids += _pezzo(testo[posizione:trovato.start()], nfc)
        ids.append(SPECIALI[trovato.group(0)])
        posizione = trovato.end()
    return ids + _pezzo(testo[posizione:], nfc)


def codifica_come_llama(testo: str) -> list[int]:
    """Come `_gguf_tokenize` di SemIf: token speciali riconosciuti, nessuna normalizzazione."""
    return _codifica(testo, nfc=False)


class TokenizzatoreFinto:
    eos_token_id = 3

    def encode(self, testo: str, add_special_tokens: bool = False) -> list[int]:
        return _codifica(testo, nfc=True)

    def decode(self, ids, skip_special_tokens: bool = False) -> str:
        pezzi = []
        for i in ids:
            if i in DA_ID:
                if not skip_special_tokens:
                    pezzi.append(DA_ID[i])
            elif i >= BASE_SPAZIO_LETTERA:
                pezzi.append(" " + LETTERE[i - BASE_SPAZIO_LETTERA])
            elif not (skip_special_tokens and i < 32 and i not in (9, 10)):
                pezzi.append(chr(i))
        return "".join(pezzi)

    def get_added_vocab(self) -> dict[str, int]:
        return dict(SPECIALI)

    def apply_chat_template(self, messaggi, tokenize=False, add_generation_prompt=True, enable_thinking=False):
        testo = "".join(f"<|im_start|>{m['role']}\n{m['content']}<|im_end|>\n" for m in messaggi)
        if add_generation_prompt:
            testo += "<|im_start|>assistant\n"
            if not enable_thinking:
                testo += "<think>\n\n</think>\n\n"
        return testo

    def convert_tokens_to_ids(self, token: str) -> int:
        return SPECIALI.get(token, 3)


class MotoreFinto:
    """Imita `_Engine` di SemIf: tiene la sequenza di token "in memoria" e la salva/ripristina.

    I logit premiano la lettera scritta in `preferita`, oppure, se il testo in memoria
    contiene una parola chiave di `parole`, la lettera associata. `ritardo_token` simula
    il tempo di calcolo (secondi per token), per le prove di precedenza e di stop.
    """

    def __init__(self, contesto: int = 4096, preferita: str = "A", parole=None, ritardo_token: float = 0.0):
        self.context_tokens = contesto + 64
        self.memoria: list[int] = []
        self.preferita = preferita
        self.parole = parole or {}
        self.ritardo_token = ritardo_token
        self.chiamate: list[tuple] = []
        self.decodifiche: list[tuple[float, int]] = []  # (istante, quanti token)

    def _logit(self) -> numpy.ndarray:
        logit = numpy.zeros(VOCABOLARIO, dtype=numpy.float32)
        testo = TokenizzatoreFinto().decode(self.memoria)
        lettera = self.preferita
        for parola, scelta in self.parole.items():
            if parola in testo:
                lettera = scelta
                break
        logit[ord(lettera)] = 8.0
        logit[token_spazio(lettera)] = 8.0
        return logit

    def clear(self) -> None:
        self.chiamate.append(("clear",))
        self.memoria = []

    def prefill(self, ids: list[int]) -> None:
        self.chiamate.append(("prefill", len(ids)))
        self._decode(ids, 0, 0, False)

    def _decode(self, ids, inizio, sequenza, vuoi_logit):
        assert ids, "mai decodificare una lista vuota"
        assert inizio == len(self.memoria), f"posizione {inizio} ma in memoria {len(self.memoria)}"
        if self.ritardo_token:
            time.sleep(self.ritardo_token * len(ids))
        self.decodifiche.append((time.monotonic(), len(ids)))
        self.memoria = self.memoria + list(ids)
        assert len(self.memoria) <= self.context_tokens
        return self._logit() if vuoi_logit else None

    def save_state(self):
        self.chiamate.append(("save", len(self.memoria)))
        return list(self.memoria)

    def restore_state(self, stato) -> None:
        self.chiamate.append(("restore", len(stato)))
        self.memoria = list(stato)

    def branch_logits(self, lunghezza_prefisso, coda):
        return self._decode(coda, lunghezza_prefisso, 0, True)
