"""Il Motore: tiene il modello caricato UNA volta e mette in fila decisioni e scritture.

All'avvio, in un thread a parte (così /salute risponde subito "in_avvio"):
1. scarica tokenizzatore e GGUF nel volume, se mancano;
2. li carica con `semif_phase1.llamacpp_backend.load_model` (che ricalcola la sha256);
3. prepara la parte fissa del prompt (stile "insieme": la testa delle domande consigliate;
   stile "sol": istruzioni + inizio busta), così la prima mail vera non paga quel costo.
Se qualcosa va storto riprova con attese crescenti (1, 2, 4... minuti) e dopo l'ultimo
tentativo si ferma: /salute dice "non_disponibile" con una frase semplice; i dettagli
tecnici vanno nel registro del container. Gli errori "definitivi" (configurazione, file
sbagliato sul server) non si riprovano affatto.

Un solo contesto llama.cpp = una sola operazione per volta: chi passa e quando lo decide
l'Arbitro (decisioni in fila, una sola scrittura, che cede il modello a turni).
"""

from __future__ import annotations

import errno
import json
import logging
import socket
import threading
import time
import urllib.error
from pathlib import Path

from . import scaricatore
from .arbitro import SCRITTORE, Arbitro, Quanto, Segnale
from .configurazione import TOKENIZZATORE_FILE, Configurazione, ErroreConfigurazione
from .domande import Domanda, leggi_decidi, neutralizza_domande, righe_semif, risposta_domanda

log = logging.getLogger("decisore.motore")

IN_AVVIO = "in_avvio"
PRONTO = "pronto"
NON_DISPONIBILE = "non_disponibile"
# Attese tra un tentativo di avvio e l'altro (secondi); finite queste, ci si ferma.
ATTESE_RIPROVA = (60, 120, 240, 480, 960, 1920)
FILE_DOMANDE_CONSIGLIATE = Path(__file__).with_name("domande_consigliate.json")
_dormi = time.sleep  # sostituibile nei test


class NonPronto(RuntimeError):
    pass


class Occupato(RuntimeError):
    """C'è già una /genera in corso: al massimo una alla volta (contratto 15.14)."""


def _di_rete(errore: BaseException) -> bool:
    if isinstance(errore, (urllib.error.URLError, ConnectionError, TimeoutError, socket.timeout, socket.gaierror)):
        return True
    modulo = type(errore).__module__ or ""
    return modulo.startswith(("httpx", "httpcore", "huggingface_hub", "requests", "urllib3", "ssl"))


def frase_errore(errore: BaseException) -> str:
    """Frase semplice per /salute: il testo tecnico dell'eccezione resta nel registro."""
    if isinstance(errore, (ErroreConfigurazione, scaricatore.ErroreScaricamento)):
        return str(errore)
    if _di_rete(errore):
        return "Non riesco a scaricare il modello: controlla la connessione a internet del NAS."
    if isinstance(errore, OSError) and errore.errno == errno.ENOSPC:
        return "Il disco del NAS è pieno: non c'è spazio per il modello."
    return "Il modello non si carica: i dettagli tecnici sono nel registro del container."


_RICHIAMO_REGISTRO = None


def _zittisci_llama(llama_cpp) -> None:
    """llama.cpp scrive una riga a ogni ripristino di stato: teniamo solo avvisi ed errori."""
    global _RICHIAMO_REGISTRO
    try:
        import ctypes
        import sys

        @llama_cpp.llama_log_callback
        def _richiamo(livello, testo, _dati):
            if livello in (3, 4):  # GGML_LOG_LEVEL_WARN, GGML_LOG_LEVEL_ERROR
                sys.stderr.write(testo.decode("utf-8", "replace"))

        _RICHIAMO_REGISTRO = _richiamo  # se venisse raccolto, llama.cpp chiamerebbe memoria libera
        llama_cpp.llama_log_set(_richiamo, ctypes.c_void_p(0))
    except Exception:  # noqa: BLE001 - solo estetica dei log
        log.warning("non riesco a ridurre i messaggi di llama.cpp", exc_info=True)


def prepara_domande_consigliate(lettore) -> None:
    """Calcola subito la testa delle domande che il server di Sol manda di solito.

    Se il server usa testi diversi non succede niente di male: la sua prima mail
    paga la preparazione (~40 s sul NAS) e poi anche quella testa resta in memoria.
    """
    try:
        dati = json.loads(FILE_DOMANDE_CONSIGLIATE.read_text(encoding="utf-8"))
        _, domande = leggi_decidi({"stato": "x", "domande": dati["domande"]})
        lettore.prepara(domande)
    except Exception:  # noqa: BLE001 - è solo un anticipo, non deve bloccare l'avvio
        log.warning("domande consigliate non preparate", exc_info=True)


