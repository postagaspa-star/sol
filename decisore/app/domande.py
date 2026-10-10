"""Lettura e controllo delle richieste, e forma delle risposte.

Il controllo è fatto a mano (non con i modelli pydantic) per avere errori in italiano
semplice, nella forma del contratto: {"errore": "...", "codice": "..."}.
I codici (contratto 15.14) dicono al server di chi è il problema:
- `mail_non_valida`: è QUESTA mail (il server la passa subito all'utente);
- `domande_non_valide`: sono le domande/opzioni, cioè la configurazione (riguarda tutte le mail);
- `non_valido`: richiesta scritta male (per esempio non è JSON).
"""

from __future__ import annotations

from dataclasses import dataclass

from .neutro import normalizza

# SemIf legge una lettera per opzione (A..P): oltre 16 opzioni non ci sono lettere.
MAX_OPZIONI = 16
# Ogni domanda in più costa un passaggio del modello (qualche secondo sul NAS).
MAX_DOMANDE = 8
# Oltre questa lunghezza lo stato viene comunque tagliato prima di arrivare al modello
# (e poi accorciato a SOL_MAX_TOKEN_MAIL token).
MAX_CARATTERI_STATO = 200_000
# Tetti ai testi della configurazione: una domanda o una descrizione lunghissima non è
# una configurazione sensata, e finirebbe per non entrare nel contesto del modello.
MAX_CARATTERI_DOMANDA = 1_000
MAX_CARATTERI_ID = 100
MAX_CARATTERI_DESCRIZIONE = 500
MAX_TOKEN_GENERA = 2048
MAX_MESSAGGI = 20
MAX_CARATTERI_MESSAGGIO = 50_000
MAX_CARATTERI_MESSAGGI = 100_000
MAX_SCADENZA_SECONDI = 3_600
RUOLI = {"sistema": "system", "utente": "user", "assistente": "assistant"}


class ErroreRichiesta(ValueError):
    """Richiesta scritta male: diventa un 422 con una frase leggibile."""

    codice = "non_valido"


class DomandeNonValide(ErroreRichiesta):
    """Domande od opzioni fuori regola: è la configurazione, vale per tutte le mail."""

    codice = "domande_non_valide"


class MailNonValida(ErroreRichiesta):
    """Il problema è proprio questa mail: il server la mette subito da controllare."""

    codice = "mail_non_valida"


@dataclass(frozen=True)
class Opzione:
    id: str
    descrizione: str
    # Testo dell'id mostrato al modello (neutralizzato); l'id vero resta quello del server,
    # che lo riceve identico nella risposta.
    etichetta: str | None = None

    @property
    def per_modello(self) -> str:
        # Il modello vede solo questo testo, mai l'id da solo: mettere il nome del gruppo
        # davanti alla descrizione aiuta (es. "Fornitori: un fornitore manda un'offerta").
        nome = self.etichetta if self.etichetta is not None else normalizza(self.id)
        descrizione = normalizza(self.descrizione).strip()
        if not descrizione or descrizione == nome:
            return nome
        return f"{nome}: {descrizione}"


@dataclass(frozen=True)
class Domanda:
    id: str
    domanda: str
    opzioni: tuple[Opzione, ...]


def neutralizza_domande(domande: list[Domanda], neutro) -> list[Domanda]:
    """Copie delle domande con i testi per il modello resi innocui; gli id restano quelli veri."""
    return [
        Domanda(d.id, neutro(d.domanda), tuple(
            Opzione(o.id, neutro(o.descrizione),
                    etichetta=neutro(o.etichetta if o.etichetta is not None else o.id))
            for o in d.opzioni))
        for d in domande
    ]


def _testo(valore, cosa: str, errore, massimo: int | None = None) -> str:
    if not isinstance(valore, str) or not valore.strip():
        raise errore(f"Manca {cosa}, oppure è vuoto.")
    if massimo is not None and len(valore) > massimo:
        raise errore(f"Il testo per {cosa} è troppo lungo (al massimo {massimo} caratteri).")
    return valore


