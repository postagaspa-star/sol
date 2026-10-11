# Sol 2.0 — aspetto e uso

Guida comune all'app Android e al programma PC. Sostituisce la guida 1.3, bocciata da Andrea l'11/10/2026
("hai solo spostato cose e messo del rosso"). La direzione è il **prototipo approvato** in
`docs/prototipo/` (Main = Oggi, Posta, Mail = mail aperta, Smista): aprire quei file e riprodurne misure,
colori, pesi e spaziature. Quello che il prototipo non mostra si fa nello stesso stile, seguendo questa guida.

## 0. Principi (dalla ricerca, non gusti)

1. **Parti dal lavoro, non dai dati.** La prima schermata risponde a "cosa devo fare adesso?" (pagina Oggi),
   non mostra l'elenco grezzo delle mail.
2. **Una cosa sola risalta per schermata** (prova dello sguardo socchiuso): un titolo grande, un'azione
   principale piena; il resto più piccolo, più chiaro, più quieto.
3. **Colore 60-30-10.** 60% fondo neutro, 30% superfici bianche, 10% accento arancio SOLO sulle azioni
   principali e su pochi numeri. Colore dei gruppi solo nei cerchi con le iniziali e nei pallini delle sezioni.
   Rosso solo per le possibili truffe.
4. **Meno bordi, più spazio.** Separare con spazio, schede bianche e ombre leggere; righe sottili solo dentro
   gli elenchi. Griglia di 4/8 px.
5. **Raggruppa e riduci.** Mail raccolte sotto il loro gruppo; newsletter e fatture chiuse in una riga
   ("raccolte"); al massimo un segno per riga.
6. **L'AI propone, l'utente decide in un tocco.** La risposta di Sol è un suggerimento con pulsanti di tono;
   l'invio resta un gesto esplicito con conferma.
7. **Una alla volta quando si decide.** Lo smistamento delle mail incerte è a schede, una per volta.
8. **Niente istruzioni scritte in piccolo** (sezione 9): si impara col tutorial interattivo, non con frasi
   d'aiuto permanenti.
9. **Il pollice comanda.** Azioni principali in basso, tocchi ≥ 48 dp, pulsanti principali alti 56-60 dp.

## 1. Fondamenta

