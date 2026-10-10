# Sol — aspetto e uso (v1.3)

Guida comune all'app Android e al programma PC. Decisa da Andrea il 10/10/2026 dopo aver visto
la 1.0 ("troppo basica, la UX fa schifo"): **gruppi colorati + accento caldo**, **mail aperta con
prima il testo e la risposta**, **Posta più pulita**, **gesti veloci**, **piccoli movimenti**,
**stessi colori sul PC**. Riassunti: solo i colori nuovi, la struttura resta.

Per chi è: un perito industriale adulto, che la usa anche in cantiere e al sole. Colorata ma da
professionista: niente emoji, niente toni da bambini, niente pagine di festa, niente coriandoli.

## 1. Colori

### Accento di Sol ("sole")
| Uso | Chiaro | Scuro |
|---|---|---|
| Fascia in alto (sfumatura da sinistra a destra) | `#EA580C` → `#C2410C`, titolo bianco grande | `#9A3412` → `#7C2D12`, titolo bianco |
| Pulsante principale (Invia, Chiedi, Abbina) | sfondo `#C2410C`, testo bianco | sfondo `#EA580C`, testo bianco |
| Scheda / filtro selezionato, icona attiva della barra in basso | `#C2410C` | `#FB923C` |
| Sfondo pagina | `#FFFBF7` (bianco caldo) | `#14110F` |
| Superfici (schede) | `#FFFFFF` | `#1F1A17` |

### Colori dei gruppi
Ogni gruppo ha un colore (campo `Gruppo.colore` del contratto, sezione 17). Il server manda il
NOME del colore, le app lo traducono in tinte adatte al tema:

| Nome | Base (cerchi con iniziali, testo bianco) | Etichetta chiara: sfondo / testo | Etichetta scura: sfondo / testo |
|---|---|---|---|
| `blu` | `#1D4ED8` | `#DBEAFE` / `#1E40AF` | `#1E3A8A` / `#BFDBFE` |
| `verde` | `#15803D` | `#DCFCE7` / `#166534` | `#14532D` / `#BBF7D0` |
| `ambra` | `#B45309` | `#FEF3C7` / `#92400E` | `#78350F` / `#FDE68A` |
| `viola` | `#7E22CE` | `#F3E8FF` / `#6B21A8` | `#581C87` / `#E9D5FF` |
| `grigio` | `#4B5563` | `#F3F4F6` / `#374151` | `#374151` / `#E5E7EB` |
| `ardesia` | `#334155` | `#E2E8F0` / `#1E293B` | `#1E293B` / `#CBD5E1` |
| `petrolio` | `#0F766E` | `#CCFBF1` / `#115E59` | `#134E4A` / `#99F6E4` |
| `rosa` | `#BE185D` | `#FCE7F3` / `#9D174D` | `#831843` / `#FBCFE8` |
| `indaco` | `#4338CA` | `#E0E7FF` / `#3730A3` | `#312E81` / `#C7D2FE` |
| `oliva` | `#4D7C0F` | `#ECFCCB` / `#3F6212` | `#365314` / `#D9F99D` |
| `marrone` | `#92400E` | `#FFEDD5` / `#7C2D12` | `#7C2D12` / `#FED7AA` |
| `ciano` | `#0E7490` | `#CFFAFE` / `#155E75` | `#164E63` / `#A5F3FC` |

Gruppi iniziali: Clienti `blu`, Fornitori `verde`, Amministrazione e fatture `ambra`, PEC ed enti
`viola`, Newsletter `grigio`, Altro `ardesia`. Nome sconosciuto → `ardesia`.

### Segnali (mai solo col colore: sempre icona + parola)
| Segnale | Come |
|---|---|
| Possibile truffa | etichetta PIENA rossa `#B91C1C` con triangolo e testo bianco; nel dettaglio il riquadro d'avviso tenue già fatto |
| Urgente | punto esclamativo rosso + parola "urgente" in rosso, SENZA riempimento (così non si confonde con la truffa) |
| Risposta pronta | matita + "risposta pronta" nel colore dell'accento |
| Da controllare | punto interrogativo + "da controllare", ambra |
| Non letta | nome del mittente e oggetto in grassetto + pallino dell'accento |

Contrasto: testo normale almeno 4,5:1, testo grande e icone almeno 3:1, in tutti e due i temi.

## 2. Posta
```
┌────────────────────────────────┐
│▓▓ Posta                     ⟳ ▓│  fascia calda
├────────────────────────────────┤
│ Tutte ●Clienti 4 ●Fornitori …  │  UNA riga di gruppi, scorrevole, colore del gruppo
│ ┌ Da fare ───────────────────┐ │  solo in "Tutte"; si nasconde se è tutto a zero
│ │ 5 da rispondere  1 truffa  │ │  piastrelle toccabili (= filtro)
│ │ 3 da controllare 2 scadenze│ │  scadenze = apre la scheda Scadenze
│ └────────────────────────────┘ │
│ (MR) Mario Rossi         08:12 │  cerchio col colore del gruppo + iniziali
│      Offerta capannone         │  grassetto se non letta
│      Chiede l'offerta entro... │  riassunto di una riga (o anteprima)
│      ! urgente  ✎ risposta     │  al massimo DUE segnali, in ordine di importanza
└────────────────────────────────┘
```
- Una sola riga di schede: i gruppi (con numero da leggere). I filtri non sono più schede: stanno
  nel riquadro "Da fare" (da rispondere, possibili truffe, da controllare, scadenze della settimana).
  Quando un filtro è attivo, sopra l'elenco compare "Da rispondere · togli filtro".
