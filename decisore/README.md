# Sol — decisore

Servizio interno che sceglie tra opzioni fisse ("a quale gruppo appartiene questa mail?",
"serve una risposta?", "quanto è urgente?") leggendo le probabilità direttamente dai logit
di un modello open congelato, senza fargli scrivere niente. Contratto: `docs/contratto-api.md`,
sezioni 13 e 15.14.

- Base: [SemIf (ex OpenJev)](https://github.com/TheoLeeCJ/SemIf-OpenJev), MIT, commit fissato nel `Dockerfile`.
- Modello: Qwen3.5-4B, file GGUF `Q4_K_M` (3,0 GB), su CPU con llama.cpp.
- Il modello NON è nell'immagine: al primo avvio si scarica in `decisore/modelli` (con ripresa e
  controllo sha256). Nel frattempo `/salute` risponde `in_avvio` con `percentuale` (0-100).

## Come funziona

1. Il modello si carica **una volta** (`load_model` del backend llama.cpp di SemIf, che
   controlla anche che il vocabolario del GGUF sia quello del tokenizzatore di riferimento).
2. La scelta è la lettera (A, B, C...) con il logit più alto subito dopo il prompt;
   `probabilita` è il softmax sulle sole lettere delle opzioni. Nessun token generato.
3. Tre modi di costruire il prompt (`SOL_STILE_PROMPT`):
   - **`insieme`** (di base): istruzioni + tutte le domande + inizio busta formano una "testa"
     calcolata una volta per ogni insieme di domande e salvata in memoria (fino a 3 insiemi).
     Per ogni mail si calcola solo il testo della mail e una coda corta; le risposte si leggono
     in fila nel formato `1: B` / `2: A` / `3: C` (dopo la 1 si aggiunge la lettera scelta e
     si legge la 2). Dopo la mail c'è un richiamo: le istruzioni scritte dentro la mail non contano.
   - `sol`: una domanda per volta dopo la mail (lo schema di SemIf, in italiano).
   - `semif`: il prompt originale di SemIf (inglese, JSON) tramite `score_shared`, per confronto.
4. `/genera` usa **lo stesso contesto llama.cpp** (nessun secondo modello in memoria).