### Carattere
**Figtree** (Google Fonts, licenza OFL; su Android incluso nell'app come risorsa font, sul PC incluso nel
pacchetto: niente richieste a Google durante l'uso). Pesi 400, 500, 600, 700, 800.

| Ruolo | Dimensione / peso | Esempio |
|---|---|---|
| Titolo pagina | 30 / 800, spaziatura -0,6 | "Buongiorno, Manuel", "Posta" |
| Oggetto mail aperta | 25 / 800 | "Offerta capannone Bassano" |
| Titolo sezione | 21 / 800 | "Da fare" |
| Testo forte | 16-17 / 700-800 | mittente, titolo di una voce |
| Testo | 15-16 / 400-600, interlinea 1,45-1,55 | corpo, riassunti |
| Secondario | 13-14 / 500-600, colore tenue | ora, indirizzo, dettagli |
| Etichetta | 12 / 700, maiuscolo, spaziatura 0,08em | "IL PUNTO DI OGGI", "SOL PROPONE" |

### Colori (chiaro / scuro)
| Ruolo | Chiaro | Scuro |
|---|---|---|
| Fondo | `#F4F4F2` | `#121214` |
| Superficie (schede, elenchi) | `#FFFFFF` | `#1C1C1F` |
| Superficie secondaria (pulsanti tondi, binario segmenti) | `#E7E6E2` | `#2A2A2E` |
| Testo | `#1B1B1F` | `#F2F2F3` |
| Testo tenue | `#6B6B73` | `#A1A1AA` |
| Testo molto tenue | `#8A8A92` | `#85858E` |
| Riga sottile | `#EFEEEB` | `#2C2C30` |
| Accento (pulsante pieno, barra di avanzamento) | `#D9480F` (testo bianco) | `#E8590C` (testo bianco grande e grassetto) |
| Accento tenue (pulsanti secondari, tab attiva) | `#FFF1E8` / `#FFE8D9`, testo `#B23A0B` | `#3A2418`, testo `#FF9A66` |
| Truffa | pieno `#C92A2A`, tenue `#FDEEEC`, testo `#9B1C1C` | pieno `#E03131`, tenue `#3A1C1C`, testo `#FFA8A8` |

Colori dei gruppi (dal nome `Gruppo.colore` del server): cerchio con iniziali = sfondo tenue + iniziali
scure del colore; pallino della sezione = colore pieno. Esempi dal prototipo: blu `#2F6FDB` / tenue
`#EAF1FD` / iniziali `#1E4FA8`; verde `#2B8A3E` / `#EBF7EE` / `#1F6A30`; ambra `#B7791F` / `#FBF3E3` /
`#8A5A12`; viola `#7048E8` / `#F1ECFE` / `#5532C9`; grigio `#868E96` / `#F1F3F5` / `#495057`; ardesia
`#495057` / `#EEF0F2` / `#343A40`. Gli altri 6 nomi (petrolio, rosa, indaco, oliva, marrone, ciano) con la
stessa logica (pieno saturo, tenue al 10% circa, iniziali scure). Nel tema scuro il tenue diventa il colore al
20% su superficie scura e le iniziali una tinta chiara.

### Forme, ombre, movimento
- Raggi: schede 20-24, schede grandi (Smista) 28, pulsanti pieni 16-18, pillole 999, cerchi.
- Ombre: scheda `0 1px 2px rgba(20,20,20,.04)`; scheda in rilievo `0 1px 2px rgba(20,20,20,.04), 0 10px 28px rgba(20,20,20,.06)`;
  pulsante principale `0 8px 20px` del suo colore al 28%. Nel tema scuro niente ombre: superfici più chiare.
- Movimento 200 ms, curva morbida; tolto se il sistema chiede meno animazioni. Niente rimbalzi né coriandoli.
- Icone a tratto (2 px), mai emoji. Il simbolo di Sol è il sole del prototipo.

## 2. Navigazione
Barra in basso con **3 voci**: **Oggi**, **Posta** (pallino col numero da leggere importanti), **Riassunti**.
Voce attiva: pillola accento tenue dietro l'icona, testo accento. Le **Impostazioni** si aprono dal cerchio con
l'iniziale in alto a destra nella pagina Oggi. Scadenze e "Aspetti risposta" si aprono da Oggi.

## 3. Oggi (pagina iniziale)
Come `docs/prototipo/Main.dc.html`, con i dati di `GET /api/oggi`:
- Riga in alto: sole + "Sol"; a destra cerchio con l'iniziale del nome (→ Impostazioni).
- "Buongiorno/Buon pomeriggio/Buonasera, {nome}" (senza nome: solo il saluto) e sotto data e "N mail nuove".
- Scheda "IL PUNTO DI OGGI": il riassunto di oggi (3 frasi), con i riferimenti `[#id]` come parole
  toccabili (nome del mittente sottolineato leggero), e la pillola della scadenza più vicina. Riassunto in
  preparazione: tre righe grigie che pulsano piano (niente rotelle in loop… una pulsazione lenta va bene).
- "Da fare" con "N di M fatte" e barra di avanzamento. Una scheda per voce (`da_fare`), con il pulsante giusto:
  risposta → "Rivedi" (pieno accento) apre la mail; truffa → scheda rossa tenue, "Guarda"; smista → "Inizia";
  rispondi → "Rispondi"; sollecito → "Rivedi"/"Prepara"; scadenza → "Fatto" (segna fatta).
- Tutto fatto: al posto delle schede un cerchio con la spunta e "Niente da fare. Bella giornata." (una riga),
  nessuna festa.
- Sotto: due righe-collegamento "Scadenze" e "Aspetti risposta" con numero e freccia (aprono le pagine intere).

## 4. Posta
Come `docs/prototipo/Posta.dc.html`:
- Titolo "Posta", pulsante tondo di ricerca (apre una pagina di ricerca con campo in alto e risultati nello
  stesso stile delle righe).
- Segmenti **Importanti | Tutte**. Importanti = gruppi non raccolta, divisi in sezioni (pallino colore + nome +
  numero), poi la sezione "Raccolte" con una riga per gruppo raccolta (icona, nome, "N mail · ultima: …",
  freccia → pagina della raccolta con le sue mail). Tutte = tutte le mail per data, con piccolo pallino del
  gruppo accanto all'ora.
- In cima, solo se ci sono mail incerte: scheda accento tenue "N mail da sistemare" con pulsante "Smista".
- Riga mail: cerchio con iniziali, mittente (800 se non letta, 600 se letta), ora (accento se non letta),
  oggetto, riassunto di una riga tenue; a destra in fondo al massimo UN segno: "Possibile truffa" (pillola
  rossa piena) oppure "Risposta pronta" (pillola accento tenue con matita) oppure niente. Urgente: l'ora e il
  pallino in rosso invece che accento.
- Gesti: a destra letta/da leggere; a sinistra "Sposta in" (foglio dal basso coi gruppi: cerchio colorato,
  nome, descrizione) e poi "Spostata in X · Annulla" per 5 secondi.
- Modalità prova: una pillola ambra discreta accanto al titolo ("Prova"), toccandola si spiega cosa vuol dire
  in un foglio. Niente striscia fissa.

## 5. Mail aperta
Come `docs/prototipo/Mail.dc.html`:
- Pulsanti tondi indietro e "altre azioni" (menu: Segna da leggere, Non serve rispondere / Va risposta,
  Segnala come possibile truffa, Scarta la risposta, Rispondi a tutti se c'erano altri destinatari).
- Mittente con cerchio, "indirizzo · ora", pillola del gruppo a destra (tocco → Sposta in).
- Oggetto grande; "In breve:" in grassetto seguito dal riassunto; corpo; allegati come pillole bianche.
- Possibile truffa: scheda rossa tenue SOPRA "In breve" col testo d'avviso del contratto e il pulsante
  "Non è una truffa"; niente risposta automatica.
- Mail incerta: sotto l'intestazione una riga "È nel gruppo giusto?" con [Sì] [Cambia].
- **Risposta di Sol**: scheda bianca in rilievo col sole, "a {destinatario}" (tocco → si aprono A, Cc,
  Oggetto), testo modificabile direttamente, e le pillole **Più breve**, **Più formale**, **Chiedi più tempo**,
  più una pillola "Altro…" che apre un campo per scrivere (o dettare) come rifarla. Ogni pillola chiama
  `rifai` con l'istruzione corrispondente; mentre Sol riscrive, il testo resta visibile sbiadito con una
  barra sottile che scorre una volta.
- Barra in basso: **Invia risposta** (pieno, 56 dp, tutta larghezza). Senza risposta e da rispondere:
  **Prepara la risposta**. Mail che non chiede risposta: niente barra, "Rispondi" nel menu.
- Conferma d'invio: foglio dal basso (non una finestrella) con A, Cc, Oggetto, prima riga, eventuali avvisi
  (truffa, già risposto) e i pulsanti "Invia" / "Annulla". Tutte le garanzie d'invio restano identiche.
- Dopo l'invio: la scheda si chiude con una spunta e la mail torna all'elenco.

## 6. Smista (le mail incerte, una alla volta)
Come `docs/prototipo/Smista.dc.html`: chiudi, "Smista", "1 di N", barra; pila di schede con la mail
(mittente, oggetto, riassunto) e in basso "SOL PROPONE" con la pillola del gruppo e "abbastanza sicuro" /
"poco sicuro" (da `sicurezza`: ≥ 0,7 abbastanza, sotto poco). Pulsanti grandi **Cambia** e **Va bene**.
Gesti: scorri a destra = Va bene, a sinistra = Cambia (si apre la griglia dei gruppi). La scheda esce con un
movimento. Fine: spunta, "Tutto sistemato", "Torna a Oggi". Possibili truffe NON passano da qui (hanno la
loro scheda).

## 7. Le altre pagine (nello stesso stile)
- **Riassunti**: titolo, scheda "Oggi" come il punto di Oggi, campo grande "Cosa vuoi sapere?" con il
  microfono della tastiera, risposta che compare in una scheda con i riferimenti toccabili, elenco dei
  riassunti chiesti di recente. (La struttura resta questa: Andrea non ha voluto le richieste pronte.)
- **Raccolta** (Newsletter, Fatture…): titolo col nome, righe mail come nella Posta, in alto "Segna tutte
  lette".
- **Scadenze**: elenco per giorno ("Oggi", "Domani", "Venerdì 16"), ognuna con "Fatto"; pulsante tondo "+"
  per aggiungerne una.
- **Aspetti risposta** (niente resta indietro): due sezioni, "Aspettano te" e "Aspetti tu", righe con giorni
  passati e il pulsante giusto (Rivedi / Prepara sollecito / Non serve).
- **Ricerca**: campo in alto con il cursore già dentro, risultati come righe di mail.
- **Impostazioni**: elenco a gruppi in schede bianche, ogni voce con icona, nome, valore attuale e freccia:
  - *Tu*: Nome, Firma, Tono delle risposte.
  - *Caselle di posta*: una riga per casella con stato (pallino verde/rosso); "Aggiungi Gmail", "Aggiungi
    Outlook", "Ricollega" sulle caselle in errore.
  - *Smistamento*: Gruppi (colore, descrizione, raccolta sì/no, ordine), Mittenti ricordati, Quanto deve
    essere sicuro Sol, Modalità prova (con la domanda sull'arretrato quando si spegne).
  - *Sicurezza*: Controllo truffe (soglia), Dispositivi collegati, Scollega questo telefono.
  - *Promemoria*: Giorni per "aspettano te" e per il sollecito.
  - *App*: Tema (automatico/chiaro/scuro), **Come funziona Sol** (riapre il tutorial), Versione e
    aggiornamento.
- **Abbinamento** (primo avvio): sole grande, "Collega Sol al server di casa", campo indirizzo (già scritto
  `https://sol.taildbae63.ts.net` se si conosce, altrimenti esempio come segnaposto), codice di 8 cifre in 8
  caselle grandi, nome del telefono, pulsante pieno "Collega".
- **Stati**: senza rete = pillola grigia in alto "Senza collegamento · dati delle 9:40" e tutto consultabile;
  errori = frase chiara nella scheda interessata con "Riprova"; elenchi vuoti = una riga calma.

## 8. Tema scuro
Stessa struttura, colori della tabella. Le schede si distinguono per superficie più chiara, non per ombre.

## 9. Testi: niente istruzioni permanenti
- VIETATE le frasi d'aiuto fisse in piccolo, per esempio "Oppure scorri…", "Puoi anche dettare col microfono
  della tastiera", "Gli allegati si aprono dalla casella di posta", "Sol per ora suggerisce…" ripetuto in ogni
  pagina, spiegazioni sotto gli interruttori. Le cose si imparano col tutorial (sezione 10).
- Restano: etichette brevi, numeri, errori chiari con cosa fare, conferme, una riga negli elenchi vuoti, i
  testi d'avviso sulla truffa (sono sicurezza, non istruzioni) e le spiegazioni dentro i fogli che l'utente
  apre apposta (per esempio "Cos'è la modalità prova").
- Tono: italiano semplice, frasi corte, niente punti esclamativi, niente gergo.

## 10. Tutorial: visita guidata DENTRO l'app vera (2.1)
La versione 2.0 è stata bocciata da Andrea: mostrava pezzi d'app staccati, "zone isolate", e non era chiara.
Il tutorial 2.1 è una **visita guidata sulle schermate vere dell'app** (le stesse pagine, la stessa barra in
basso, le stesse transizioni), riempite con **posta di esempio**.

### Come funziona
- **Modalità esempio**: per tutta la visita l'app usa una sorgente dati finta in memoria (stessa interfaccia del
  client vero: stesse schermate, stessi ViewModel/stesso codice di pagina) con una casella di esempio: mail di
  Mario Rossi (risposta pronta), Ferramenta Neri (possibile truffa), 2 mail da smistare, una newsletter, una
  scadenza venerdì, un sollecito. Niente chiamate al server, niente invii, niente cache toccata. In cima una
  pillola discreta "Esempio" ricorda che i dati non sono veri. Uscendo dalla visita l'app torna ai dati veri
  esattamente dov'era.
- **Riflettore**: un velo scuro (nero al 55%, al buio 65%) copre lo schermo tranne un "buco" arrotondato attorno
  all'elemento di cui si parla, con un anello accento che pulsa piano. Il buco si **sposta e si ridimensiona
  con un movimento fluido** da un elemento al successivo (e segue la pagina quando cambia).
- **Fumetto**: vicino al buco (sopra o sotto, mai a coprirlo), scheda bianca raggio 20: in alto "2 di 9" piccolo,
  un TITOLO corto (18/800) e UNA frase (16/500) che dice cosa fare e perché; in basso "Indietro" (testo) e,
  solo per i passi da leggere, "Avanti" (pieno accento). Nei passi da fare non c'è "Avanti": si va avanti
  FACENDO l'azione vera sull'elemento illuminato. "Salta la visita" sempre in alto a destra.
- **Mano che mostra il gesto**: per i gesti (scorrere, toccare), un cerchio-dito semitrasparente anima il
  movimento sopra l'elemento (tocco: si abbassa e rilascia; scorrere: parte a sinistra e scivola a destra con
  una scia), ripetuto con pause finché l'utente non lo fa.
