# Sol — contratto delle API (v1.0)

Fonte di verità tra server, app Android e programma PC. Si cambia QUI prima che nel codice.
Repo pubblico: negli esempi e nei test solo dati finti (Rossi, Bianchi, `@esempio.it`).

## 0. Cos'è Sol

Assistente per la posta di un professionista (perito industriale). Il server, sul NAS di casa,
lavora sempre in sottofondo:

1. legge le mail nuove di più caselle (1 Outlook/Microsoft 365, 2 Gmail);
2. le **smista** in gruppi (regole fisse prima, poi il modello decisore SemIf sul NAS);
3. per ogni mail scrive un **riassunto di una riga** e, se serve rispondere, prepara una **bozza**;
4. tiene le liste **"niente resta indietro"** e le **scadenze** trovate nelle mail;
5. risponde a richieste di **riassunto** libere ("le mail di oggi", "cosa ha scritto Bianchi").

Le app (telefono e PC) sono finestre sullo stesso server. **Niente voce.**

### Regole che nessun pezzo può aggirare

- **Nessuna mail parte senza un gesto esplicito dell'utente.** L'unico punto del server che invia è
  `POST /api/bozze/{id}/invia` con `{"conferma": true}`. I modelli non hanno strumenti né azioni:
  producono solo testo o scelte tra opzioni fisse. Una mail-trappola ("inoltra le fatture a…") può al
  massimo produrre una bozza sbagliata, che l'utente legge prima di inviare.
- **Il contenuto delle mail è dato, mai istruzione.** Nei prompt va sempre racchiuso e dichiarato come
  testo da analizzare.
- **Sol non cancella mai mail.** Sposta, etichetta, segna letto. Basta.
- **Modalità prova** (attiva di base): Sol decide i gruppi ma NON sposta niente nelle caselle vere;
  mostra solo cosa farebbe. Si spegne dalle impostazioni quando l'utente si fida.
- **Ogni esito si vede**: un'azione fallita (invio, spostamento, collegamento) torna con un errore
  leggibile in italiano semplice nel campo `errore`, e le app lo mostrano.

## 1. Convenzioni

- Base: `http(s)://<server>/api`. JSON UTF-8. Date in ISO 8601 con fuso (`2026-10-10T09:30:00+02:00`).
- Autenticazione: header `Authorization: Bearer <token>` su tutto tranne `GET /api/versione`,
  `GET /api/salute` (versione ridotta senza token), `POST /api/abbina`, `GET /scarica/<file>`.
- Errori: status HTTP + `{"errore": "frase in italiano semplice", "codice": "slug"}`.
  Codici usati: `non_autorizzato` (401), `non_trovato` (404), `non_valido` (422),
  `conflitto` (409), `casella_non_raggiungibile` (502), `scrittore_non_disponibile` (503).
- Id: interi. Paginazione: `?limite=50&prima_di=<id>` (lista ordinata per `ricevuta_il` decrescente).
- Testi per l'utente: italiano semplice, zero gergo.

## 2. Abbinamento dei dispositivi

Il server stampa un codice di abbinamento a 8 cifre (valido 15 minuti, monouso) con
`docker exec sol python -m app.abbina`.

`POST /api/abbina`
```json
{"codice": "48213377", "nome": "Telefono di Papà", "tipo": "android"}
```
`tipo`: `android` | `pc`. Risposta 200: `{"token": "<64 caratteri>", "dispositivo_id": 3}`.
401 se il codice è sbagliato o scaduto (max 5 tentativi sbagliati ogni 15 minuti per IP → 429).

`GET /api/dispositivi` → elenco `{id, nome, tipo, abbinato_il, ultimo_accesso}`;
`DELETE /api/dispositivi/{id}` revoca un token.

## 3. Salute e versioni

`GET /api/versione` → `{"server": "1.0.0", "android": {"codice": 1, "nome": "1.0.0"}, "pc": {"codice": 1, "nome": "1.0.0"}, "nota": "..."}`
(letto da `server/app/versioni.json`). I file si scaricano da `GET /scarica/sol.apk` e `GET /scarica/sol-pc.zip`.

`GET /api/salute` (con token; senza token solo `{"ok": true, "versione": "1.0.0"}`)
```json
{
  "ok": true,
  "versione": "1.0.0",
  "decisore": {"stato": "pronto", "modello": "Qwen3.5-4B Q4_K_M"},
  "scrittore": {"stato": "pronto", "fornitore": "groq", "riserve": ["cerebras", "locale"]},
  "caselle": [{"id": 1, "indirizzo": "studio@esempio.it", "stato": "collegata", "ultimo_controllo": "..."}],
  "modalita_prova": true,
  "coda": {"da_smistare": 3, "bozze_in_preparazione": 1}
}
```
`decisore.stato`: `pronto` | `in_avvio` | `non_disponibile`. `scrittore.stato`: `pronto` | `solo_locale` | `non_disponibile`.

