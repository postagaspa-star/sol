# Sol — contratto delle API (v1.1)

Fonte di verità tra server, app Android e programma PC. Si cambia QUI prima che nel codice.
Le novità della v1.1 (dopo la prima revisione) sono raccolte nella sezione 15 e valgono sopra
il testo delle sezioni precedenti dove lo contraddicono.
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
  `conflitto` (409), `troppi_tentativi` (429), `casella_non_raggiungibile` (502),
  `scrittore_non_disponibile` (503), `errore_interno` (500). Anche gli errori inattesi (500) escono
  in JSON con questa forma, mai in testo semplice.
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
401 se il codice è sbagliato o scaduto. Massimo 10 tentativi sbagliati ogni 15 minuti per tutto il server
(dietro Tailscale tutti arrivano dallo stesso indirizzo): superato il limite → 429 `troppi_tentativi` e
tutti i codici aperti vengono annullati.

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

## 15. Novità della v1.1 (dopo la prima revisione)

### 15.1 Gruppi: da 2 a 16
Il decisore accetta da 2 a 16 opzioni. `POST /api/gruppi` oltre i 16 → 422
`{"errore": "Puoi avere al massimo 16 gruppi: togline uno prima di aggiungerne un altro.", "codice": "non_valido"}`.
`DELETE` dell'ultimo gruppo oltre ad Altro → 422.

### 15.2 Spegnere la modalità prova non tocca l'arretrato da solo
Spenta la modalità prova, Sol sposta nelle caselle vere SOLO le mail smistate da quel momento in poi.
Le mail già smistate prima ("arretrato") si spostano solo con un gesto esplicito:
- `GET /api/arretrato` → `{"da_applicare": 37}` (mail già smistate/corrette, ancora in Posta in arrivo,
  mai applicate nella casella).
- `POST /api/arretrato/applica` `{"conferma": true}` → 202 `{"da_applicare": 37}`; senza conferma → 422.
Le app, quando `modalita_prova` passa a false e `da_applicare > 0`, chiedono:
"Ci sono 37 mail già smistate rimaste in Posta in arrivo. Vuoi spostarle anche nella casella?".
In ogni caso Sol sposta una mail solo se è ANCORA in Posta in arrivo: se l'utente l'ha già spostata,
archiviata o eliminata con un altro programma, Sol non la tocca (e la segna come applicata).

### 15.3 Ricollegare una casella senza perdere niente
`POST /api/caselle/{id}/ricollega` — Gmail: `{"password_app": "..."}` → 200 Casella (riprova subito);
Outlook: `{}` → 202 con `codice_utente`/`url_verifica`/`scade_il` come nel collegamento.
Tiene mail, bozze, scadenze, regole e il punto da cui riprendere la lettura: le mail arrivate mentre
la casella era in errore entrano lo stesso. Le app lo propongono su ogni casella in `stato = "errore"`
("Ricollega").

### 15.4 Conteggi per i filtri
`GET /api/conteggi` →
```json
{"da_rispondere": 5, "urgenti": 2, "da_controllare": 3, "da_leggere": 12, "bozze_pronte": 4,
 "gruppi": {"1": {"da_leggere": 4, "totale": 120}}}
```
Conta solo le mail non nascoste dalla pulizia; `urgenti` = urgenza alta e non lette o da rispondere.

### 15.5 Rifare qualsiasi bozza
`POST /api/bozze/{id}/rifai` `{"istruzioni": "più breve" | null}` → 202 Bozza(in_preparazione), vale sia
per le risposte sia per i solleciti. 409 se già inviata. (`POST /api/mail/{id}/bozza` resta valido.)

### 15.6 Novità con le bozze
`GET /api/novita` aggiunge `"bozze_nuove": [{"id": 55, "mail_id": 812, "oggetto": "Re: Offerta capannone Bassano"}]`
(le bozze diventate pronte dopo il cursore). `bozze_pronte` resta il numero totale.