- **Fatto bene**: quando l'utente esegue l'azione, piccolo segno di spunta nell'anello, poi il riflettore
  scivola al passo successivo. Se tocca fuori dal buco non succede niente (il fumetto fa un piccolo scatto).
- Movimenti: 250-350 ms, curve morbide (riflettore e fumetto insieme, cambio pagina con la transizione normale
  dell'app). Tutti tolti se il sistema chiede meno animazioni (allora il riflettore salta e la mano diventa una
  freccia ferma).

### I passi (telefono)
1. **Oggi → "Il punto di oggi"** (leggere): "Il punto della giornata" / "Ogni mattina Sol ti riassume qui le
   mail che contano."
2. **Oggi → pulsante "Rivedi" di Mario Rossi** (fare: tocco): "Le cose da fare" / "Qui c'è quello che ti
   aspetta. Tocca Rivedi per aprire la mail di Rossi." → si apre la mail vera.
3. **Mail → scheda "Risposta di Sol"** (leggere): "La risposta è già pronta" / "Sol l'ha scritta per te. Se
   vuoi cambiare qualcosa, scrivi direttamente nel testo."
4. **Mail → pillola "Più breve"** (fare: tocco): "Cambia il tono" / "Tocca Più breve: Sol la riscrive in un
   attimo." → il testo cambia con la sua animazione.