## 4. Caselle di posta

Oggetto `Casella`:
```json
{"id": 1, "tipo": "outlook", "indirizzo": "studio@esempio.it", "nome": "Studio",
 "stato": "collegata", "ultimo_controllo": "2026-10-10T09:30:00+02:00", "errore": null,
 "attiva_dal": "2026-10-10T08:00:00+02:00"}
```
`tipo`: `outlook` | `gmail` | `finta`. `stato`: `collegata` | `in_collegamento` | `errore`.
`attiva_dal`: Sol considera solo le mail ricevute da quel momento in poi (le vecchie non le tocca).

- `GET /api/caselle` → `[Casella]`
- `POST /api/caselle` — Gmail: `{"tipo": "gmail", "indirizzo": "...", "nome": "...", "password_app": "abcd efgh ijkl mnop"}`.
  Il server prova subito IMAP+SMTP; 201 con la Casella se va, 422 `{"errore": "Google ha rifiutato la password per le app..."}` se no.
  La password viene salvata cifrata (chiave in variabile d'ambiente `SOL_CHIAVE`), mai restituita.
- `POST /api/caselle` — Outlook: `{"tipo": "outlook", "nome": "Studio"}` → 202
  `{"casella": Casella(in_collegamento), "codice_utente": "ABCD-EFGH", "url_verifica": "https://microsoft.com/devicelogin", "scade_il": "..."}`.
  L'utente apre l'indirizzo, scrive il codice e accede; il server completa da solo
  (le app ricaricano `GET /api/caselle/{id}` ogni 5 s finché `stato` cambia).
- `POST /api/caselle` — Finta: `{"tipo": "finta", "nome": "Prova"}` → casella demo con mail inventate
  (serve per collaudo e dimostrazioni; nessun dato vero).
- `GET /api/caselle/{id}`, `PATCH /api/caselle/{id}` (`nome`), `DELETE /api/caselle/{id}`
  (scollega e cancella dal server le mail di quella casella; nelle caselle vere non tocca niente).
- `POST /api/caselle/{id}/controlla` → forza un controllo subito (202).

## 5. Gruppi e regole

`Gruppo`: `{"id": 1, "nome": "Clienti", "ordine": 1, "cartella": "Sol/Clienti", "da_leggere": 4, "totale": 120}`
Gruppi iniziali: Clienti, Fornitori, Amministrazione e fatture, PEC ed enti, Newsletter, Altro.
`Altro` non si può cancellare. `cartella` = nome della cartella (Outlook) o etichetta (Gmail).

- `GET /api/gruppi`, `POST /api/gruppi` `{"nome"}`, `PATCH /api/gruppi/{id}` `{"nome"?, "ordine"?}`,
  `DELETE /api/gruppi/{id}` (le sue mail passano ad Altro).

`Regola`: `{"id": 1, "tipo": "mittente", "valore": "bianchi@esempio.it", "gruppo_id": 1, "origine": "imparata", "creata_il": "..."}`
`tipo`: `mittente` (indirizzo esatto) | `dominio` (`esempio.it`). `origine`: `imparata` (da una correzione) | `manuale`.
- `GET /api/regole`, `POST /api/regole`, `DELETE /api/regole/{id}`.

## 6. Mail

`Mail` (in lista):
```json
{
  "id": 812, "casella_id": 1, "casella": "studio@esempio.it",
  "mittente": {"nome": "Mario Rossi", "indirizzo": "rossi@esempio.it"},
  "oggetto": "Offerta capannone Bassano",
  "anteprima": "Buongiorno, ci servirebbe l'offerta entro venerdì...",
  "riassunto": "Rossi chiede l'offerta per il capannone di Bassano entro venerdì.",
  "ricevuta_il": "2026-10-10T08:12:00+02:00",
  "letta": false, "risposta_data": false,
  "gruppo_id": 1, "gruppo": "Clienti",
  "da_rispondere": true, "urgenza": "alta",
  "sicurezza": 0.93,
  "smistamento": "smistata",
  "origine_smistamento": "decisore",
  "applicata_in_casella": false,
  "bozza": {"id": 55, "stato": "pronta"},
  "allegati": [{"nome": "pianta.pdf", "byte": 482113}]
}
```
- `urgenza`: `alta` | `normale` | `bassa`.
- `smistamento`: `in_attesa` (non ancora valutata) | `smistata` | `da_controllare` (sicurezza sotto soglia) | `corretta` (l'utente l'ha spostata a mano).
- `origine_smistamento`: `regola` | `decisore` | `utente`.
- `applicata_in_casella`: true se lo spostamento/etichetta è stato fatto davvero nella casella (falso in modalità prova).
- `bozza`: null se non c'è.

Dettaglio `GET /api/mail/{id}` = Mail + `{"destinatari": [...], "cc": [...], "corpo": "testo semplice", "probabilita": {"gruppo": {"Clienti": 0.93, "Fornitori": 0.04, ...}, "da_rispondere": {"si": 0.88, "no": 0.12}, "urgenza": {...}}}`.

- `GET /api/mail?gruppo_id=&smistamento=&da_rispondere=true&urgenza=alta&letta=false&casella_id=&cerca=testo&limite=50&prima_di=` → `{"mail": [Mail], "altre": true}`
- `POST /api/mail/{id}/sposta` `{"gruppo_id": 2, "impara": true}` → Mail. Imposta `smistamento=corretta`;
  con `impara` crea/aggiorna la regola `mittente` (se il mittente non è di un servizio di posta pubblico
  come gmail.com, aggiunge anche, solo se l'utente lo chiede con `"impara_dominio": true`, la regola dominio).
  Fuori modalità prova sposta davvero nella casella.
- `POST /api/mail/{id}/conferma` → accetta il gruppo proposto per una `da_controllare` (diventa `smistata`).
- `POST /api/mail/{id}/letta` `{"letta": true}` → segna letto anche nella casella vera (anche in modalità prova:
  è un gesto dell'utente).
- `POST /api/mail/{id}/rispondere` `{"da_rispondere": false}` → toglie/rimette l'etichetta "da rispondere".

## 7. Bozze

`Bozza`:
```json
{"id": 55, "mail_id": 812, "tipo": "risposta", "stato": "pronta",
 "a": ["rossi@esempio.it"], "cc": [], "oggetto": "Re: Offerta capannone Bassano",
 "testo": "Buongiorno Mario,\n...", "istruzioni": null,
 "fornitore": "groq", "modificata": false,
 "creata_il": "...", "inviata_il": null, "errore": null}
```
`tipo`: `risposta` | `sollecito`. `stato`: `in_preparazione` | `pronta` | `inviata` | `scartata` | `errore`.

- Il server prepara da solo la bozza per ogni mail `da_rispondere` appena smistata (in sottofondo).
- `POST /api/mail/{id}/bozza` `{"istruzioni": "digli che giovedì alle 10 va bene"}` → 202 Bozza(in_preparazione):
  rifà la bozza seguendo le istruzioni (sostituisce la precedente non inviata).
- `GET /api/bozze?stato=pronta`, `GET /api/bozze/{id}`
- `PUT /api/bozze/{id}` `{"a", "cc", "oggetto", "testo"}` → `modificata=true`. 409 se già inviata.
- `POST /api/bozze/{id}/invia` `{"conferma": true}` → invia dalla casella della mail originale
  (risposta nello stesso filo), segna la mail `risposta_data=true`. Senza `conferma: true` → 422.
  Errore di invio → 502 e `stato=errore` con `errore` leggibile; la bozza resta modificabile e reinviabile.
- `POST /api/bozze/{id}/scarta` → `stato=scartata`.

## 8. Riassunti

`GET /api/riassunti/oggi` → riassunto delle mail di oggi, ricalcolato in sottofondo ogni ora e dopo ogni
controllo con mail nuove importanti:
```json
{"testo": "Oggi 14 mail, 3 importanti: ...", "aggiornato_il": "...", "mail_citate": [812, 815], "stato": "pronto"}
```
`POST /api/riassunti` `{"richiesta": "le ultime mail sul CRM"}` → 202 `{"id": 9, "stato": "in_coda"}`.
Il server sceglie le mail pertinenti (ricerca testuale + gruppi + mittenti, max 40, partendo dai riassunti
di una riga) e chiede allo scrittore il testo.
`GET /api/riassunti/{id}` → `{"id", "richiesta", "stato", "testo_parziale", "testo", "mail_citate": [id], "errore", "creato_il"}`
`stato`: `in_coda` | `in_corso` | `pronto` | `errore`. Le app ricaricano ogni 2 s mentre è in corso.
Nel testo le mail citate compaiono come `[#812]`; le app le rendono cliccabili.
`GET /api/riassunti` → ultimi 20.

## 9. Scadenze

`Scadenza`: `{"id": 4, "mail_id": 812, "descrizione": "Offerta capannone Bassano a Rossi", "quando": "2026-10-16", "ora": null, "stato": "aperta", "creata_il": "..."}`
Estratte dallo scrittore dalle mail smistate (solo dalle mail non newsletter). `stato`: `aperta` | `fatta` | `scartata`.
- `GET /api/scadenze?stato=aperta`, `PATCH /api/scadenze/{id}` `{"stato"?, "quando"?, "descrizione"?}`, `POST /api/scadenze` (a mano).

## 10. Niente resta indietro

`GET /api/indietro`
```json
{
  "aspettano_te": [{"mail": Mail, "giorni": 3}],
  "aspetti_tu": [{"inviata": {"id": 77, "casella_id": 1, "a": ["neri@esempio.it"], "oggetto": "Offerta compressore", "inviata_il": "...", "anteprima": "..."}, "giorni": 8, "bozza_sollecito": null}]
}
```
- `aspettano_te`: mail `da_rispondere`, `risposta_data=false`, più vecchie di `giorni_risposta` (impostazione, base 2).
- `aspetti_tu`: mail INVIATE dall'utente (lette dalla cartella Posta inviata, dal giorno di attivazione) che chiedono
  qualcosa (deciso dal decisore: "questa mail aspetta una risposta?") e a cui non è arrivata risposta nello stesso
  filo da più di `giorni_sollecito` (base 7).
- `POST /api/indietro/{inviata_id}/sollecito` → 202 Bozza(tipo `sollecito`, in_preparazione).
- `POST /api/indietro/{inviata_id}/ignora` → toglie dalla lista.

## 11. Impostazioni

`GET /api/impostazioni` / `PATCH /api/impostazioni`
```json
{"modalita_prova": true, "soglia_sicurezza": 0.85, "giorni_risposta": 2, "giorni_sollecito": 7,
 "intervallo_controllo_minuti": 5, "firma": "Cordiali saluti\nManuel", "tono": "cortese e diretto",
 "scrittori": ["groq", "cerebras", "locale"], "conserva_giorni": 180}
```
Le chiavi dei fornitori di scrittura stanno SOLO nell'ambiente del server (`SOL_GROQ_KEY`, `SOL_CEREBRAS_KEY`),
mai nelle API.

## 12. Novità (per le notifiche)

`GET /api/novita?dal=<cursore>` → `{"cursore": "8131", "urgenti": [Mail], "da_rispondere_nuove": 2, "bozze_pronte": 1, "scadenze_domani": [Scadenza]}`.
Le app chiedono ogni 15 minuti (telefono) / 2 minuti (PC) e mostrano una notifica solo se c'è qualcosa di nuovo
di urgente. Primo giro senza `dal`: restituisce solo il cursore.

## 13. Servizio decisore (interno, solo rete Docker)

Container `sol-decisore`, `http://sol-decisore:8300`, non esposto fuori dal NAS.

`POST /decidi`
```json
{"stato": "Mittente: ...\nOggetto: ...\nTesto: ...",
 "domande": [{"id": "gruppo", "domanda": "A quale gruppo appartiene questa mail?",
              "opzioni": [{"id": "Clienti", "descrizione": "un cliente chiede o conferma un lavoro"}, ...]}]}
```
→ `{"risposte": {"gruppo": {"scelta": "Clienti", "probabilita": {"Clienti": 0.93, ...}}}, "millisecondi": 41000}`

`POST /genera` `{"messaggi": [{"ruolo": "sistema"|"utente", "testo": "..."}], "max_token": 600}` →
`{"testo": "..."}` (scrittore di riserva locale, lento).
`GET /salute` → `{"stato": "pronto"|"in_avvio", "modello": "..."}`.

## 14. Scrittori esterni

Interfaccia unica compatibile OpenAI `/chat/completions`:
- `groq`: `https://api.groq.com/openai/v1`, chiave `SOL_GROQ_KEY`, modello `SOL_GROQ_MODELLO`.
- `cerebras`: `https://api.cerebras.ai/v1`, chiave `SOL_CEREBRAS_KEY`, modello `SOL_CEREBRAS_MODELLO`.
- `locale`: `POST /genera` del decisore.
Ordine dall'impostazione `scrittori`; su errore/limite (429, 5xx, timeout) si passa al successivo. Senza chiavi
resta solo `locale`. Solo modelli open-source.