- In "Tutte", la riga mostra anche una piccola etichetta col nome del gruppo nel suo colore.
- Ordine dei segnali (se ce ne sono di più si mostrano i primi due): truffa, urgente, da
  controllare, risposta pronta.
- Modalità prova: una striscia sottile ambra in cima alla Posta ("Prova: Sol suggerisce e non
  sposta niente"), non ripetuta in ogni mail.
- Elenco vuoto: frase calma ("Nessuna mail qui") senza disegni.

## 3. Mail aperta
```
┌────────────────────────────────┐
│ ←  (MR) Mario Rossi         ⋮  │
│     oggi 08:12 · [Clienti ▾]   │  il gruppo si tocca per spostarla
│ Offerta capannone Bassano      │
│ ┌ In breve ──────────────────┐ │  tinta leggera del colore del gruppo
│ │ Chiede l'offerta entro ven.│ │
│ └────────────────────────────┘ │
│ Buongiorno, ci servirebbe...   │  testo della mail
│ Allegati: pianta.pdf (482 kB)  │
│ ┌ Risposta pronta ───────────┐ │  si scrive DIRETTAMENTE qui (niente "Modifica")
│ │ A: rossi@esempio.it      ▾ │ │  tocco su A/Oggetto = si aprono per cambiarli
│ │ Buongiorno Mario,          │ │
│ │ l'offerta arriva giovedì.  │ │
│ └────────────────────────────┘ │
├────────────────────────────────┤
│ [ Rifai ]          [ Invia → ] │  barra fissa in basso, Invia nel colore dell'accento
└────────────────────────────────┘
```
- Ordine: intestazione (mittente con cerchio colorato, ora, gruppo), oggetto, eventuale avviso
  truffa (in cima, sopra "In breve"), "In breve", testo, allegati, risposta.
- Mail "da controllare": sotto l'intestazione una riga compatta "È nel gruppo giusto?"
  [Sì] [Cambia]. Le altre non mostrano nessun riquadro sul gruppo (basta l'etichetta).
- Menu ⋮ con le azioni rare: Segna come da leggere, Non serve rispondere / Va risposta,
  Segnala come possibile truffa, Scarta la risposta.
- Barra in basso:
  - con una risposta pronta: [Rifai] [Invia →] (Invia apre la conferma di sempre);
  - senza risposta ma "da rispondere": [Prepara la risposta];
  - durante la preparazione: "Sol sta scrivendo la risposta…" con un indicatore;
  - mail sospetta senza risposta chiesta: nessuna barra (il pulsante "Non è una truffa" è
    nell'avviso).
- Aprire una mail la segna letta (come oggi); niente pulsante "Segna come letta" in vista.
- Tutte le garanzie dell'invio restano identiche (versione riletta, conferma, campi bloccati,
  salvataggio delle modifiche).

## 4. Gesti (solo telefono)
- Scorri a DESTRA su una riga: letta ↔ da leggere. Sfondo dell'accento chiaro con l'icona.
- Scorri a SINISTRA: si apre il foglio "Sposta in" con i gruppi colorati (cerchio + nome +
  descrizione corta). Scelto il gruppo: la riga esce con un movimento se non appartiene più
  alla vista, e compare in basso "Spostata in Clienti · Annulla" per 5 secondi (Annulla la
  rimette nel gruppo di prima). "Ricorda questo mittente" acceso come sempre.
- Mai azioni distruttive a gesto: niente scarta, niente invia.
- Sul PC restano trascinamento e scorciatoie (1-9, Invio…), con gli stessi colori.

## 5. Movimenti
- Brevi e calmi: 150-250 ms, curva morbida. Righe che entrano dall'alto quando arrivano mail
  nuove; riga che scivola via quando la sposti; schede dei gruppi che cambiano colore con una
  dissolvenza; dopo l'invio, la risposta si chiude con un segno di spunta che compare e la mail
  passa a "risposta data".
- Niente rimbalzi, niente coriandoli, niente animazioni in loop.
- Se il sistema chiede meno animazioni (Android: animazioni disattivate; PC:
  `prefers-reduced-motion`), si tolgono tutte.

## 6. Cosa NON cambia
Testi già approvati, comportamento di invio e conferma, gestione degli errori, abbinamento,
notifiche, cache, accessibilità (tocchi ≥ 48 dp, lettore schermo che legge prima i segnali
importanti), tema chiaro e scuro.