### 15.7 Invio: niente doppioni
Prima di ogni invio (anche il primo) il server assegna alla bozza un Message-ID fisso. Se un invio
fallisce in modo ambiguo (la casella potrebbe averlo accettato), al tentativo successivo il server
cerca prima quel Message-ID in Posta inviata: se c'è, segna la bozza `inviata` senza rimandarla
(risposta 200 con `"gia_inviata": true`); se non riesce a controllare → 502
`{"errore": "Non riesco a capire se la mail è già partita. Guarda in Posta inviata prima di riprovare.", "codice": "casella_non_raggiungibile"}`.
`PUT`, `rifai` e `scarta` su una bozza in fase di invio → 409.

### 15.8 PEC
`Mail` aggiunge `"pec": null` oppure `{"mittente_busta": "posta-certificata@pec.esempio.it", "gestore": "pec.esempio.it"}`.
Sol apre la busta (e mostra mittente/oggetto della mail interna) solo per le PEC vere (con `daticert.xml`).
Le app mostrano entrambi i mittenti. Le regole imparate non usano il mittente interno.

### 15.9 Mittente e oggetto puliti
Oggetto e nomi arrivano con gli spazi normalizzati (niente a-capo). I testi che vengono dalle mail non
contengono mai sequenze `[#numero]`: nei riassunti un `[#id]` è sempre un riferimento messo da Sol.

### 15.10 Versioni con impronta
`GET /api/versione` aggiunge per ogni file scaricabile `"sha256"` e `"byte"`:
`"android": {"codice": 1, "nome": "1.0.0", "sha256": "…", "byte": 12500244}` (idem `pc`).
Le app controllano l'impronta dopo il download e scartano il file se non coincide.

### 15.11 Si invia SOLO la versione che l'utente ha visto
`Bozza` aggiunge `"versione": 3` (intero, +1 a ogni cambiamento: PUT, rifai, bozza rifatta dal server).
`POST /api/bozze/{id}/invia` vuole `{"conferma": true, "versione": 3}` (obbligatorio). Se la bozza nel
frattempo è cambiata (salvataggio arrivato tardi, modifica da un altro dispositivo) → 409
`{"errore": "La risposta è cambiata dopo che l'hai controllata: ricontrollala prima di inviarla.", "codice": "conflitto", "bozza": Bozza}`.
Le app mostrano nella conferma destinatari, Cc, oggetto e la prima riga del testo DELLA VERSIONE che
mandano, e durante la conferma i campi della bozza sono bloccati e nessun salvataggio automatico parte.
Testo vuoto → le app non chiedono nemmeno la conferma (il server risponde comunque 422).

### 15.12 Salute: dice se il dispositivo è riconosciuto
`GET /api/salute` aggiunge `"autenticato": true|false`. Senza token o con token revocato risponde
`{"ok": true, "versione": "1.0.0", "autenticato": false}`. Le app NON devono considerare un 200 di
`/api/salute` come prova che l'accesso funziona: guardano `autenticato`.

### 15.13 Precisazioni
- `rifai` (e `POST /api/mail/{id}/bozza` con istruzioni) parte dalla bozza ATTUALE, comprese le modifiche
  fatte a mano dall'utente (testo, destinatari, oggetto), e applica le istruzioni: non riparte da zero.
  Se la bozza è stata modificata a mano e la richiesta arriva senza istruzioni, il server la rifà da zero
  solo se le app l'hanno chiesto esplicitamente con `{"istruzioni": null, "da_zero": true}`; altrimenti 409.
- Il filtro `GET /api/mail?da_rispondere=true` restituisce solo mail con `da_rispondere=true` E
  `risposta_data=false` (come il conteggio `da_rispondere`).
- Errori 422 di validazione: il campo `errore` è sempre una frase in italiano semplice (mai il nome
  tecnico del campo); il nome del campo, se serve alle app, va in `"campo": "giorni_risposta"`.
