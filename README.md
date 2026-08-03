# Sol — assistente vocale per papà

App Android nativa: task, calendario ed email gestiti solo a voce, con wake
word «Ehi Sol» sempre attiva e doppia conferma sulle azioni irreversibili.

## Le due regole della casa

1. **Claude propone, il codice dispone.** Il modello emette proposte di
   azione (tool call); la classe di rischio — VERDE / GIALLO / ROSSO — la
   decide `conferme/Rischio.kt`, mai il modello. Il ROSSO richiede la card a
   schermo **e** la frase vocale «timbro e firma» (confronto deterministico).
2. **Ogni esito parla.** Successo, annullamento, timeout, errore: il giro si
   chiude sempre con una frase. Il silenzio non è mai una risposta valida.

## Moduli (package sotto `eu.stgm.sol`)

| Package      | Ruolo |
|--------------|-------|
| `ascolto`    | foreground service, wake word (Porcupine), trascrizione (`SpeechRecognizer`), voce (TTS), tile, riavvio |
| `cervello`   | Claude Haiku 4.5 via REST (`ClaudeApi`, loop tool scritto a mano), tool (`DefinizioniTool`), `CervelloEco` di ripiego |
| `conferme`   | il cancello: rischio, esiti parlanti, normalizzazione frase, `CancelloConferme` |
| `esecuzione` | `EsecutoreProposte` (unico punto che esegue), archivio promemoria su file, sveglie (`PianificatoreAllarmi`) |
| `dati`       | impostazioni (DataStore), registro azioni JSONL, lettura date a voce |

## Come funziona un giro (milestone 2)

«Ehi Sol» → beep → comando → Haiku 4.5 col system prompt che contiene data,
ora e la lista dei promemoria → ogni tool_use passa dal cancello:

- **VERDE** (lista, annulla): esegue e basta.
- **GIALLO** (crea, completa, sposta): esegue subito; a fine risposta il
  microfono resta aperto qualche secondo e «annulla» — a voce nuda, senza
  wake word — rimette tutto com'era (finestra di 30 secondi, vale anche
  con «Ehi Sol, annulla»).
- **ROSSO** (elimina): Sol legge il riepilogo e aspetta «timbro e firma».
  Timeout, frase diversa o «annulla» = azione annullata, **detto a voce**.
  Un rosso annullato chiude il turno: la frase la dice il codice, non il
  modello. La card a schermo arriva con la milestone 3.

Le scadenze suonano come notifiche (sveglie esatte, rimesse a ogni riavvio
o aggiornamento). L'archivio è un JSON locale con scrittura atomica: zero
OAuth, zero cloud oltre a Claude.

## Chiavi (`C:\Users\andre\sol-keys\sol.properties`, fuori da repo e OneDrive)

```
picovoiceAccessKey=...   # wake word; senza → pulsante Parla
anthropicApiKey=...      # Claude; senza → CervelloEco (ripete e basta)
```

Per la chiave Anthropic: console.anthropic.com, chiave dedicata con spend
limit basso (5-15 €/mese). Wake word: vedi `app/src/main/assets/LEGGIMI.md`.

**Limite di sistema da sapere:** dopo un riavvio del telefono Android blocca
il microfono ai servizi auto-avviati → arriva una notifica, un tocco e Sol
riparte. Non è un bug nostro.

## Build

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK in `app/build/outputs/apk/debug/`. Toolchain identica a Pactum
(AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, compileSdk 35, minSdk 26).

## Tabella di marcia

- [x] M1 — scheletro + eco vocale (pipeline, registro, tile, riavvio)
- [x] M2 — CervelloClaude (Haiku 4.5, 6 tool), archivio + sveglie, verde/giallo con annulla a caldo, rosso vocale
- [ ] M3 — card rossa a schermo (payload esatto + pulsante esplicito) accanto alla via vocale
- [ ] M4 — collaudo sul campo con papà (metriche dal registro), poi fase 2 calendario