class Motore:
    def __init__(self, conf: Configurazione | None, carica=None):
        self.conf = conf
        # `carica` restituisce (decidi_fn, scrittore, motore_llama): sostituibile nei test.
        self._carica = carica or self._carica_vero
        self.stato = IN_AVVIO
        self.dettaglio = "avvio"
        self.errore: str | None = None
        self.percentuale = 0
        self._decidi_fn = None
        self._scrittore = None
        self._llama = None
        self._arbitro = Arbitro()
        self._scrittura = threading.Lock()  # al massimo una /genera: si prende senza aspettare

    @classmethod
    def fermo(cls, frase: str) -> "Motore":
        """Motore che non parte (configurazione sbagliata): /salute lo dice, il resto dà 503."""
        motore = cls(None, carica=lambda: None)
        motore.stato, motore.errore = NON_DISPONIBILE, frase
        motore.dettaglio = "fermo: va corretta la configurazione del container"
        return motore

    # --- avvio ---------------------------------------------------------------------
    def avvia_in_sottofondo(self) -> threading.Thread:
        filo = threading.Thread(target=self._avvia, name="avvio-decisore", daemon=True)
        filo.start()
        return filo

    def _avvia(self) -> None:
        for numero, attesa in enumerate((*ATTESE_RIPROVA, None), start=1):
            try:
                self._decidi_fn, self._scrittore, self._llama = self._carica()
                self.stato, self.dettaglio, self.errore, self.percentuale = PRONTO, "pronto", None, 100
                log.info("decisore pronto")
                return
            except Exception as errore:  # noqa: BLE001 - qualsiasi guasto va gestito qui
                log.exception("avvio fallito (tentativo %s)", numero)
                frase = frase_errore(errore)
                self.stato = NON_DISPONIBILE
                definitivo = getattr(errore, "definitivo", False) or isinstance(errore, ErroreConfigurazione)
                if definitivo or attesa is None:
                    self.errore = f"{frase} Ho smesso di riprovare: dopo aver sistemato, riavvia sol-decisore."
                    self.dettaglio = "fermo: serve un intervento (dettagli nel registro del container)"
                    log.error("smetto di riprovare: %s", frase)
                    return
                minuti = max(1, attesa // 60)
                self.errore = f"{frase} Riprovo tra {minuti} minut{'o' if minuti == 1 else 'i'}."
                self.dettaglio = f"riprovo tra {minuti} minuti (tentativo {numero} di {len(ATTESE_RIPROVA) + 1})"
                _dormi(attesa)
                self.stato = IN_AVVIO

    def _avanzamento(self, fatti: int, totali: int) -> None:
        self.percentuale = min(99, fatti * 100 // max(totali, 1))
        self.dettaglio = f"scarico il modello: {self.percentuale}%"

    def _carica_vero(self):
        conf = self.conf
        self.dettaglio = "scarico il tokenizzatore"
        cartella_tok = scaricatore.prepara_tokenizzatore(
            conf.tokenizzatore_repo, conf.tokenizzatore_revisione, TOKENIZZATORE_FILE,
            conf.cartella_tokenizzatore)
        self.dettaglio = "scarico il modello"
        gguf = scaricatore.scarica(conf.gguf_url, conf.percorso_gguf, conf.gguf_byte,
                                   conf.gguf_sha256, avanzamento=self._avanzamento)
        self.percentuale = 100
        self.dettaglio = "controllo e carico il modello in memoria"
        import llama_cpp
        from semif_phase1 import llamacpp_backend

        _zittisci_llama(llama_cpp)

        etichetta = f"{conf.tokenizzatore_repo}@{conf.tokenizzatore_revisione}"
        backend, tok, metadati = llamacpp_backend.load_model(
            str(cartella_tok), etichetta, Path(gguf),
            threads=conf.threads, context_tokens=conf.contesto)
        if conf.gguf_sha256 and metadati["gguf"]["sha256"] != conf.gguf_sha256:
            # File rovinato (es. disco, copia a metà): lo togliamo e al prossimo giro si riscarica.
            backend.close()
            Path(gguf).unlink(missing_ok=True)
            raise scaricatore.ErroreScaricamento(
                "Il file del modello sul disco è rovinato (impronta diversa): lo cancello e lo riscarico.")
        motore = backend.engine
        vocab = backend.vocab
        log.info("modello caricato: %s", metadati)

        def verifica(testo: str) -> list[int]:
            return llamacpp_backend._gguf_tokenize(llama_cpp, vocab, testo)

        if conf.stile_prompt == "semif":
            decidi_fn = self._decidi_semif_fn(llamacpp_backend, backend, tok, metadati)
        elif conf.stile_prompt == "insieme":
            from .insieme import LettoreInsieme

            lettore = LettoreInsieme(tok, motore, conf.contesto, verifica=verifica,
                                     max_token_mail=conf.max_token_mail)
            self.dettaglio = "preparo le domande consigliate"
            prepara_domande_consigliate(lettore)
            decidi_fn = lettore.decidi
        else:
            from .punteggio import LettoreSol

            lettore = LettoreSol(tok, motore, conf.contesto, verifica=verifica,
                                 max_token_mail=conf.max_token_mail)
            self.dettaglio = "preparo la parte fissa del prompt"
            lettore.prepara_testa()
            decidi_fn = lettore.decidi

        from .scrittore import Scrittore

        fine_ids = {i for i in (tok.eos_token_id, tok.convert_tokens_to_ids("<|im_end|>")) if isinstance(i, int)}

        def fine_testo(token: int) -> bool:
            if token in fine_ids:
                return True
            controllo = getattr(llama_cpp, "llama_vocab_is_eog", None)
            return bool(controllo(vocab, token)) if controllo else False

        scrittore = Scrittore(tok, motore, fine_testo, motore.context_tokens, conf.temperatura)
        # Tengo un riferimento al backend: se venisse raccolto, il finalizzatore di SemIf
        # libererebbe il modello sotto i nostri piedi.
        self._backend = backend
        return decidi_fn, scrittore, motore

    def _decidi_semif_fn(self, llamacpp_backend, backend, tok, metadati):
        """Variante con il prompt originale di SemIf (inglese, JSON), per confronto."""
        from .domande import MailNonValida
        from .neutro import Neutralizzatore, conta_speciali, ids_speciali
        from .punteggio import FRASE_SPECIALI_MAIL, accorcia_mail

        neutro = Neutralizzatore.da_tokenizzatore(tok)
        speciali = ids_speciali(tok)
        conf = self.conf

        def codifica(testo):
            return tok.encode(testo, add_special_tokens=False)

        def decidi_fn(stato, domande, controlla=None):
            stato = accorcia_mail(codifica, neutro(stato), conf.max_token_mail)
            domande = neutralizza_domande(domande, neutro)
            if conta_speciali(codifica(stato), speciali):
                raise MailNonValida(FRASE_SPECIALI_MAIL)
            if controlla is not None:
                controlla()
            for _ in range(6):
                try:
                    risultati, _ = llamacpp_backend.score_shared(
                        backend, tok, righe_semif(stato, domande), metadati, metadati["max_prompt_tokens"])
                    return [(r["probabilities"], r["allowed_token_mass"]) for r in risultati]
                except ValueError as errore:
                    if "exceed limit" not in str(errore) or len(stato) < 400:
                        raise
                    stato = stato[: int(len(stato) * 0.75)]
            raise ValueError("Mail troppo lunga anche dopo averla accorciata")
        return decidi_fn

    # --- uso -------------------------------------------------------------------------
    def salute(self) -> dict:
        esito = {"stato": self.stato, "modello": self.conf.nome_modello if self.conf else "finto"}
        if self.stato == IN_AVVIO:
            esito["percentuale"] = int(self.percentuale)
        if self.stato != PRONTO:
            esito["dettaglio"] = self.dettaglio
            if self.errore:
                esito["errore"] = self.errore
        return esito

    def _controlla_pronto(self) -> None:
        if self.stato != PRONTO:
            raise NonPronto(self.dettaglio)

    def decidi(self, stato: str, domande: list[Domanda], segnale: Segnale | None = None) -> dict:
        self._controlla_pronto()
        segnale = segnale or Segnale()
        inizio = time.perf_counter()
        biglietto = self._arbitro.entra_decisione(segnale)
        try:
            # Se il client se n'è andato (o la scadenza è passata) mentre aspettava, il
            # calcolo non si fa: nessuno leggerebbe la risposta.
            segnale.controlla()
            esiti = self._decidi_fn(stato, domande, segnale.controlla)
        finally:
            self._arbitro.esci(biglietto)
        risposte = {d.id: risposta_domanda(d, p, m) for d, (p, m) in zip(domande, esiti)}
        millisecondi = round((time.perf_counter() - inizio) * 1000)
        # Nel registro solo numeri, mai il testo delle mail.
        log.info("decisione: %d domande, %d caratteri, %d ms", len(domande), len(stato), millisecondi)
        return {"risposte": risposte, "millisecondi": millisecondi}

    def genera(self, messaggi: list[dict], max_token: int, segnale: Segnale | None = None) -> dict:
        self._controlla_pronto()
        segnale = segnale or Segnale()
        if not self._scrittura.acquire(blocking=False):
            raise Occupato("Lo scrittore locale sta già scrivendo un'altra bozza: riprova tra poco.")
        try:
            segnale.controlla()
            self._arbitro.entra_scrittore(segnale)
            try:
                esito = self._scrittore.genera(messaggi, max_token, pausa=self._crea_pausa(segnale))
            finally:
                self._arbitro.esci(SCRITTORE)
        finally:
            self._scrittura.release()
        log.info("scrittura: %d token in %d ms (%.2f token/s)", esito["token"], esito["millisecondi"],
                 esito["token_al_secondo"])
        return esito

    def _crea_pausa(self, segnale: Segnale):
        quanto = Quanto()

        def pausa(token: int) -> None:
            """Tra un pezzo di prompt o un token e l'altro: stop? decisioni in attesa?"""
            segnale.controlla()
            quanto.aggiungi(token)
            if not self._arbitro.decisioni_in_attesa() or not quanto.fatto:
                return
            salvato = self._llama.save_state()
            self._arbitro.cedi(segnale)  # se nel frattempo arriva lo stop, esce di qui senza modello
            self._llama.restore_state(salvato)
            quanto.ricomincia()
        return pausa