- Risposta data da un altro programma (Outlook, sito di Gmail): quando Sol se ne accorge, la bozza
  automatica di quella mail MAI modificata dall'utente diventa `scartata`; se l'utente l'aveva modificata
  resta com'è, e le app nella conferma d'invio avvisano "Hai già risposto a questa mail da un altro
  programma: vuoi mandare anche questa?".
- `bozze_pronte` in `/api/novita` è il totale delle bozze pronte; per notificare si usa SOLO `bozze_nuove`.
- Indirizzi `http://` (senza s): accettati dalle app solo per indirizzi di rete privata di casa
  (192.168.x.x, 10.x.x.x, 172.16-31.x.x) e mai per mandare password (aggiunta/ricollegamento Gmail): lì le
  app chiedono l'indirizzo https di Tailscale. La porta di casa 8200 resta aperta solo durante
  l'installazione; a Tailscale funzionante si chiude.

### 15.14 Decisore: com'è davvero (misurato sul NAS)
- Tempi: ~18 s per mail (3 domande insieme), ~4,8 token/s per `/genera`, ~60 s di avvio a modello scaricato.
  Il server usa timeout di almeno 3 minuti per `/decidi` e 5 per `/genera`, e prima di mandare una mail
  toglie le parti citate delle risposte precedenti (righe con `>`, "Il giorno … ha scritto:", "Da: … Inviato:").
- Domande: il server manda le 3 domande con i testi di `decisore/app/domande_consigliate.json` (il decisore li
  prepara all'avvio: con testi diversi la prima mail costa ~40 s in più). Le opzioni del gruppo sono i gruppi
  dell'utente con la loro `descrizione`.
- `Gruppo` aggiunge `"descrizione"`: frase breve che dice al decisore che mail ci vanno. I 6 gruppi iniziali
  hanno le descrizioni del file sopra; un gruppo nuovo la chiede all'utente (`POST /api/gruppi` `{"nome", "descrizione"}`,
  se manca si usa il nome). Si modifica con `PATCH /api/gruppi/{id}`.
- Errori del decisore: 503 `decisore_non_disponibile` (modello in avvio o non caricato, oppure già occupato
  con una `/genera`), 500 `errore_interno`, 422 `domande_non_valide` (domande/opzioni fuori regola: problema
  di configurazione, riguarda tutte le mail) oppure 422 `mail_non_valida` (problema di QUESTA mail: il server
  la mette subito `da_controllare`, senza altri tentativi e senza contarla come guasto del decisore).
- Neutralizzazione: sia il server (prima di ogni prompt, anche verso gli scrittori esterni) sia il decisore
  normalizzano il testo in NFC e rendono innocue le sequenze di controllo dei modelli dentro i testi che
  vengono dalle mail: tutto ciò che ha la forma `<|…|>`, e `<think>`, `</think>`, `<tool_call>`,
  `</tool_call>`, `[INST]`, `[/INST]` (si spezza la sequenza, es. `<|` → `<​|`). Il testo resta
  leggibile, ma il modello non lo può scambiare per un cambio di turno.
- `/genera` accetta `"scadenza_secondi"`: oltre quel tempo, o se il client chiude la connessione, il decisore
  smette di scrivere e libera il modello. Al massimo una `/genera` alla volta: un'altra mentre la prima è in
  corso riceve 503 `decisore_non_disponibile`. `/salute` può rispondere `pronto` | `in_avvio` (con `"percentuale"`: intero 0-100, campo vero, non dentro una
  frase) | `non_disponibile`. Le risposte di `/decidi` possono avere campi in più
  (`massa_opzioni`, `token`, `token_al_secondo`): il server li ignora.
- Sicurezza delle scelte: sotto 0,85 sul gruppo → `da_controllare`; per `da_rispondere` e `urgenza` sotto 0,6
  il server tiene il valore più prudente (`da_rispondere = si`, `urgenza = normale`) e lo segna come incerto
  nel campo `Mail.incerti` (elenco, es. `["urgenza"]`; vuoto se Sol è sicuro di tutto).
