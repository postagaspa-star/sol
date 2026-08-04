# Sol — assistente vocale

App Android nativa: promemoria, calendario ed email gestiti **solo a voce**, con
wake word «Ehi Sol» sempre attiva e conferma esplicita sulle azioni
irreversibili.

## Perché esiste

Nasce per un utente reale, non generico: mio padre, perito industriale, che
lavora in cantiere con le mani occupate e non userebbe mai un'app di task da
tastiera. Il vincolo di partenza è che tutto il giro — dettatura, conferma,
esito — deve funzionare senza guardare lo schermo.

Questo rende il problema più severo di un normale assistente vocale: se l'utente
non guarda, ogni errore silenzioso è un errore invisibile. Da qui le due regole
che governano il codice.

## Le due regole

1. **Claude propone, il codice dispone.** Il modello emette proposte di azione
   (tool call); la classe di rischio — VERDE / GIALLO / ROSSO — la decide
   `conferme/Rischio.kt`, mai il modello. Un LLM non è autorizzato a stabilire
   quanto è pericolosa la propria richiesta.
2. **Ogni esito parla.** Successo, annullamento, timeout, errore: il giro si
   chiude sempre con una frase. Il silenzio non è mai una risposta valida.

## Come funziona un giro

«Ehi Sol» → beep → comando → Haiku 4.5 con un system prompt che contiene data,
ora e promemoria correnti → ogni `tool_use` passa dal cancello:

- **VERDE** (elenca, annulla): esegue e basta.
- **GIALLO** (crea, completa, sposta): esegue subito, poi il microfono resta
  aperto ~30 secondi e «annulla» — a voce nuda, senza wake word — rimette tutto
  com'era.
- **ROSSO** (elimina): Sol legge il riepilogo e aspetta la frase «timbro e
  firma», confrontata in modo deterministico. Timeout, frase diversa o «annulla»
  annullano l'azione, e lo dice a voce.

## Stack

Kotlin, Android nativo (minSdk 26). Wake word con Porcupine (Picovoice),
trascrizione via `SpeechRecognizer`, sintesi vocale TTS. Cervello: Claude Haiku
4.5 su REST con loop tool scritto a mano. Archivio promemoria in JSON locale con
scrittura atomica, sveglie esatte via `AlarmManager`. Zero OAuth, zero cloud
oltre a Claude.

## Note tecniche

- **Degradazione a gradini**: senza chiave Picovoice la wake word sparisce ma
  resta il pulsante Parla; senza chiave Anthropic entra `CervelloEco`, che
  ripete e basta. L'app non si rifiuta mai di partire.
- **Un solo punto che esegue**: `EsecutoreProposte` è l'unico componente che
  tocca lo stato. Il cancello sta a monte, quindi non esistono scorciatoie.
- **Limite di sistema, non bug**: dopo un riavvio Android blocca il microfono ai
  servizi auto-avviati. Sol se ne accorge e manda una notifica: un tocco e
  riparte.

## Stato

M1 e M2 completate (pipeline vocale, 6 tool, archivio e sveglie, verde/giallo con
annulla a caldo, rosso vocale). In corso: card rossa a schermo e collaudo sul
campo.

Le chiavi API vivono fuori dal repository, in un file `sol.properties` non
versionato.
