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
| `cervello`   | interfaccia verso l'AI: `CervelloEco` (milestone 1), `CervelloClaude` = Haiku 4.5 (milestone 2) |
| `conferme`   | il cancello: rischio, esiti parlanti, normalizzazione frase, `CancelloConferme` |
| `esecuzione` | interfacce repo + adapter per provider (Google = primario in scrittura, M365 in lettura) |
| `dati`       | impostazioni (DataStore) e registro azioni JSONL (`filesDir/registro/AAAA-MM.jsonl`) |

## Milestone 1 — l'eco (questa)

Pipeline completa senza AI: «Ehi Sol» → beep → comando → «Ho sentito: …».
Serve a collaudare handoff del microfono, qualità di trascrizione sul campo
(tutto annotato nel registro) e batteria.

**Prova subito, senza chiavi:** installa, concedi i permessi, «Avvia
l'ascolto», poi pulsante **Parla** (in app o nella notifica).

**Wake word vera:**
1. Account su https://console.picovoice.ai → copia l'AccessKey in
   `C:\Users\andre\sol-keys\sol.properties` → `picovoiceAccessKey=...`
   (fuori da repo e OneDrive, stesso schema di pactum-keys).
2. Console → Porcupine → wake word «Ehi Sol», lingua Italiano, piattaforma
   Android → scarica `ehi_sol_android.ppn` in `app/src/main/assets/`.
3. Aggiungi `porcupine_params_it.pv` (repo GitHub Porcupine, `lib/common`)
   sempre in `assets/`.
Con la sola chiave e senza i due file, parola di prova inglese: «Porcupine».

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
- [ ] M2 — CervelloClaude (Haiku 4.5, 5 tool promemoria) + Room + verde/giallo con annulla
- [ ] M3 — cancello rosso completo (card con payload esatto + «timbro e firma») su `elimina_promemoria`
- [ ] M4 — collaudo sul campo con papà (metriche dal registro), poi fase 2 calendario