5. **Mail → "Invia risposta"** (fare: tocco) e poi **"Invia" nel foglio di conferma**: "Mandala" / "Quando va
   bene, tocca Invia. È un esempio: non parte niente." → spunta, ritorno alla Posta.
6. **Posta → riga di Laura Bianchi, gesto a destra** (fare: scorri): "Segna come letta" / "Scorri la mail
   verso destra."
7. **Stessa riga, gesto a sinistra** (fare: scorri), poi scelta del gruppo nel foglio, poi **"Annulla"**
   illuminato per 3 secondi (leggere): "Spostala in un gruppo" / "Scorri verso sinistra e scegli dove va. Se
   sbagli, c'è Annulla."
8. **Posta → scheda "2 mail da sistemare" → "Smista"** (fare: tocco) e poi **"Va bene"** sulla prima scheda
   (fare): "Quando Sol non è sicuro" / "Ti fa vedere la mail e il gruppo che propone. Se è giusto, Va bene."
9. **Posta → riga di Ferramenta Neri con "Possibile truffa"** (leggere, pulsante "Ho capito"): "Attenzione
   alle truffe" / "Se vedi questa etichetta, prima di pagare telefona al mittente a un numero che conosci già."
10. Fine: il velo si dissolve, fumetto al centro "Ecco fatto" / "Adesso Sol lavora con la tua posta vera."
   [Inizia] → app sui dati veri, pagina Oggi.

### Sul PC
Stessa visita sulle schermate vere del programma, con il riflettore sugli elementi della finestra (colonna,
elenco, mail aperta). Al posto dei gesti si provano i tasti: passo 6 "Premi L per segnarla letta", passo 7
"Premi 2 per spostarla in Fornitori" (poi Annulla), passo 8 "Premi Invio per dire Va bene"; un tasto disegnato
nel fumetto si "preme" con un'animazione finché l'utente non lo preme davvero.

- Parte da sola la prima volta dopo l'abbinamento (e dopo l'aggiornamento alla 2.1, una volta); si riapre da
  Impostazioni → "Come funziona Sol".

## 11. Cosa NON cambia
Contratto delle API e tutte le garanzie: invio solo con conferma della versione riletta, campi bloccati
durante la conferma, salvataggio delle modifiche, avvisi truffa e già risposto, gestione 401, cache cifrata,
notifiche, aggiornamento con sha256, accessibilità (lettore schermo, tocchi ≥ 48 dp, contrasti 4,5:1 / 3:1).