All'avvio il decisore prepara la testa delle **domande consigliate**
(`app/domande_consigliate.json`: `gruppo`, `da_rispondere`, `urgenza`, `truffa`, in quest'ordine)
e quella delle stesse senza `gruppo` (quando il gruppo l'ha già deciso una regola): se il server
di Sol manda esattamente quei testi, nessuna mail paga la preparazione (~45 s sul NAS). La quarta
domanda, "possibile truffa" (contratto 16), nello stile `insieme` costa solo la lettura di una
risposta in più (`4: A`).

## Testi che arrivano da fuori (mail, domande, opzioni, messaggi)

Il tokenizzatore riconosce i token speciali anche dentro il testo: una mail con `<|im_end|>`
e `<|im_start|>system` aprirebbe davvero un turno finto di sistema. Per questo ogni testo
esterno, prima di entrare in un prompt (`app/neutro.py`):

1. viene normalizzato in **NFC** (il tokenizzatore di Qwen lo fa, llama.cpp no: senza questo
   passo "e" + accento separato o il simbolo dell'ohm davano token diversi e un errore 500);
2. ha le **sequenze di controllo spezzate** con uno spazio invisibile (U+200B): tutti i token
   aggiunti del vocabolario (letti dal tokenizzatore), qualsiasi `<|` e `|>`, e `<think>`,
   `</think>`, `<tool_call>`, `</tool_call>`, `[INST]`, `[/INST]`. Il testo resta leggibile.
3. Rete di sicurezza: il prompt finale deve contenere esattamente i token speciali del modello
   di chat (system, user, assistant), né uno di più; altrimenti 422 `mail_non_valida`.

I tag `<mail>`/`</mail>` dentro la mail diventano `[mail]`, così la mail non può chiudere la busta.
Gli id delle opzioni tornano nella risposta esattamente come li ha mandati il server: si
neutralizza solo il testo mostrato al modello.

## Turni, stop e limiti

- Un solo contesto = una cosa per volta. Le decisioni passano in fila. **Al massimo una
  `/genera`**: un'altra mentre la prima è in corso riceve 503 `decisore_non_disponibile`.
- Lo scrittore legge il prompt a pezzi da 64 token e scrive token per token; tra un pezzo e
  l'altro lascia passare una decisione in attesa, ma solo dopo aver fatto un po' di strada
  (8 token o 2 secondi da quando ha ripreso il modello): con tante mail in coda non si blocca.
- **Stop**: `scadenza_secondi` (in `/genera`, e facoltativa anche in `/decidi`) oppure il client
  che chiude la connessione fermano il lavoro al primo controllo (tra un pezzo e l'altro) e
  liberano il modello. Una richiesta già scaduta o abbandonata mentre era in fila non viene calcolata.
- Corpo della richiesta oltre 1 MB rifiutato; tetti ai testi (domanda 1000 caratteri, id 100,
  descrizione 500, messaggi 50 000 l'uno e 100 000 in tutto, al massimo 20 messaggi).
- Per decidere si usa solo l'inizio della mail (`SOL_MAX_TOKEN_MAIL`, base 1000 token).

## Errori

Sempre `{"errore": "frase in italiano", "codice": "..."}`:

| Codice | HTTP | Quando |
|---|---|---|
| `mail_non_valida` | 422 | problema di QUESTA mail (vuota, oltre 1 MB, caratteri che il modello non legge in modo affidabile) |
| `domande_non_valide` | 422 | domande/opzioni fuori regola o troppo lunghe: è la configurazione |
| `non_valido` | 422 | richiesta scritta male (non JSON, `scadenza_secondi` sbagliata, messaggi troppo lunghi) |
| `decisore_non_disponibile` | 503 | in avvio o fermo, un'altra `/genera` in corso, richiesta scaduta o abbandonata |
| `errore_interno` | 500 | imprevisto (dettagli nel registro del container) |

`/salute`: `pronto` | `in_avvio` (con `percentuale`) | `non_disponibile` (con `errore`, una frase
semplice; i dettagli tecnici sono nel registro). Se l'avvio fallisce riprova dopo 1, 2, 4, 8, 16,
32 minuti e poi si ferma; gli errori definitivi (configurazione, file sbagliato sul server) non si
riprovano. Oltre ai campi del contratto: `massa_opzioni` per domanda, e in `/genera` `token`,
`millisecondi`, `token_al_secondo`.

## Impostazioni (variabili d'ambiente)

| Variabile | Base | Cosa fa |
|---|---|---|
| `SOL_THREADS` | 3 | thread CPU di llama.cpp (il NAS ha 4 core: uno resta agli altri servizi) |
| `SOL_STILE_PROMPT` | `insieme` | `insieme`, `sol` oppure `semif` |
| `SOL_CONTESTO` | 4096 | token massimi: mail + domande, o prompt + risposta di `/genera` |
| `SOL_MAX_TOKEN_MAIL` | 1000 | per decidere si usa solo l'inizio della mail (0 = tutta) |
| `SOL_TEMPERATURA` | 0.5 | fantasia dello scrittore di riserva |
| `SOL_GGUF_FILE`, `SOL_GGUF_URL` | — | altro file del modello: servono anche `SOL_GGUF_BYTE` e `SOL_GGUF_SHA256`, altrimenti il decisore non parte e lo dice in `/salute` |

## Sul NAS

Il decisore fa parte del compose unico di Sol (`docker-compose.yml` nella radice del repo, sul
NAS in `/volume1/docker/Sol-NAS`, progetto `sol`, rete esterna `sol-rete`). Per ricostruire e
riavviare SOLO il decisore:

```bash
cd /volume1/docker/Sol-NAS
docker compose -p sol up -d --build sol-decisore
curl -s 127.0.0.1:8300/salute
docker exec sol-decisore python /servizio/prova/prova_mail.py --domande chiare --genera --scrittore
docker stats --no-stream sol-decisore
```

Il server di Sol lo chiama su `http://sol-decisore:8300` dentro `sol-rete`. Il modello sta in
`/volume1/docker/Sol-NAS/decisore/modelli`. Non lanciare altri processi col modello dentro il
container: due copie superano il limite di memoria e il sistema rilegge il modello dal disco.

## Test sul PC (senza modello)

```bash
python -m venv .venv && .venv/Scripts/pip install -r requirements-test.txt
.venv/Scripts/python -m pytest -q tests
```

I test usano un tokenizzatore e un motore finti (`tests/finti.py`) che si comportano come quelli
veri nei punti che contano (modello di chat ChatML, token speciali riconosciuti nel testo, NFC
solo nel tokenizzatore di riferimento).
