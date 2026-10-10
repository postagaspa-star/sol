"""Generazione di testo (scrittore di riserva) sullo STESSO motore llama.cpp del decisore.

Perché lo stesso motore: il NAS ha ~4,9 GB liberi e il modello ne occupa ~3. Un secondo
contesto llama.cpp (o un secondo processo) raddoppierebbe la memoria. Qui usiamo il
contesto di SemIf token per token: si calcola il prompt, si sceglie il token successivo
dai logit, lo si aggiunge, e così via.

Il contesto è uno solo, quindi decidere e scrivere non possono andare insieme. Per non
bloccare lo smistamento delle mail durante una bozza lunga, chiamiamo `pausa(token)`:
- tra un pezzo e l'altro del prompt (pezzi da 64 token: sul NAS ~6 s l'uno);
- tra un token scritto e l'altro.
Il Motore lì controlla se la richiesta va fermata (client scollegato, scadenza) e se c'è
una decisione in attesa: in quel caso salva lo stato della scrittura, la lascia passare
e poi lo ripristina.

I testi dei messaggi (che di solito contengono mail) passano dal Neutralizzatore come in
/decidi: nessuna sequenza scritta dentro può diventare un cambio di turno.
"""

from __future__ import annotations

import time

import numpy

from .domande import ErroreRichiesta
from .neutro import Neutralizzatore, conta_speciali, ids_speciali
from .punteggio import decodifica_a_blocchi

# Valori prudenti per un modello piccolo: poca fantasia, niente ripetizioni a pappagallo.
TOP_K = 40
TOP_P = 0.9
PENALITA_RIPETIZIONE = 1.1
FINESTRA_RIPETIZIONE = 64


def campiona(logit, temperatura: float, generatore, recenti: list[int]) -> int:
    valori = numpy.array(logit, dtype=numpy.float64)
    if recenti and PENALITA_RIPETIZIONE != 1.0:
        uniche = numpy.unique(numpy.asarray(recenti[-FINESTRA_RIPETIZIONE:], dtype=numpy.int64))
        scelti = valori[uniche]
        valori[uniche] = numpy.where(scelti > 0, scelti / PENALITA_RIPETIZIONE, scelti * PENALITA_RIPETIZIONE)
    if temperatura <= 0:
        return int(valori.argmax())
    k = min(TOP_K, valori.size)
    candidati = numpy.argpartition(valori, -k)[-k:]
    candidati = candidati[numpy.argsort(valori[candidati])[::-1]]
    scalati = valori[candidati] / temperatura
    pesi = numpy.exp(scalati - scalati.max())
    pesi /= pesi.sum()
    cumulata = numpy.cumsum(pesi)
    tieni = int(numpy.searchsorted(cumulata, TOP_P) + 1)
    pesi = pesi[:tieni] / pesi[:tieni].sum()
    return int(candidati[generatore.choice(tieni, p=pesi)])


class Scrittore:
    def __init__(self, tokenizzatore, motore, fine_testo, contesto: int, temperatura: float = 0.5, seme=None):
        self.tok = tokenizzatore
        self.motore = motore
        # fine_testo(token) -> True per i token di fine (es. <|im_end|>): lì ci si ferma.
        self.fine_testo = fine_testo
        self.contesto = contesto
        self.temperatura = temperatura
        self.generatore = numpy.random.default_rng(seme)
        self.neutro = Neutralizzatore.da_tokenizzatore(tokenizzatore)
        self.speciali = ids_speciali(tokenizzatore)

    def _render(self, messaggi: list[dict]) -> str:
        return self.tok.apply_chat_template(
            messaggi, tokenize=False, add_generation_prompt=True, enable_thinking=False)

    def _codifica(self, testo: str) -> list[int]:
        return self.tok.encode(testo, add_special_tokens=False)

    def codifica(self, messaggi: list[dict]) -> list[int]:
        puliti = [{"role": m["role"], "content": self.neutro(m["content"])} for m in messaggi]
        ids = self._codifica(self._render(puliti))
        # Rete di sicurezza: solo i token speciali dei turni veri, né uno di più.
        attesi = self._codifica(self._render([{"role": m["role"], "content": "X"} for m in messaggi]))
        if conta_speciali(ids, self.speciali) != conta_speciali(attesi, self.speciali):
            raise ErroreRichiesta("I messaggi contengono sequenze di controllo del modello che non si "
                                  "possono rendere innocue.")
        return ids

    def genera(self, messaggi: list[dict], max_token: int, pausa=None) -> dict:
        ids = self.codifica(messaggi)
        if len(ids) + max_token > self.contesto:
            raise ErroreRichiesta(
                f"Richiesta troppo lunga per lo scrittore locale: {len(ids)} token di testo "
                f"più {max_token} di risposta superano il limite di {self.contesto}.")
        inizio = time.perf_counter()
        self.motore.clear()
        logit = decodifica_a_blocchi(self.motore, ids, 0, True, tra_blocchi=pausa)
        dopo_prompt = time.perf_counter()
        uscita: list[int] = []
        posizione = len(ids)
        for _ in range(max_token):
            token = campiona(logit, self.temperatura, self.generatore, ids[-FINESTRA_RIPETIZIONE:] + uscita)
            if self.fine_testo(token):
                break
            uscita.append(token)
            if len(uscita) == max_token:
                break
            if pausa is not None:
                pausa(1)
            logit = self.motore._decode([token], posizione, 0, True)
            posizione += 1
        fine = time.perf_counter()
        testo = self.tok.decode(uscita, skip_special_tokens=True).strip()
        secondi_scrittura = fine - dopo_prompt
        return {
            "testo": testo,
            "token": len(uscita),
            "token_prompt": len(ids),
            "millisecondi": round((fine - inizio) * 1000),
            "millisecondi_prompt": round((dopo_prompt - inizio) * 1000),
            "token_al_secondo": round(len(uscita) / secondi_scrittura, 2) if secondi_scrittura > 0 and uscita else 0.0,
        }