def leggi_decidi(corpo) -> tuple[str, list[Domanda]]:
    if not isinstance(corpo, dict):
        raise ErroreRichiesta("La richiesta deve essere un oggetto JSON.")
    stato = _testo(corpo.get("stato"), "il testo della mail (stato)", MailNonValida)
    stato = normalizza(stato[:MAX_CARATTERI_STATO])
    grezze = corpo.get("domande")
    if not isinstance(grezze, list) or not grezze:
        raise DomandeNonValide("Serve almeno una domanda.")
    if len(grezze) > MAX_DOMANDE:
        raise DomandeNonValide(f"Troppe domande: al massimo {MAX_DOMANDE} per volta.")
    domande: list[Domanda] = []
    visti: set[str] = set()
    for numero, grezza in enumerate(grezze, start=1):
        if not isinstance(grezza, dict):
            raise DomandeNonValide(f"La domanda {numero} non è scritta bene.")
        ident = _testo(grezza.get("id"), f"l'id della domanda {numero}", DomandeNonValide, MAX_CARATTERI_ID)
        if ident in visti:
            raise DomandeNonValide(f"Due domande hanno lo stesso id: {ident}.")
        visti.add(ident)
        testo = _testo(grezza.get("domanda"), f"il testo della domanda {ident}", DomandeNonValide,
                       MAX_CARATTERI_DOMANDA)
        opzioni_grezze = grezza.get("opzioni")
        if not isinstance(opzioni_grezze, list) or not 2 <= len(opzioni_grezze) <= MAX_OPZIONI:
            raise DomandeNonValide(f"La domanda {ident} deve avere da 2 a {MAX_OPZIONI} opzioni.")
        opzioni: list[Opzione] = []
        for opzione in opzioni_grezze:
            if not isinstance(opzione, dict):
                raise DomandeNonValide(f"Un'opzione della domanda {ident} non è scritta bene.")
            oid = _testo(opzione.get("id"), f"l'id di un'opzione della domanda {ident}", DomandeNonValide,
                         MAX_CARATTERI_ID)
            descrizione = opzione.get("descrizione", "")
            if descrizione is None:
                descrizione = ""
            if not isinstance(descrizione, str):
                raise DomandeNonValide(f"La descrizione dell'opzione {oid} deve essere un testo.")
            if len(descrizione) > MAX_CARATTERI_DESCRIZIONE:
                raise DomandeNonValide(f"La descrizione dell'opzione {oid} è troppo lunga "
                                       f"(al massimo {MAX_CARATTERI_DESCRIZIONE} caratteri).")
            opzioni.append(Opzione(oid, normalizza(descrizione)))
        if len({o.id for o in opzioni}) != len(opzioni):
            raise DomandeNonValide(f"Nella domanda {ident} due opzioni hanno lo stesso id.")
        domande.append(Domanda(ident, normalizza(testo), tuple(opzioni)))
    return stato, domande


def leggi_genera(corpo) -> tuple[list[dict], int]:
    if not isinstance(corpo, dict):
        raise ErroreRichiesta("La richiesta deve essere un oggetto JSON.")
    grezzi = corpo.get("messaggi")
    if not isinstance(grezzi, list) or not grezzi:
        raise ErroreRichiesta("Serve almeno un messaggio.")
    if len(grezzi) > MAX_MESSAGGI:
        raise ErroreRichiesta(f"Troppi messaggi: al massimo {MAX_MESSAGGI}.")
    messaggi = []
    totale = 0
    for numero, messaggio in enumerate(grezzi, start=1):
        if not isinstance(messaggio, dict) or messaggio.get("ruolo") not in RUOLI:
            raise ErroreRichiesta(
                f"Il messaggio {numero} deve avere ruolo 'sistema', 'utente' o 'assistente'.")
        testo = _testo(messaggio.get("testo"), f"il messaggio {numero}", ErroreRichiesta,
                       MAX_CARATTERI_MESSAGGIO)
        totale += len(testo)
        messaggi.append({"role": RUOLI[messaggio["ruolo"]], "content": normalizza(testo)})
    if totale > MAX_CARATTERI_MESSAGGI:
        raise ErroreRichiesta(f"I messaggi insieme sono troppo lunghi (al massimo {MAX_CARATTERI_MESSAGGI} caratteri).")
    if not any(m["role"] == "user" for m in messaggi):
        raise ErroreRichiesta("Serve almeno un messaggio con ruolo 'utente'.")
    max_token = corpo.get("max_token", 600)
    if isinstance(max_token, bool) or not isinstance(max_token, int) or not 1 <= max_token <= MAX_TOKEN_GENERA:
        raise ErroreRichiesta(f"max_token deve essere un numero intero tra 1 e {MAX_TOKEN_GENERA}.")
    return messaggi, max_token


def leggi_scadenza(corpo) -> float | None:
    """`scadenza_secondi` (facoltativa): oltre quel tempo la richiesta si lascia perdere."""
    valore = corpo.get("scadenza_secondi") if isinstance(corpo, dict) else None
    if valore is None:
        return None
    if isinstance(valore, bool) or not isinstance(valore, (int, float)) or not 0 < valore <= MAX_SCADENZA_SECONDI:
        raise ErroreRichiesta(f"scadenza_secondi deve essere un numero di secondi tra 1 e {MAX_SCADENZA_SECONDI}.")
    return float(valore)


def righe_semif(stato: str, domande: list[Domanda]) -> list[dict]:
    """Traduce le domande nel formato riga di SemIf (una riga per domanda, stesso stato)."""
    return [
        {
            "id": domanda.id,
            "state": stato,
            "question": domanda.domanda,
            "options": [{"id": o.id, "description": o.per_modello} for o in domanda.opzioni],
        }
        for domanda in domande
    ]


def risposta_domanda(domanda: Domanda, probabilita: list[float], massa: float | None = None) -> dict:
    if len(probabilita) != len(domanda.opzioni):
        raise ValueError("Numero di probabilità diverso dal numero di opzioni")
    per_opzione = {o.id: round(float(p), 4) for o, p in zip(domanda.opzioni, probabilita)}
    # A parità esatta vince la prima opzione: l'ordine lo decide chi chiede.
    migliore = max(range(len(probabilita)), key=lambda i: (probabilita[i], -i))
    esito = {"scelta": domanda.opzioni[migliore].id, "probabilita": per_opzione}
    if massa is not None:
        # Quota di probabilità che il modello dava alle lettere delle opzioni rispetto a
        # tutto il vocabolario: se è bassa, il modello "voleva dire altro" e la scelta
        # è meno affidabile anche quando la probabilità sembra alta.
        esito["massa_opzioni"] = round(float(massa), 4)
    return esito
