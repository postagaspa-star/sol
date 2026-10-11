/*
 * Sol 2.0 per il computer: l'interfaccia (docs/aspetto.md e il prototipo approvato in docs/prototipo/).
 *
 * Una finestra sul server di casa: qui non si decide niente sulla posta, si mostra quello che il server
 * sa e si mandano i gesti dell'utente. Parla SOLO col programma, attraverso api.js (/locale/* e /server/*).
 *
 * Regole:
 *  - tutto il testo che arriva dalle mail entra nella pagina come testo, mai come codice;
 *  - l'invio passa sempre da /locale/invia, dove il programma chiede la conferma con una finestra di
 *    Windows che mostra destinatari, oggetto e prima riga DELLA VERSIONE che parte;
 *  - ogni riquadro di risposta lavora solo sulla sua bozza (niente stato condiviso tra riquadri);
 *  - smistare a mano deve essere rapidissimo ma mai pericoloso: un'azione alla volta, niente ripetizione
 *    automatica dei tasti, niente scorciatoie con il fuoco in un campo di testo, e la pagina non cambia
 *    mail da sola se nel frattempo Manuel ha fatto altro;
 *  - niente frasi d'aiuto fisse (§9): si impara col tutorial, che lavora solo su dati finti.
 */
(function () {
  'use strict';

  const L = window.SolLogica;
  const ApiBase = window.SolApi;
  // I tempi si possono accorciare solo nei test (window.SolTempi): nel programma valgono questi.
  const T = Object.assign({ minuto: 60000, bozza: 2000, salva: 1500, outlook: 5000, riassunto: 2000, annulla: 5000 }, window.SolTempi || {});
  const MAX_SENZA_CONFERMA = 5;
  const MAX_GRUPPI = 16;
  const PER_GRUPPO = 15;
  const ESEMPIO_SERVER = 'https://sol.esempio.ts.net';
  /** I 12 colori dei gruppi a parole (contratto §17): il colore non è mai l'unica informazione. */
  const NOMI_COLORI = {
    blu: 'Blu', verde: 'Verde', ambra: 'Ambra', viola: 'Viola', grigio: 'Grigio', ardesia: 'Ardesia',
    petrolio: 'Petrolio', rosa: 'Rosa', indaco: 'Indaco', oliva: 'Oliva', marrone: 'Marrone', ciano: 'Ciano',
  };
  // §16.3: il testo dell'avviso in cima a una mail segnalata, parola per parola.
  const AVVISO_TRUFFA = 'Attenzione: questa mail potrebbe essere una truffa. Prima di pagare, cambiare coordinate bancarie o mandare documenti, telefona al mittente a un numero che conosci già, non a quello scritto nella mail.';
  const VISTE = ['oggi', 'posta', 'riassunti', 'scadenze', 'aspetti'];

  const S = {
    motore: null,             // GET /locale/stato
    salute: null,             // GET /server/api/salute
    conteggi: null,           // GET /server/api/conteggi (§15.4, §18.2)
    gruppi: [],               // GET /server/api/gruppi, già ordinati
    impostazioni: null,       // GET /server/api/impostazioni (per il nome, §18.3)
    vista: null,
    filtro: { tipo: 'importanti' },
    filtroPrima: null,        // da dove si è aperto un gruppo o la ricerca, per tornarci
    cerca: '',
    ricerca: false,           // la Posta mostra il campo di ricerca al posto dei segmenti
    elenco: { mail: [], altre: false, altrePerGruppo: new Map(), incerte: null, caricando: false, errore: null, giro: 0 },
    selezione: new Set(),
    ancora: null,
    attiva: null,
    aperta: null,             // { id, mail, caricando, errore, memoria }
    editor: null,             // il riquadro della risposta della mail aperta
    solleciti: new Map(),     // id della mail inviata -> riquadro del suo sollecito ("Aspetti risposta")
    oggi: { dati: null, errore: null, caricando: false, aspetti: null },
    nomiMail: {},             // id della mail -> chi l'ha scritta, per i riferimenti dei riassunti
    smista: null,             // { mail: [], i, totale, gruppiAperti, finito }
    tutorial: null,           // il tutorial aperto
    ricorda: true,            // le correzioni di UNA mail insegnano a Sol il mittente (le regole)
    occupato: null,           // l'azione di smistamento in corso: finché non finisce, niente azioni nuove
    velo: null,               // la finestrella o il foglio aperto sopra la pagina
    tastoInCampo: null,       // l'ultimo tasto battuto in un campo di testo: { el, t }
    chiusura: null,           // il salvataggio chiesto dal programma prima di chiudere
    memoriaDel: null,         // dati vecchi mostrati perché il server non risponde
    abbinamento: false,
    aggiornando: false,
    trascina: null,
    dom: {},
  };

  // --- Le chiamate (api.js) con due attenzioni in più ------------------------------------------

  const Api = {
    get: (p) => dopo(p, ApiBase.get(p)),
    post: (p, c) => dopo(p, ApiBase.post(p, c)),
    put: (p, c) => dopo(p, ApiBase.put(p, c)),
    patch: (p, c) => dopo(p, ApiBase.patch(p, c)),
    elimina: (p) => dopo(p, ApiBase.elimina(p)),
  };

  /** Dopo ogni risposta del server: dati vecchi o freschi, e computer non più riconosciuto (401). */
  async function dopo(percorso, promessa) {
    const r = await promessa;
    if (percorso.startsWith('/server/')) {
      const prima = S.memoriaDel;
      if (r.memoria) S.memoriaDel = r.memoria;
      else if (r.ok) S.memoriaDel = null;
      if (r.stato === 401 && S.motore && !S.motore.non_riconosciuto) {
        S.motore.non_riconosciuto = true;
        ridisegnaStatoPresto();
      } else if (prima !== S.memoriaDel) {
        ridisegnaStatoPresto();
      }
    }
    return r;
  }

  let statoInAttesa = false;
  function ridisegnaStatoPresto() {
    if (statoInAttesa) return;
    statoInAttesa = true;
    setTimeout(() => { statoInAttesa = false; renderStato(); }, 0);
  }

  // --- Costruire la pagina senza HTML da stringhe --------------------------------------

  function h(tag, attributi, ...figli) {
    const el = document.createElement(tag);
    if (attributi) {
      for (const [nome, valore] of Object.entries(attributi)) {
        if (valore === null || valore === undefined || valore === false) continue;
        if (nome === 'class') el.className = valore;
        else if (nome.startsWith('on') && typeof valore === 'function') el.addEventListener(nome.slice(2), valore);
        else if (nome === 'value') el.value = valore;
        else if (nome === 'checked') el.checked = !!valore;
        else if (valore === true) el.setAttribute(nome, '');
        else el.setAttribute(nome, String(valore));
      }
    }
    for (const figlio of figli.flat(Infinity)) {
      if (figlio === null || figlio === undefined || figlio === false) continue;
      el.append(figlio instanceof Node ? figlio : document.createTextNode(String(figlio)));
    }
    return el;
  }

  function svuota(el, ...figli) {
    el.replaceChildren();
    for (const f of figli.flat(Infinity)) if (f) el.append(f instanceof Node ? f : document.createTextNode(String(f)));
    return el;
  }

  const SVG = 'http://www.w3.org/2000/svg';
  const ICONE = {
    oggi: 'M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8 M12 2.5v2 M12 19.5v2 M2.5 12h2 M19.5 12h2 M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M5.3 18.7l1.4-1.4 M17.3 6.7l1.4-1.4',
    posta: 'M4 13.5l2.2-7.2A2 2 0 0 1 8.1 5h7.8a2 2 0 0 1 1.9 1.3l2.2 7.2 M4 13.5V18a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 18v-4.5h-4.5l-1.5 2.5h-4l-1.5-2.5z',
    riassunti: 'M5 6.5h14 M5 11h14 M5 15.5h9',
    scadenze: 'M6.5 5h11a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3h-11a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z M3.5 10h17 M8 3v4 M16 3v4',
    aspetti: 'M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17 M12 7.5V12l3 2',
    impostazioni: 'M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5v4 M9 15v4',
    cerca: 'M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13 M16 16l4 4',
    chiudi: 'M6 6l12 12 M18 6L6 18',
    indietro: 'M15 5l-7 7 7 7',
    giu: 'M6 9l6 6 6-6',
    destra: 'M9 6l6 6-6 6',
    freccia: 'M5 12h14 M13 6l6 6-6 6',
    spunta: 'M5 12.5l4.5 4.5L19 7.5',
    matita: 'M4 20l4.5-1 10-10a2.1 2.1 0 0 0-3-3l-10 10z',
    triangolo: 'M12 3.5l9 16H3z M12 10v4.5 M12 17.2v.3',
    strati: 'M12 3.5l8.5 4.5-8.5 4.5-8.5-4.5z M3.5 12.5l8.5 4.5 8.5-4.5 M3.5 16.5l8.5 4.5 8.5-4.5',
    allegato: 'M20 11.5l-7.8 7.8a5 5 0 0 1-7.1-7.1l8-8a3.4 3.4 0 0 1 4.8 4.8l-8 8a1.7 1.7 0 0 1-2.4-2.4l7.3-7.3',
    raccolta: 'M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z M8 9h8 M8 13h8 M8 17h5',
    microfono: 'M12 3.5a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0v-5a3 3 0 0 0-3-3z M6 11a6 6 0 0 0 12 0 M12 17v3.5',
    aggiorna: 'M19.5 12a7.5 7.5 0 1 1-2.3-5.4 M19.5 4.5v4.2h-4.2',
    rete: 'M3 9a13 13 0 0 1 18 0 M6 12.5a8.5 8.5 0 0 1 12 0 M9.5 16a3.5 3.5 0 0 1 5 0 M12 19.5v.2',
    piu: 'M12 5v14 M5 12h14',
    persona: 'M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8 M4.5 20a7.5 7.5 0 0 1 15 0',
    busta: 'M3.5 6.5h17v11h-17z M3.5 7l8.5 6 8.5-6',
    cartella: 'M4 6.5h6l2 2h8v10H4z',
    scudo: 'M12 3l8 3v5c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z',
    campana: 'M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z M10 20.5h4',
    computer: 'M4 5h16v11H4z M9 20h6 M12 16v4',
    luna: 'M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z',
    aiuto: 'M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17 M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5v.7 M12 16.8v.2',
    info: 'M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17 M12 11v5 M12 8v.2',
    fumetto: 'M4 5h16v11H9l-5 4z',
    telefono: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M11 18h2',
    catena: 'M9 15l6-6 M8 8.5l-3 3a4 4 0 0 0 5.7 5.7l3-3 M16 15.5l3-3a4 4 0 0 0-5.7-5.7l-3 3',
    soglia: 'M4 18h16 M4 12h10 M4 6h6',
    prova: 'M9 3h6 M10 3v6l-5 9a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3l-5-9V3',
    giorni: 'M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17 M12 8v4h4',
  };

  function icona(nome, classe) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'icona' + (classe ? ' ' + classe : ''));
    for (const d of (ICONE[nome] || '').split(' M').map((p, i) => (i === 0 ? p : 'M' + p))) {
      const path = document.createElementNS(SVG, 'path');
      path.setAttribute('d', d);
      svg.append(path);
    }
    return svg;
  }

  /** Il sole di Sol (come nel prototipo): un cerchio pieno coi raggi. */
  function sole(classe) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'sole-svg' + (classe ? ' ' + classe : ''));
    const c = document.createElementNS(SVG, 'circle');
    c.setAttribute('cx', '12'); c.setAttribute('cy', '12'); c.setAttribute('r', '4.2');
    const p = document.createElementNS(SVG, 'path');
    p.setAttribute('d', 'M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4');
    svg.append(c, p);
    return svg;
  }

  /** I tre puntini pieni del pulsante "altre azioni". */
  function puntini() {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'icona');
    for (const x of ['5.5', '12', '18.5']) {
      const c = document.createElementNS(SVG, 'circle');
      c.setAttribute('cx', x); c.setAttribute('cy', '12'); c.setAttribute('r', '1.8');
      c.setAttribute('class', 'puntino');
      svg.append(c);
    }
    return svg;
  }

  // --- Avvisi brevi, conferme sul posto, finestrelle ---------------------------------------------

  /** Un avviso breve in basso al centro; con `azione` ({ etichetta, fai }) per esempio "Annulla". */
  function avviso(testo, tipo, azione) {
    if (!testo) return;
    const zona = document.getElementById('avvisi');
    const el = h('div', { class: 'avviso ' + (tipo || 'info'), role: tipo === 'errore' ? 'alert' : null }, h('span', null, testo));
    if (azione) {
      el.append(h('button', { class: 'avviso-azione', type: 'button', onclick: () => { el.remove(); azione.fai(); } }, azione.etichetta));
    }
    el.append(h('button', { class: 'avviso-chiudi', type: 'button', 'aria-label': 'Chiudi', onclick: () => el.remove() }, icona('chiudi')));
    zona.append(el);
    setTimeout(() => el.remove(), tipo === 'errore' ? 12000 : (azione ? T.annulla : 5000));
  }

  /** Un pulsante che al primo clic chiede "Sei sicuro?" sul posto, senza finestre a comparsa. */
  function conConferma(etichetta, domanda, siEtichetta, azione, classe) {
    const contenitore = h('span', { class: 'con-conferma' });
    const iniziale = () => svuota(contenitore, h('button', { type: 'button', class: classe || 'pulsante bianco piccolo', onclick: chiedi }, etichetta));
    function chiedi() {
      svuota(contenitore,
        h('span', { class: 'domanda' }, domanda),
        h('button', { type: 'button', class: 'pulsante pericolo piccolo', onclick: async () => { svuota(contenitore, h('span', { class: 'attesa' }, 'Un momento…')); await azione(); if (contenitore.isConnected) iniziale(); } }, siEtichetta),
        h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: iniziale }, 'No'));
    }
    iniziale();
    return contenitore;
  }

  function messaggioInLinea(testo, tipo) {
    return testo ? h('p', { class: 'in-linea ' + (tipo || 'errore'), role: tipo === 'errore' || !tipo ? 'alert' : null }, testo) : null;
  }

  /**
   * Una domanda sopra la pagina. Il fuoco va sul pulsante prudente ("Annulla"): un Invio di troppo
   * non fa danni. Finché è aperta, le scorciatoie della pagina non fanno niente.
   */
  function chiediConferma(o) {
    return new Promise((fine) => {
      let chiusa = false;
      // Sopra un foglio aperto (impostazioni): chiusa la domanda, Esc torna a chiudere il foglio.
      const sotto = S.velo;
      const chiudi = (esito) => {
        if (chiusa) return;
        chiusa = true;
        velo.remove();
        S.velo = sotto && sotto.foglio && document.querySelector('.foglio') ? sotto : null;
        fine(esito);
      };
      const si = h('button', { type: 'button', class: 'pulsante ' + (o.pericolo ? 'pericolo' : 'pieno'), onclick: () => chiudi(true) }, o.si);
      const no = h('button', { type: 'button', class: 'pulsante bianco', onclick: () => chiudi(false) }, o.no || 'Annulla');
      const velo = h('div', { class: 'velo', onclick: (e) => { if (e.target === velo) chiudi(false); } },
        h('div', { class: 'finestrella', role: 'dialog', 'aria-modal': 'true', 'aria-label': o.titolo },
          h('h2', null, o.titolo),
          o.testo ? h('p', null, o.testo) : null,
          h('div', { class: 'azioni' }, no, si)));
      S.velo = { chiudi: () => chiudi(false) };
      document.body.append(velo);
      no.focus();
    });
  }

  /**
   * Un foglio che si apre da destra (impostazioni, "cos'è la modalità prova"): titolo, contenuto, X per
   * chiudere (anche Esc o clic fuori). `riempi(corpo, chiudi)` lo riempie.
   */
  function apriFoglio(titolo, riempi) {
    if (S.velo) S.velo.chiudi();
    const corpo = h('div', { class: 'corpo-foglio' });
    const chiudi = () => { velo.remove(); foglio.remove(); if (S.velo === voce) S.velo = null; };
    const velo = h('div', { class: 'velo-foglio', onclick: chiudi });
    const foglio = h('aside', { class: 'foglio', role: 'dialog', 'aria-modal': 'true', 'aria-label': titolo },
      h('div', { class: 'testa-foglio' }, h('h2', null, titolo),
        h('button', { type: 'button', class: 'rotondo', 'aria-label': 'Chiudi', onclick: chiudi }, icona('chiudi'))),
      corpo);
    const voce = { chiudi, foglio: true };
    S.velo = voce;
    document.body.append(velo, foglio);
    riempi(corpo, chiudi);
    const primo = corpo.querySelector('input, textarea, select, button') || foglio.querySelector('button');
    if (primo) primo.focus();
    return { corpo, chiudi };
  }

  function campoDiTesto(el) {
    return !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));
  }

  /** Il pulsante del microfono: apre la dettatura di Windows (Win+H) nel campo accanto. */
  function microfono(campo) {
    return h('button', { type: 'button', class: 'rotondo microfono', 'aria-label': 'Detta', title: 'Detta',
      onclick: async () => {
        campo.focus();
        const r = await Api.post('/locale/detta', {});
        if (!r.ok) avviso(r.errore || 'La dettatura qui non è disponibile.', 'errore');
      } }, icona('microfono'));
  }

  function applicaTema() {
    const t = L.temaPagina(S.motore && S.motore.tema);
    if (t) document.documentElement.setAttribute('data-tema', t);
    else document.documentElement.removeAttribute('data-tema');
  }

  // --- Avvio -------------------------------------------------------------------------------

  async function avvia() {
    document.addEventListener('keydown', suTasto);
    document.addEventListener('click', (e) => {
      if (!e.target.closest || !e.target.closest('.ancora-menu')) chiudiFinestrelleMail();
    });
    window.addEventListener('hashchange', () => vaiAlFrammento(location.hash));
    await leggiStato();
    applicaTema();
    if (!S.motore || !S.motore.abbinato) {
      mostraAbbinamento();
    } else {
      await entra();
      const frammento = location.hash;
      if (frammento && L.destinazioneDaFrammento(frammento)) await vaiAlFrammento(frammento);
      else await vaiVista('oggi', { forza: true });
      if (S.motore && S.motore.tutorial_visto === false) apriTutorial(true);
    }
    document.getElementById('app').removeAttribute('aria-busy');
    setInterval(giroPeriodico, T.minuto);
  }

  /** Dopo l'avvio (o l'abbinamento): lo scheletro della finestra e i dati di base. */
  async function entra() {
    costruisciScheletro();
    await Promise.all([leggiSalute(), leggiGruppi(), leggiConteggi(), leggiImpostazioni()]);
    renderRail();
    renderStato();
  }

  async function leggiStato() {
    const r = await Api.get('/locale/stato');
    if (r.ok) S.motore = r.dati;
  }

  async function leggiSalute() {
    const r = await Api.get('/server/api/salute');
    if (r.ok && r.dati) {
      S.salute = r.dati;
      // §15.12: un 200 di /api/salute non prova niente; conta "autenticato".
      if (r.dati.autenticato === false && S.motore) S.motore.non_riconosciuto = true;
    }
  }

  async function leggiGruppi() {
    const r = await Api.get('/server/api/gruppi');
    if (r.ok && Array.isArray(r.dati)) S.gruppi = L.gruppiOrdinati(r.dati);
  }

  /** I contatori (§15.4) e, per ogni gruppo, l'ultima mail (§18.2). Un server di prima della v1.1 non li ha. */
  async function leggiConteggi() {
    const r = await Api.get('/server/api/conteggi');
    if (r.ok && r.dati && typeof r.dati === 'object') S.conteggi = r.dati;
    else if (r.stato === 404 || r.stato === 405) S.conteggi = null;
  }

  async function leggiImpostazioni() {
    const r = await Api.get('/server/api/impostazioni');
    if (r.ok && r.dati && typeof r.dati === 'object') S.impostazioni = r.dati;
  }

  async function aggiornaContatori() {
    await Promise.all([leggiGruppi(), leggiConteggi()]);
    renderRail();
  }

  async function giroPeriodico() {
    if (S.abbinamento) return;
    await leggiStato();
    if (!S.motore || !S.motore.abbinato) return;
    await Promise.all([leggiSalute(), leggiGruppi(), leggiConteggi()]);
    renderRail();
    renderStato();
    if (S.tutorial) return;
    if (S.vista === 'posta') await caricaElenco({ silenzioso: true });
    else if (S.vista === 'oggi') await caricaOggi(true);
  }

  // --- Abbinamento (primo avvio) ---------------------------------------------------------------

  function mostraAbbinamento(messaggio) {
    S.abbinamento = true;
    distruggiEditori();
    const app = document.getElementById('app');
    app.className = 'app solo';
    const m = S.motore || {};
    const cifre = Array.from({ length: 8 }, (_, i) => h('input', {
      type: 'text', inputmode: 'numeric', maxlength: '1', autocomplete: 'off', class: 'cifra', 'aria-label': 'Cifra ' + (i + 1) + ' del codice',
    }));
    // Le 8 caselle si riempiono da sole scrivendo o incollando il codice intero.
    cifre.forEach((c, i) => {
      c.addEventListener('input', () => {
        const tutte = c.value.replace(/\D/g, '');
        if (tutte.length > 1) {
          tutte.split('').slice(0, 8 - i).forEach((x, j) => { cifre[i + j].value = x; });
          cifre[Math.min(7, i + tutte.length - 1)].focus();
          return;
        }
        c.value = tutte;
        if (tutte && i < 7) cifre[i + 1].focus();
      });
      c.addEventListener('keydown', (e) => {
        if (e.key === 'Backspace' && !c.value && i > 0) { cifre[i - 1].focus(); cifre[i - 1].value = ''; e.preventDefault(); }
        if (e.key === 'ArrowLeft' && i > 0) cifre[i - 1].focus();
        if (e.key === 'ArrowRight' && i < 7) cifre[i + 1].focus();
      });
      c.addEventListener('paste', (e) => {
        const testo = (e.clipboardData && e.clipboardData.getData('text')) || '';
        const solo = testo.replace(/\D/g, '');
        if (!solo) return;
        e.preventDefault();
        solo.split('').slice(0, 8).forEach((x, j) => { cifre[j].value = x; });
        cifre[Math.min(7, solo.length - 1)].focus();
      });
    });
    const campi = {
      server: h('input', { type: 'text', id: 'ab-server', value: m.server || '', placeholder: ESEMPIO_SERVER, autocomplete: 'off', spellcheck: 'false' }),
      nome: h('input', { type: 'text', id: 'ab-nome', value: m.nome_dispositivo || m.nome_suggerito || '', maxlength: '60' }),
      avvio: h('input', { type: 'checkbox', id: 'ab-avvio', checked: true }),
    };
    const esito = h('div', { class: 'esito' }, messaggioInLinea(messaggio, 'avvertenza'));
    const pulsante = h('button', { type: 'submit', class: 'pulsante pieno grande' }, 'Collega');
    const modulo = h('form', {
      class: 'abbinamento',
      onsubmit: async (e) => {
        e.preventDefault();
        if (pulsante.disabled) return;
        pulsante.disabled = true;
        svuota(esito, h('p', { class: 'attesa' }, 'Collegamento in corso…'));
        const r = await Api.post('/locale/abbina', {
          server: campi.server.value,
          codice: cifre.map((c) => c.value).join(''),
          nome: campi.nome.value,
          avvio_al_login: campi.avvio.checked,
        });
        pulsante.disabled = false;
        if (!r.ok) {
          svuota(esito, messaggioInLinea(r.errore));
          return;
        }
        S.abbinamento = false;
        await leggiStato();
        await entra();
        await vaiVista('oggi', { forza: true });
        avviso('Computer collegato.', 'ok');
        if (S.motore && S.motore.tutorial_visto === false) apriTutorial(true);
      },
    },
      sole(),
      h('h1', null, 'Collega Sol al server di casa'),
      h('label', { for: 'ab-server' }, 'Indirizzo del server'),
      campi.server,
      h('label', { id: 'ab-codice-etichetta' }, 'Codice di 8 cifre'),
      h('div', { class: 'cifre', role: 'group', 'aria-labelledby': 'ab-codice-etichetta', id: 'ab-codice' }, cifre),
      h('label', { for: 'ab-nome' }, 'Nome di questo computer'),
      campi.nome,
      m.avvio_disponibile ? h('label', { class: 'spunta' }, campi.avvio, 'Apri Sol quando accendo il computer') : null,
      esito,
      pulsante);
    svuota(app, h('div', { class: 'pagina-abbinamento' }, modulo));
    (m.server ? cifre[0] : campi.server).focus();
  }

  // --- Scheletro: colonna stretta a sinistra, stato in alto, contenuto -------------------------

  function costruisciScheletro() {
    const app = document.getElementById('app');
    app.className = 'app';
    S.dom.rail = h('nav', { class: 'rail', 'aria-label': 'Sezioni' });
    S.dom.stato = h('div', { class: 'stato-alto' });
    S.dom.area = h('div', { class: 'area' });
    S.dom.contenuto = h('main', { class: 'contenuto' }, S.dom.stato, S.dom.area);
    svuota(app, S.dom.rail, S.dom.contenuto);
  }

  const NOMI_VISTE = { oggi: 'Oggi', posta: 'Posta', riassunti: 'Riassunti', scadenze: 'Scadenze', aspetti: 'Aspetti risposta' };

  function renderRail() {
    if (!S.dom.rail || S.abbinamento) return;
    const daLeggere = L.daLeggereImportanti(S.gruppi, S.conteggi);
    const nome = (S.impostazioni && S.impostazioni.nome) || (S.oggi.dati && S.oggi.dati.nome) || '';
    const iniziale = L.inizialeNome(nome);
    const voce = (vista) => h('button', {
      type: 'button', class: 'voce-rail', 'data-vista': vista,
      'aria-current': S.vista === vista || (vista === 'posta' && S.vista === 'smista') ? 'page' : null,
      onclick: () => vaiVista(vista, vista === 'posta' ? { filtro: { tipo: 'importanti' } } : null),
    },
      h('span', { class: 'pillola-icona' }, icona(vista),
        vista === 'posta' && daLeggere > 0 ? h('span', { class: 'pallino-numero', 'aria-label': daLeggere + ' da leggere' }, daLeggere > 99 ? '99+' : String(daLeggere)) : null),
      NOMI_VISTE[vista]);
    svuota(S.dom.rail,
      h('div', { class: 'marchio' }, sole(), 'Sol'),
      VISTE.map(voce),
      h('span', { class: 'spazio' }),
      h('button', {
        type: 'button', class: 'voce-tu', 'data-vista': 'impostazioni', 'aria-label': 'Impostazioni', title: 'Impostazioni',
        'aria-current': S.vista === 'impostazioni' ? 'page' : null, onclick: () => vaiVista('impostazioni'),
      }, iniziale || icona('impostazioni')));
  }

  function oraDi(iso) {
    const d = new Date(iso);
    return isNaN(d) ? '' : String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  }

  /** In alto, solo quando serve: senza collegamento, collegamento di casa non protetto, versione nuova, 401. */
  function renderStato() {
    if (!S.dom.stato || S.abbinamento) return;
    const m = S.motore || {};
    const sa = S.salute || {};
    const pezzi = [];
    if (m.non_riconosciuto) {
      pezzi.push(h('div', { class: 'striscia-fissa', role: 'alert' },
        h('span', null, 'Il server di casa non riconosce più questo computer: le mail e le notifiche non arrivano.'),
        h('button', { type: 'button', class: 'pulsante pieno piccolo', onclick: () => mostraAbbinamento('Collega di nuovo questo computer con un codice nuovo.') }, 'Collega di nuovo')));
    } else if (m.raggiungibile === false || S.memoriaDel) {
      const quando = S.memoriaDel ? ' · dati delle ' + oraDi(S.memoriaDel) : '';
      pezzi.push(h('span', { class: 'pillola-stato', role: 'status' }, icona('rete'), 'Senza collegamento' + quando));
    }
    if (m.senza_protezione || L.senzaProtezione(m.server)) {
      pezzi.push(h('span', { class: 'pillola-stato', title: 'Il programma parla col server con un indirizzo http di casa: per le password serve l\'indirizzo https di Tailscale.' }, 'Collegamento di casa non protetto'));
    }
    if (sa.decisore && sa.decisore.stato === 'in_avvio') {
      const pct = typeof sa.decisore.percentuale === 'number' ? ' (' + Math.round(sa.decisore.percentuale) + '%)' : '';
      pezzi.push(h('span', { class: 'pillola-stato' }, 'Sol si sta avviando' + pct));
    } else if (sa.decisore && sa.decisore.stato === 'non_disponibile') {
      pezzi.push(h('span', { class: 'pillola-stato' }, 'Lo smistamento è fermo: le mail arrivano lo stesso'));
    }
    if (sa.scrittore && sa.scrittore.stato === 'non_disponibile') {
      pezzi.push(h('span', { class: 'pillola-stato' }, 'Sol adesso non riesce a scrivere bozze e riassunti'));
    }
    if (m.aggiornamento && m.aggiornamento.disponibile) {
      pezzi.push(h('button', { type: 'button', class: 'pillola-stato', onclick: () => apriImpostazione('versione') }, 'Versione ' + m.aggiornamento.disponibile + ' disponibile'));
    }
    svuota(S.dom.stato, pezzi);
  }

  async function ricaricaTutto() {
    if (S.aggiornando) return;
    S.aggiornando = true;
    try {
      // Riassunti, Scadenze e Aspetti si ridisegnano da capo: prima i solleciti non salvati.
      if (!['posta', 'oggi', 'smista'].includes(S.vista) && !(await puoLasciare())) return;
      await Api.post('/locale/aggiorna-adesso');
      await Promise.all([leggiStato(), leggiSalute(), leggiGruppi(), leggiConteggi()]);
      renderRail();
      renderStato();
      if (S.vista === 'posta') await caricaElenco({ silenzioso: true });
      else if (S.vista === 'oggi') await caricaOggi(true);
      else if (S.vista !== 'smista') {
        distruggiSolleciti();
        renderArea();
      }
    } finally {
      S.aggiornando = false;
    }
  }

  // --- Spostarsi tra le viste, senza perdere quello che si sta scrivendo -------------------------

  function editoriAperti() {
    const l = [];
    if (S.editor && !S.editor.distrutto) l.push(S.editor);
    for (const ed of S.solleciti.values()) if (!ed.distrutto) l.push(ed);
    return l;
  }

  function inviaInCorso() {
    return editoriAperti().some((ed) => ed.invio);
  }

  /**
   * Prima di lasciare una mail o una vista si salva la risposta che si sta scrivendo. Se il salvataggio
   * non riesce, si resta dove si è e il riquadro chiede: "resta qui" o "scarta le modifiche" (poi `dopo`).
   */
  async function puoLasciare(dopo) {
    for (const ed of editoriAperti()) {
      if (ed.invio) {
        avviso('Aspetta: la risposta sta aspettando la conferma d\'invio.', 'info');
        return false;
      }
      if (!(await ed.salvaSeServe())) {
        ed.mostraNonSalvata(dopo);
        return false;
      }
    }
    return true;
  }

  function chiudiEditorPosta() {
    if (S.editor) S.editor.distruggi();
    S.editor = null;
  }

  function distruggiSolleciti() {
    for (const ed of S.solleciti.values()) ed.distruggi();
    S.solleciti.clear();
  }

  function distruggiEditori() {
    chiudiEditorPosta();
    distruggiSolleciti();
  }

  /** Va a una vista. o: { filtro } per la Posta, { apriSollecito } per "Aspetti risposta". */
  async function vaiVista(vista, o) {
    const opz = o || {};
    if (vista === S.vista && vista !== 'posta' && !opz.forza) return;
    if (vista === 'posta' && S.vista === 'posta') {
      if (opz.filtro && !L.stessoFiltro(opz.filtro, S.filtro)) await scegliFiltro(opz.filtro);
      return;
    }
    if (!(await puoLasciare(() => vaiVista(vista, o)))) return;
    distruggiEditori();
    S.aperta = null;
    S.selezione = new Set();
    S.attiva = null;
    S.ancora = null;
    if (vista === 'posta') {
      S.filtro = opz.filtro || { tipo: 'importanti' };
      S.cerca = '';
      S.ricerca = false;
      S.elenco.mail = [];
    }
    if (vista === 'smista') S.smistaDa = S.vista;
    S.vista = vista;
    S.vistaOpzioni = opz;
    history.replaceState(null, '', location.pathname);
    renderRail();
    renderArea();
    if (vista === 'posta') await caricaElenco();
  }

  async function vaiAlFrammento(frammento) {
    const d = L.destinazioneDaFrammento(frammento);
    if (!d || S.abbinamento) return;
    if (S.tutorial) chiudiTutorial(false);
    if (d.mailId) {
      if (!(await puoLasciare(() => vaiAlFrammento(frammento)))) return;
      if (S.vista !== 'posta') {
        distruggiEditori();
        S.vista = 'posta';
        S.filtro = { tipo: 'importanti' };
        S.cerca = '';
        S.ricerca = false;
        S.elenco.mail = [];
        renderRail();
        renderArea();
        await caricaElenco({ silenzioso: true });
      } else if (!S.elenco.mail.length && !S.elenco.caricando) {
        await caricaElenco({ silenzioso: true });
      }
      S.selezione = new Set([d.mailId]);
      S.attiva = d.mailId;
      S.ancora = d.mailId;
      renderElenco();
      await apriMail(d.mailId, true);
      return;
    }
    if (d.vista === 'posta') await vaiVista('posta', { filtro: d.filtro });
    else await vaiVista(d.vista, { forza: true });
  }

  function renderArea() {
    if (!S.dom.area) return;
    S.dom.contenuto.className = 'contenuto ' + S.vista;
    S.dom.area.className = 'area ' + S.vista;
    if (S.vista === 'oggi') return vistaOggi();
    if (S.vista === 'posta') return costruisciPosta();
    if (S.vista === 'smista') return vistaSmista();
    if (S.vista === 'riassunti') return vistaRiassunti();
    if (S.vista === 'aspetti') return vistaAspetti();
    if (S.vista === 'scadenze') return vistaScadenze();
    if (S.vista === 'notifiche') return vistaNotifiche();
    if (S.vista === 'impostazioni') return vistaImpostazioni();
    return null;
  }

  function classeColore(g) {
    return 'col-' + L.coloreGruppo(g && g.colore);
  }

  function gruppoDi(m) {
    return (m && S.gruppi.find((g) => g.id === m.gruppo_id)) || null;
  }

  /** Lascia far vedere i movimenti? No se Windows chiede meno animazioni (o se non si può sapere). */
  function conMovimenti() {
    return !!(window.matchMedia && !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function nomeMittente(m) {
    return (m.mittente && (m.mittente.nome || m.mittente.indirizzo)) || '(mittente sconosciuto)';
  }

  function inizialiDi(m) {
    return L.iniziali(m.mittente && m.mittente.nome, m.mittente && m.mittente.indirizzo);
  }

  // --- Oggi: il punto della giornata e cosa fare (§3, GET /api/oggi) -------------------------------

  function scheletro() {
    return h('div', { class: 'scheletro', role: 'status', 'aria-label': 'In preparazione' }, h('span'), h('span'), h('span'));
  }

  /** Il testo di un riassunto con i riferimenti [#id] come parole toccabili (il nome di chi ha scritto). */
  function testoConRiferimenti(testo) {
    return L.riferimentiConNomi(testo, S.nomiMail).map((p) => (p.mailId != null
      ? h('a', { href: '#mail/' + p.mailId, class: 'riferimento', title: 'Apri la mail' }, p.testo || 'apri la mail')
      : p.testo));
  }

  const nomiChiesti = new Set();

  /** I nomi di chi ha scritto le mail citate nel riassunto (per i riferimenti toccabili). */
  async function risolviNomi(testo, ridisegna) {
    const ids = L.dividiRiferimenti(testo).filter((p) => p.mailId != null && !S.nomiMail[p.mailId] && !nomiChiesti.has(p.mailId)).map((p) => p.mailId).slice(0, 6);
    if (!ids.length) return;
    ids.forEach((id) => nomiChiesti.add(id));
    const esiti = await Promise.all(ids.map((id) => Api.get('/server/api/mail/' + id)));
    let nuovi = false;
    esiti.forEach((r, i) => {
      if (r.ok && r.dati && r.dati.mittente) { S.nomiMail[ids[i]] = nomeMittente(r.dati); nuovi = true; }
    });
    if (nuovi) ridisegna();
  }

  function vistaOggi() {
    S.dom.oggi = h('div', { class: 'pagina oggi' });
    svuota(S.dom.area, S.dom.oggi);
    disegnaOggi();
    caricaOggi();
  }

  async function caricaOggi(silenzioso) {
    if (!silenzioso && !S.oggi.dati) { S.oggi.caricando = true; disegnaOggi(); }
    const [r, ind] = await Promise.all([Api.get('/server/api/oggi'), Api.get('/server/api/indietro')]);
    let dati = null;
    if (r.ok && r.dati && typeof r.dati === 'object') dati = r.dati;
    else if (r.stato === 404 || r.stato === 405) dati = await oggiRicostruita();
    S.oggi.caricando = false;
    if (ind.ok && ind.dati) S.oggi.aspetti = (ind.dati.aspettano_te || []).length + (ind.dati.aspetti_tu || []).length;
    if (dati) {
      S.oggi.dati = dati;
      S.oggi.errore = null;
      for (const v of dati.da_fare || []) if (v.mail_id && v.mittente) S.nomiMail[v.mail_id] = v.mittente;
    } else if (!S.oggi.dati) {
      S.oggi.errore = r.errore || 'La pagina di oggi non si è caricata.';
    }
    if (S.vista !== 'oggi') return;
    disegnaOggi();
    renderRail();
    if (dati && dati.riassunto && dati.riassunto.testo) risolviNomi(dati.riassunto.testo, disegnaOggi);
  }

  /** Un server di prima della v1.4 non ha GET /api/oggi: la pagina si mette insieme da quello che c'è. */
  async function oggiRicostruita() {
    const [ri, so, dr, ds, sc, ind] = await Promise.all([
      Api.get('/server/api/riassunti/oggi'),
      Api.get('/server/api/mail?sospetta=true&limite=20'),
      Api.get('/server/api/mail?da_rispondere=true&limite=40'),
      Api.get('/server/api/mail?smistamento=da_controllare&sospetta=false&limite=200'),
      Api.get('/server/api/scadenze?stato=aperta'),
      Api.get('/server/api/indietro'),
    ]);
    const lista = (x) => (x.ok && x.dati && Array.isArray(x.dati.mail) ? x.dati.mail : []);
    const conColore = (l) => l.map((m) => Object.assign({ colore: (gruppoDi(m) || {}).colore }, m));
    return L.oggiDaRisorse({
      riassunto: ri.ok ? ri.dati : null,
      sospette: conColore(lista(so)),
      daRispondere: conColore(lista(dr)),
      daSmistare: lista(ds),
      scadenze: sc.ok && Array.isArray(sc.dati) ? sc.dati : [],
      indietro: ind.ok ? ind.dati : null,
      nome: S.impostazioni && S.impostazioni.nome,
      mailOggi: null,
    });
  }

  function disegnaOggi() {
    const p = S.dom.oggi;
    if (!p || !p.isConnected) return;
    const d = S.oggi.dati;
    const nome = (d && d.nome) || (S.impostazioni && S.impostazioni.nome) || '';
    const testa = h('div', { class: 'testa-oggi' },
      h('h1', null, L.saluto() + (nome ? ', ' + nome : '')),
      h('p', { class: 'sotto-titolo' }, L.rigaOggi(d && d.data, d && !d.ricostruita ? d.mail_oggi : null)));
    if (!d) {
      svuota(p, testa, h('section', { class: 'scheda punto-oggi' },
        h('span', { class: 'etichetta' }, 'Il punto di oggi'),
        S.oggi.errore
          ? [messaggioInLinea(S.oggi.errore), h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: () => caricaOggi() }, 'Riprova'))]
          : scheletro()));
      return;
    }
    svuota(p, testa, schedaPunto(d), sezioneDaFare(d), collegamentiOggi());
  }

  function schedaPunto(d) {
    const r = d.riassunto;
    let corpo;
    if (r && (r.stato === 'in_preparazione' || r.stato === 'in_coda' || r.stato === 'in_corso')) corpo = scheletro();
    else if (r && r.testo) corpo = h('p', { class: 'testo-punto' }, testoConRiferimenti(r.testo));
    else corpo = h('p', { class: 'testo-punto tenue' }, 'Ancora niente da riassumere.');
    const s = d.scadenza_prossima;
    return h('section', { class: 'scheda punto-oggi', 'aria-label': 'Il punto di oggi' },
      h('span', { class: 'etichetta' }, 'Il punto di oggi'),
      corpo,
      s ? h('button', { type: 'button', class: 'pillola-scadenza', onclick: () => vaiVista('scadenze') }, icona('scadenze'), L.pillolaScadenza(s)) : null);
  }

  function sezioneDaFare(d) {
    const voci = (d.da_fare || []).map((v) => [v, L.voceOggi(v)]).filter(([, t]) => t);
    const av = L.avanzamentoOggi(d.fatte_oggi, d.totale_oggi);
    const titolo = h('div', { class: 'titolo-sezione' }, h('h2', null, 'Da fare'),
      voci.length || d.fatte_oggi ? h('span', { class: 'conto' }, av.testo) : null);
    if (!voci.length) {
      return h('div', { class: 'da-fare' }, titolo,
        h('div', { class: 'tutto-fatto', role: 'status' }, h('span', { class: 'cerchio-spunta', 'aria-hidden': 'true' }, icona('spunta')), 'Niente da fare. Bella giornata.'));
    }
    const pieno = h('span');
    pieno.style.width = av.percento + '%';
    return h('div', { class: 'da-fare' }, titolo,
      h('div', { class: 'barra-avanzamento', role: 'progressbar', 'aria-label': av.testo, 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(av.percento) }, pieno),
      h('div', { class: 'elenco-voci' }, voci.map(([v, t]) => voceDaFare(v, t))));
  }

  function voceDaFare(v, t) {
    let cerchio;
    if (t.cerchio === 'truffa') cerchio = h('span', { class: 'cerchio truffa', 'aria-hidden': 'true' }, icona('triangolo'));
    else if (t.cerchio === 'smista') cerchio = h('span', { class: 'cerchio neutro', 'aria-hidden': 'true' }, icona('strati'));
    else if (t.cerchio === 'scadenza') cerchio = h('span', { class: 'cerchio neutro', 'aria-hidden': 'true' }, icona('scadenze'));
    else cerchio = h('span', { class: 'cerchio col-' + L.coloreGruppo(v.colore), 'aria-hidden': 'true' }, v.iniziali || L.iniziali(v.mittente || v.nome, v.a));
    const stile = { pieno: 'pulsante pieno', tenue: 'pulsante tenue', truffa: 'pulsante truffa-bianco' }[t.pulsante.stile] || 'pulsante tenue';
    return h('div', { class: 'voce-oggi' + (v.tipo === 'truffa' ? ' truffa' : ''), 'data-tipo': v.tipo },
      cerchio,
      h('div', { class: 'testi' }, h('div', { class: 'titolo-voce' }, t.titolo), h('div', { class: 'sotto-voce' }, t.sotto)),
      h('button', { type: 'button', class: stile, onclick: () => azioneVoce(v) }, t.pulsante.etichetta));
  }

  async function azioneVoce(v) {
    if (v.tipo === 'smista') return vaiVista('smista');
    if (v.tipo === 'sollecito') return vaiVista('aspetti', { apriSollecito: v.inviata_id, forza: true });
    if (v.tipo === 'scadenza') {
      const r = await Api.patch('/server/api/scadenze/' + v.scadenza_id, { stato: 'fatta' });
      if (!r.ok) { avviso(r.errore, 'errore'); return null; }
      avviso('Fatto.', 'ok');
      return caricaOggi(true);
    }
    if (v.mail_id) return vaiAlFrammento('#mail/' + v.mail_id);
    return null;
  }

  function collegamentiOggi() {
    const nScad = S.conteggi && typeof S.conteggi.scadenze_prossime === 'number' ? S.conteggi.scadenze_prossime : null;
    const riga = (vista, testo, n) => h('button', { type: 'button', class: 'riga-collegamento', 'data-vista': vista, onclick: () => vaiVista(vista) },
      icona(vista), h('span', { class: 'spazio' }, testo), n ? h('span', { class: 'numero' }, String(n)) : null, icona('destra'));
    return h('div', { class: 'scheda-elenco collegamenti' }, riga('scadenze', 'Scadenze', nScad), riga('aspetti', 'Aspetti risposta', S.oggi.aspetti));
  }

  // --- Posta: Importanti | Tutte, sezioni per gruppo, raccolte (§4) --------------------------------

  function titoloFiltro() {
    if (S.filtro.tipo === 'gruppo') {
      const g = S.gruppi.find((x) => x.id === Number(S.filtro.id));
      return g ? g.nome : 'Gruppo';
    }
    return (L.FILTRI[S.filtro.tipo] || L.FILTRI.tutte).titolo;
  }

  /** "Importanti" senza ricerca è a sezioni; tutto il resto (Tutte, un gruppo, i filtri, la ricerca) è un elenco per data. */
  function vistaPiatta() {
    return !(S.filtro.tipo === 'importanti' && !S.ricerca);
  }

  function costruisciPosta() {
    S.dom.colElenco = h('section', { class: 'colonna-elenco', 'aria-label': 'Elenco della posta' });
    S.dom.lettura = h('section', { class: 'colonna-mail', 'aria-label': 'Mail aperta' });
    S.dom.elenco = h('div', { class: 'elenco-mail', role: 'listbox', 'aria-multiselectable': 'true', 'aria-label': 'Mail', tabindex: '0' });
    S.dom.filtroAttivo = h('p', { class: 'filtro-attivo' });
    S.dom.infoSelezione = h('p', { class: 'info-selezione' });
    S.dom.incerte = h('div', { class: 'spazio-incerte' });
    S.dom.altre = h('div', { class: 'altre-mail' });
    svuota(S.dom.area, h('div', { class: 'posta-griglia' }, S.dom.colElenco, S.dom.lettura));
    disegnaTestaPosta();
    renderElenco();
    renderLettura();
  }

  function disegnaTestaPosta() {
    if (!S.dom.colElenco) return;
    let testa;
    if (S.filtro.tipo === 'gruppo' && !S.ricerca) {
      testa = h('div', { class: 'testa-gruppo' },
        h('button', { type: 'button', class: 'rotondo', 'aria-label': 'Torna alla posta', onclick: chiudiGruppo }, icona('indietro')),
        h('h1', null, titoloFiltro()),
        h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: segnaTutteLette }, 'Segna tutte lette'));
    } else if (S.ricerca) {
      let timer = null;
      const campo = h('input', {
        type: 'search', id: 'cerca', value: S.cerca, placeholder: 'Cerca nella posta', 'aria-label': 'Cerca nella posta', spellcheck: 'false',
        oninput: (e) => { clearTimeout(timer); const v = e.target.value; timer = setTimeout(() => cercaTesto(v), 350); },
        onkeydown: (e) => {
          if (e.key === 'Enter') { clearTimeout(timer); cercaTesto(e.target.value); }
          if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); chiudiRicerca(); }
          if (e.key === 'ArrowDown') { e.preventDefault(); S.dom.elenco.focus(); muovi(1, false); }
        },
      });
      testa = [
        h('div', { class: 'testa-elenco' }, h('h1', null, 'Posta')),
        h('div', { class: 'campo-cerca' }, icona('cerca'), campo,
          h('button', { type: 'button', class: 'rotondo', 'aria-label': 'Chiudi la ricerca', onclick: chiudiRicerca }, icona('chiudi'))),
      ];
      setTimeout(() => { if (campo.isConnected && document.activeElement !== campo) campo.focus(); }, 0);
    } else {
      const segmento = (tipo, testo) => h('button', {
        type: 'button', role: 'tab', 'data-segmento': tipo,
        'aria-selected': (tipo === 'importanti' ? S.filtro.tipo === 'importanti' : S.filtro.tipo !== 'importanti') ? 'true' : 'false',
        onclick: () => scegliFiltro({ tipo }),
      }, testo);
      testa = [
        h('div', { class: 'testa-elenco' },
          h('h1', null, 'Posta'),
          S.salute && S.salute.modalita_prova ? h('button', { type: 'button', class: 'pillola-prova', onclick: spiegaProva }, 'Prova') : null,
          h('span', { class: 'spazio' }),
          h('button', { type: 'button', class: 'rotondo', 'aria-label': 'Cerca nella posta', title: 'Cerca (/)', onclick: apriRicerca }, icona('cerca'))),
        h('div', { class: 'segmenti', role: 'tablist', 'aria-label': 'Quale posta' }, segmento('importanti', 'Importanti'), segmento('tutte', 'Tutte')),
      ];
    }
    svuota(S.dom.colElenco, testa, S.dom.filtroAttivo, S.dom.infoSelezione, S.dom.incerte, S.dom.elenco, S.dom.altre);
  }

  /** "Prova": un foglio che spiega cosa vuol dire, aperto apposta (§4, §9). */
  function spiegaProva() {
    apriFoglio('Modalità prova', (corpo, chiudi) => {
      svuota(corpo,
        h('p', { class: 'spiega' }, 'Sol decide i gruppi ma non sposta niente nelle caselle vere: mostra solo cosa farebbe. Quando ti fidi delle sue scelte, spegnila: da lì Sol sposterà davvero le mail nuove nelle cartelle o etichette. Sol non cancella mai nessuna mail.'),
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante tenue', onclick: () => { chiudi(); apriImpostazione('prova'); } }, 'Apri l\'impostazione')));
    });
  }

  async function scegliFiltro(filtro) {
    if (!(await puoLasciare(() => scegliFiltro(filtro)))) return;
    if (S.vista !== 'posta') { await vaiVista('posta', { filtro }); return; }
    const cambia = !L.stessoFiltro(filtro, S.filtro) || S.ricerca;
    S.filtro = filtro;
    S.ricerca = false;
    S.cerca = '';
    if (cambia) {
      S.selezione = new Set();
      S.attiva = null;
      S.ancora = null;
      S.aperta = null;
      chiudiEditorPosta();
      S.elenco.mail = [];
      history.replaceState(null, '', location.pathname);
    }
    disegnaTestaPosta();
    renderElenco();
    renderLettura();
    await caricaElenco();
  }

  async function apriGruppo(id) {
    if (S.filtro.tipo !== 'gruppo') S.filtroPrima = S.filtro;
    await scegliFiltro({ tipo: 'gruppo', id });
  }

  function chiudiGruppo() {
    return scegliFiltro(S.filtroPrima && S.filtroPrima.tipo !== 'gruppo' ? S.filtroPrima : { tipo: 'importanti' });
  }

  async function apriRicerca() {
    if (S.vista !== 'posta') await vaiVista('posta');
    if (S.ricerca) { const c = document.getElementById('cerca'); if (c) { c.focus(); c.select(); } return; }
    if (!(await puoLasciare())) return;
    S.filtroPrima = S.filtro;
    S.ricerca = true;
    S.cerca = '';
    S.filtro = { tipo: 'tutte' };
    disegnaTestaPosta();
    await caricaElenco();
  }

  function chiudiRicerca() {
    const prima = S.filtroPrima || { tipo: 'importanti' };
    S.ricerca = false;
    S.cerca = '';
    S.filtro = { tipo: '__' };
    return scegliFiltro(prima);
  }

  async function cercaTesto(valore) {
    if (valore === S.cerca) return;
    S.cerca = valore;
    await caricaElenco();
  }

  /** In una raccolta (o in un gruppo): tutte le mail caricate segnate lette. */
  async function segnaTutteLette() {
    const ids = S.elenco.mail.filter((m) => !m.letta).map((m) => m.id);
    if (!ids.length) { avviso('Sono già tutte lette.', 'info'); return; }
    if (S.occupato) return;
    S.occupato = 'lette';
    let fallite = 0;
    let motivo = null;
    try {
      for (const id of ids) {
        const r = await Api.post('/server/api/mail/' + id + '/letta', { letta: true });
        if (r.ok) aggiornaInElenco({ id, letta: true }); else { fallite++; motivo = motivo || r.errore; }
      }
    } finally {
      S.occupato = null;
    }
    const parziale = L.esitoMultiplo(ids.length - fallite, fallite, 'segnate lette', motivo);
    avviso(parziale || (ids.length === 1 ? 'Segnata letta.' : ids.length + ' mail segnate lette.'), parziale ? 'errore' : 'ok');
    renderElenco();
    aggiornaContatori();
  }

  /**
   * L'elenco dal server. Normale (filtro o ricerca nuovi): si sostituisce. Silenzioso (il giro del minuto,
   * F5): si UNISCE a quello che c'è, senza perdere le mail già caricate né la selezione (§ revisione 8).
   */
  async function caricaElenco(opzioni) {
    const o = opzioni || {};
    if (S.vista !== 'posta') return;
    if (!vistaPiatta()) { await caricaImportanti(o); return; }
    const giro = ++S.elenco.giro;
    if (!o.silenzioso) {
      S.elenco.caricando = true;
      renderElenco();
    }
    const r = await Api.get('/server/api/mail' + L.queryPosta(S.filtro, S.cerca, null, 50));
    if (giro !== S.elenco.giro || S.vista !== 'posta') return;
    S.elenco.caricando = false;
    if (r.ok && r.dati && Array.isArray(r.dati.mail)) {
      const selezionePrima = chiaveSelezione();
      if (o.silenzioso) {
        const tenere = new Set([...S.selezione, S.aperta && S.aperta.id].filter((x) => x != null));
        const primaIds = new Set(S.elenco.mail.map((m) => m.id));
        const u = L.unisciElenco(S.elenco.mail, r.dati.mail, !!r.dati.altre, S.elenco.altre, tenere);
        S.elenco.mail = u.mail;
        S.elenco.altre = u.altre;
        if (primaIds.size) S.elenco.nuove = new Set(u.mail.filter((m) => !primaIds.has(m.id)).map((m) => m.id));
      } else {
        S.elenco.mail = r.dati.mail;
        S.elenco.altre = !!r.dati.altre;
        const presenti = new Set(S.elenco.mail.map((m) => m.id));
        S.selezione = new Set([...S.selezione].filter((id) => presenti.has(id) || (S.aperta && id === S.aperta.id)));
      }
      S.elenco.errore = null;
      renderElenco();
      if (chiaveSelezione() !== selezionePrima && !campoDiTesto(document.activeElement)) renderLettura();
    } else {
      if (!o.silenzioso) S.elenco.errore = r.errore;
      renderElenco();
    }
    renderStato();
  }

  /** "Importanti": le mail recenti di ogni gruppo che non è una raccolta, più quante sono da sistemare. */
  async function caricaImportanti(o) {
    const giro = ++S.elenco.giro;
    const gruppi = S.gruppi.filter((g) => !g.raccolta);
    if (!o.silenzioso) {
      S.elenco.caricando = true;
      renderElenco();
    }
    const [incerte, ...liste] = await Promise.all([
      Api.get('/server/api/mail?smistamento=da_controllare&sospetta=false&limite=200'),
      ...gruppi.map((g) => Api.get('/server/api/mail?gruppo_id=' + g.id + '&limite=' + PER_GRUPPO)),
    ]);
    if (giro !== S.elenco.giro || S.vista !== 'posta' || vistaPiatta()) return;
    S.elenco.caricando = false;
    const buone = liste.map((r) => r.ok && r.dati && Array.isArray(r.dati.mail));
    const nuove = [];
    const altre = new Map();
    liste.forEach((r, i) => {
      if (!buone[i]) return;
      nuove.push(...r.dati.mail);
      altre.set(gruppi[i].id, !!r.dati.altre);
    });
    if (incerte.ok && incerte.dati && Array.isArray(incerte.dati.mail)) S.elenco.incerte = incerte.dati.mail.filter((m) => !m.sospetta).length;
    const selezionePrima = chiaveSelezione();
    if (o.silenzioso && S.elenco.mail.length) {
      const fresche = new Set(nuove.map((m) => m.id));
      const tenere = new Set([...S.selezione, S.aperta && S.aperta.id].filter((x) => x != null));
      const prima = new Set(S.elenco.mail.map((m) => m.id));
      const tenute = S.elenco.mail.filter((m) => !fresche.has(m.id) && tenere.has(m.id));
      S.elenco.mail = nuove.concat(tenute);
      S.elenco.nuove = new Set(nuove.filter((m) => !prima.has(m.id)).map((m) => m.id));
    } else if (buone.some(Boolean) || !gruppi.length) {
      S.elenco.mail = nuove;
      const presenti = new Set(nuove.map((m) => m.id));
      S.selezione = new Set([...S.selezione].filter((id) => presenti.has(id) || (S.aperta && id === S.aperta.id)));
    }
    S.elenco.altrePerGruppo = altre;
    S.elenco.altre = false;
    const fallita = liste.find((r, i) => !buone[i]);
    S.elenco.errore = fallita && !nuove.length ? fallita.errore : null;
    renderElenco();
    if (chiaveSelezione() !== selezionePrima && !campoDiTesto(document.activeElement)) renderLettura();
    renderStato();
  }

  function chiaveSelezione() {
    return [...S.selezione].sort((a, b) => a - b).join(',');
  }

  async function caricaAltre() {
    const ultima = S.elenco.mail[S.elenco.mail.length - 1];
    if (!ultima) return;
    const r = await Api.get('/server/api/mail' + L.queryPosta(S.filtro, S.cerca, ultima.id, 50));
    if (r.ok && r.dati && Array.isArray(r.dati.mail)) {
      const gia = new Set(S.elenco.mail.map((m) => m.id));
      S.elenco.mail = S.elenco.mail.concat(r.dati.mail.filter((m) => !gia.has(m.id)));
      S.elenco.altre = !!r.dati.altre;
      renderElenco();
    } else {
      avviso(r.errore, 'errore');
    }
  }

  /** L'ordine in cui si scorre con le frecce: quello che si vede (le sezioni una dopo l'altra in "Importanti"). */
  function ordine() {
    if (!vistaPiatta()) return L.sezioniImportanti(S.elenco.mail, S.gruppi).flatMap((s) => s.mail.map((m) => m.id));
    return S.elenco.mail.map((m) => m.id);
  }

  const PAROLE_SEGNO = { truffa: 'possibile truffa', pronta: 'risposta pronta' };

  function rigaMail(m, nuove, conPallino) {
    const sel = S.selezione.has(m.id);
    const g = gruppoDi(m);
    const segno = L.segnoRiga(m);
    const urgente = m.urgenza === 'alta';
    const li = h('li', {
      class: 'mail ' + classeColore(g) + (m.letta ? '' : ' non-letta') + (urgente ? ' urgente' : '') + (sel ? ' selezionata' : '')
        + (S.attiva === m.id ? ' attiva' : '') + (nuove.has(m.id) ? ' entrata' : ''),
      role: 'option',
      'aria-selected': sel ? 'true' : 'false',
      // Il lettore schermo legge prima i segnali importanti, poi chi scrive e l'oggetto.
      'aria-label': [segno ? PAROLE_SEGNO[segno] : null, urgente ? 'urgente' : null, m.letta ? null : 'non letta', nomeMittente(m), m.oggetto || '(senza oggetto)', m.risposta_data ? 'risposta data' : null].filter(Boolean).join(', '),
      draggable: 'true',
      onclick: (e) => suClicMail(e, m.id),
      ondragstart: (e) => iniziaTrascina(e, m),
      ondragend: () => fineTrascina(),
    },
      h('span', { class: 'avatar', 'aria-hidden': 'true' }, inizialiDi(m)),
      h('span', { class: 'contenuto-mail' },
        h('span', { class: 'riga-1' },
          h('span', { class: 'mittente' }, nomeMittente(m)),
          h('span', { class: 'ora' }, conPallino && g ? h('span', { class: 'pallino-gruppo', title: g.nome }) : null, L.oraBreve(m.ricevuta_il))),
        h('span', { class: 'oggetto' }, m.oggetto || '(senza oggetto)'),
        h('span', { class: 'riga-3' },
          h('span', { class: 'riassunto-breve' }, m.riassunto || m.anteprima || ''),
          segno === 'truffa' ? h('span', { class: 'segno truffa' }, icona('triangolo'), 'Possibile truffa') : null,
          segno === 'pronta' ? h('span', { class: 'segno pronta' }, icona('matita'), 'Risposta pronta') : null)));
    li.dataset.id = String(m.id);
    return li;
  }

  function schedaIncerte(n) {
    return h('button', { type: 'button', class: 'scheda-incerte', onclick: () => vaiVista('smista') },
      h('span', { class: 'cerchio', 'aria-hidden': 'true' }, icona('strati')),
      h('span', { class: 'testi' },
        h('span', { class: 'titolo-voce' }, n === 1 ? '1 mail da sistemare' : n + ' mail da sistemare'),
        h('span', { class: 'sotto-voce' }, 'Sol non è sicuro del gruppo')),
      h('span', { class: 'pulsante pieno', 'aria-hidden': 'true' }, 'Smista'));
  }

  function sezionePosta(s, nuove) {
    const g = s.gruppo;
    const c = S.conteggi && S.conteggi.gruppi && S.conteggi.gruppi[String(g.id)];
    const daLeggere = c && typeof c.da_leggere === 'number' ? c.da_leggere : null;
    return h('section', { class: 'sezione-posta ' + classeColore(g), 'data-gruppo': String(g.id) },
      h('div', { class: 'testa-sezione' },
        h('span', { class: 'pallino', 'aria-hidden': 'true' }),
        h('button', { type: 'button', class: 'nome-sezione', onclick: () => apriGruppo(g.id) }, g.nome),
        daLeggere ? h('span', { class: 'conto', title: 'Da leggere' }, String(daLeggere)) : null),
      h('ul', { class: 'gruppo-righe', role: 'group', 'aria-label': g.nome },
        s.mail.map((m) => rigaMail(m, nuove, false)),
        S.elenco.altrePerGruppo.get(g.id) ? h('li', null, h('button', { type: 'button', class: 'vedi-tutte', onclick: () => apriGruppo(g.id) }, 'Vedi tutte')) : null));
  }

  function sezioneRaccolte(raccolte) {
    const cont = (g) => S.conteggi && S.conteggi.gruppi && S.conteggi.gruppi[String(g.id)];
    return h('section', { class: 'sezione-posta raccolte' },
      h('div', { class: 'testa-sezione' }, h('span', { class: 'nome-sezione' }, 'Raccolte')),
      h('ul', { class: 'gruppo-righe' }, raccolte.map((g) => {
        const t = L.rigaRaccolta(g, cont(g));
        return h('li', null, h('button', { type: 'button', class: 'riga-raccolta ' + classeColore(g) + (t.daLeggere ? ' da-leggere' : ''), 'data-gruppo': String(g.id), 'aria-label': g.nome + ', ' + t.sotto + (t.daLeggere ? ', ' + t.daLeggere + ' da leggere' : ''), onclick: () => apriGruppo(g.id) },
          h('span', { class: 'icona-raccolta', 'aria-hidden': 'true' }, icona('raccolta')),
          h('span', { class: 'testi' }, h('span', { class: 'titolo-voce' }, t.titolo), h('span', { class: 'sotto-voce' }, t.sotto)),
          icona('destra')));
      })));
  }

  function renderElenco() {
    if (!S.dom.elenco || !S.dom.elenco.isConnected) return;
    const piatta = vistaPiatta();
    const conChip = !['importanti', 'tutte', 'gruppo'].includes(S.filtro.tipo) && !S.ricerca;
    svuota(S.dom.filtroAttivo, conChip ? [h('strong', null, titoloFiltro()), ' · ',
      h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => scegliFiltro({ tipo: 'tutte' }) }, 'togli filtro')] : null);
    const n = S.selezione.size;
    S.dom.infoSelezione.textContent = n > 1 ? n + ' mail selezionate' : '';
    svuota(S.dom.incerte, !piatta && S.elenco.incerte > 0 ? schedaIncerte(S.elenco.incerte) : null);
    svuota(S.dom.altre, piatta && S.elenco.altre ? h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: caricaAltre }, 'Mostra le mail più vecchie') : null);
    if (S.elenco.caricando && S.elenco.mail.length === 0) {
      svuota(S.dom.elenco, h('p', { class: 'vuoto' }, 'Carico le mail…'));
      return;
    }
    if (S.elenco.errore && S.elenco.mail.length === 0) {
      svuota(S.dom.elenco, messaggioInLinea(S.elenco.errore));
      return;
    }
    const nuove = S.elenco.nuove || new Set();
    S.elenco.nuove = null;
    if (piatta) {
      svuota(S.dom.elenco, S.elenco.mail.length
        ? h('ul', { class: 'gruppo-righe', role: 'group', 'aria-label': titoloFiltro() }, S.elenco.mail.map((m) => rigaMail(m, nuove, S.filtro.tipo !== 'gruppo')))
        : h('p', { class: 'vuoto' }, S.cerca ? 'Nessuna mail trovata.' : 'Nessuna mail qui.'));
    } else {
      const sezioni = L.sezioniImportanti(S.elenco.mail, S.gruppi);
      const raccolte = S.gruppi.filter((g) => g.raccolta);
      svuota(S.dom.elenco,
        sezioni.map((s) => sezionePosta(s, nuove)),
        !sezioni.length && !raccolte.length ? h('p', { class: 'vuoto' }, 'Nessuna mail qui.') : null,
        raccolte.length ? sezioneRaccolte(raccolte) : null);
    }
    const attiva = S.dom.elenco.querySelector('li.mail.attiva');
    if (attiva && attiva.scrollIntoView) attiva.scrollIntoView({ block: 'nearest' });
  }

  /** Cambia la selezione; se cambia quello che mostra la colonna di destra, prima si salva la risposta aperta. */
  async function cambiaSelezione(r) {
    const singola = r.selezione.size === 1 ? [...r.selezione][0] : null;
    const apertaOra = S.aperta ? S.aperta.id : null;
    const cambiaLettura = singola === null ? (apertaOra !== null || S.selezione.size !== r.selezione.size) : singola !== apertaOra;
    if (cambiaLettura && !(await puoLasciare(() => cambiaSelezione(r)))) return false;
    S.selezione = r.selezione;
    S.ancora = r.ancora;
    S.attiva = r.attiva;
    if (singola !== null) {
      renderElenco();
      await apriMail(singola, true);
    } else {
      if (cambiaLettura) {
        chiudiEditorPosta();
        S.aperta = null;
      }
      renderElenco();
      renderLettura();
    }
    return true;
  }

  async function suClicMail(e, id) {
    if (S.velo) return;
    if (S.dom.elenco && document.activeElement !== S.dom.elenco) S.dom.elenco.focus({ preventScroll: true });
    await cambiaSelezione(L.selezioneDopoClic(S.selezione, ordine(), id, { ctrl: e.ctrlKey || e.metaKey, shift: e.shiftKey }, S.ancora));
  }

  async function muovi(passo, estendi) {
    await cambiaSelezione(L.selezioneDopoTasto(S.selezione, ordine(), S.attiva, passo, estendi, S.ancora));
  }

  function mailInElenco(id) {
    return S.elenco.mail.find((m) => m.id === id) || null;
  }

  function mailNota(id) {
    return mailInElenco(id) || (S.aperta && S.aperta.id === id ? S.aperta.mail : null);
  }

  function aggiornaInElenco(mail) {
    if (!mail || mail.id == null) return;
    const i = S.elenco.mail.findIndex((m) => m.id === mail.id);
    if (i >= 0) S.elenco.mail[i] = Object.assign({}, S.elenco.mail[i], mail);
    if (S.aperta && S.aperta.id === mail.id && S.aperta.mail) S.aperta.mail = Object.assign({}, S.aperta.mail, mail);
  }

  /** La mail resta in questo elenco dopo che è cambiata? (es. spostata in una raccolta o in un altro gruppo: no). */
  function restaNelFiltro(m) {
    if (!m) return false;
    if (S.ricerca) return true;
    switch (S.filtro.tipo) {
      case 'importanti': { const g = gruppoDi(m); return !!g && !g.raccolta; }
      case 'gruppo': return m.gruppo_id === Number(S.filtro.id);
      case 'da-controllare': return m.smistamento === 'da_controllare';
      case 'da-rispondere': return !!m.da_rispondere && !m.risposta_data;
      case 'urgenti': return m.urgenza === 'alta';
      case 'truffe': return !!m.sospetta;
      default: return true;
    }
  }

  // --- Trascinare: i gruppi compaiono nella colonna di destra e quello sotto si illumina ----------

  function iniziaTrascina(e, m) {
    const ids = L.idsTrascinati(m.id, S.selezione);
    e.dataTransfer.setData('text/x-sol-mail', JSON.stringify(ids));
    e.dataTransfer.effectAllowed = 'move';
    const etichetta = h('div', { class: 'trascinata' }, ids.length === 1 ? (m.oggetto || '(senza oggetto)') : ids.length + ' mail');
    document.body.append(etichetta);
    if (e.dataTransfer.setDragImage) e.dataTransfer.setDragImage(etichetta, 10, 10);
    setTimeout(() => etichetta.remove(), 0);
    mostraZonaTrascina(ids);
  }

  function mostraZonaTrascina(ids) {
    if (!S.dom.lettura || !S.dom.lettura.isConnected) return;
    fineTrascina();
    const bersaglio = (g, i) => {
      const el = h('div', { class: 'bersaglio ' + classeColore(g), 'data-gruppo': String(g.id) },
        h('span', { class: 'pallino', 'aria-hidden': 'true' }), h('span', null, g.nome), h('span', { class: 'spazio' }), i < 9 ? h('kbd', null, String(i + 1)) : null);
      el.addEventListener('dragover', (e) => {
        if (!e.dataTransfer || !Array.from(e.dataTransfer.types || []).includes('text/x-sol-mail')) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        el.classList.add('sopra');
      });
      el.addEventListener('dragleave', () => el.classList.remove('sopra'));
      el.addEventListener('drop', (e) => {
        e.preventDefault();
        el.classList.remove('sopra');
        let scelte = [];
        try { scelte = JSON.parse(e.dataTransfer.getData('text/x-sol-mail')); } catch (err) { scelte = []; }
        fineTrascina();
        if (Array.isArray(scelte) && scelte.length) sposta(scelte.map(Number), g.id);
      });
      return el;
    };
    S.dom.zonaTrascina = h('div', { class: 'zona-trascina', role: 'group', 'aria-label': 'Sposta in' },
      h('p', { class: 'titolo-zona' }, ids.length === 1 ? 'Sposta in' : 'Sposta ' + ids.length + ' mail in'),
      h('div', { class: 'bersagli' }, S.gruppi.map(bersaglio)));
    S.dom.lettura.append(S.dom.zonaTrascina);
  }

  function fineTrascina() {
    if (S.dom.zonaTrascina) {
      S.dom.zonaTrascina.remove();
      S.dom.zonaTrascina = null;
    }
  }

  // --- Smistamento a mano: un'azione alla volta ------------------------------------------------

  /** Cosa c'era a schermo all'inizio di un'azione: se alla fine è cambiato, la pagina non salta da nessuna parte. */
  function fotografia() {
    return [S.vista, JSON.stringify(S.filtro), S.aperta ? S.aperta.id : '', S.attiva, chiaveSelezione()].join('|');
  }

  async function preparaSmistamento(ids, domanda) {
    if (S.occupato) { avviso('Un momento: Sol sta ancora finendo l\'azione di prima.', 'info'); return false; }
    if (inviaInCorso()) { avviso('Aspetta: una risposta sta aspettando la conferma d\'invio.', 'info'); return false; }
    if (!ids.length) return false;
    // L'azione può far passare a un'altra mail: la risposta della mail aperta si salva prima.
    if (S.aperta && ids.includes(S.aperta.id) && !(await puoLasciare())) return false;
    if (ids.length > MAX_SENZA_CONFERMA && domanda && !(await chiediConferma(domanda))) return false;
    return true;
  }

  /** Sposta in un gruppo. Una mail sola: Sol impara il mittente e si può annullare per qualche secondo (§17). */
  async function sposta(ids, gruppoId, o) {
    const opz = o || {};
    const gruppo = S.gruppi.find((g) => g.id === gruppoId);
    if (!gruppo) return;
    const domanda = { titolo: 'Spostare ' + ids.length + ' mail in ' + gruppo.nome + '?', testo: 'Con più mail insieme Sol non impara i mittenti.', si: 'Sposta ' + ids.length + ' mail' };
    if (!(await preparaSmistamento(ids, domanda))) return;
    // Le regole si imparano solo spostando UNA mail: con più mail insieme si sbaglia troppo facilmente (§ revisione 9).
    const impara = opz.impara != null ? opz.impara : (S.ricorda && ids.length === 1);
    const prima = ids.length === 1 && mailNota(ids[0]) ? mailNota(ids[0]).gruppo_id : null;
    const foto = fotografia();
    const riuscite = [];
    let motivo = null;
    S.occupato = 'sposta';
    try {
      for (const id of ids) {
        const r = await Api.post('/server/api/mail/' + id + '/sposta', { gruppo_id: gruppoId, impara });
        if (r.ok) {
          riuscite.push(id);
          aggiornaInElenco(r.dati && r.dati.id ? r.dati : { id, gruppo_id: gruppoId, gruppo: gruppo.nome, smistamento: 'corretta' });
        } else {
          motivo = motivo || r.errore;
        }
      }
    } finally {
      S.occupato = null;
    }
    const parziale = L.esitoMultiplo(riuscite.length, ids.length - riuscite.length, 'spostate in ' + gruppo.nome, motivo);
    if (parziale) avviso(parziale, 'errore');
    else if (opz.annullata) avviso('Rimessa in ' + gruppo.nome + '.', 'ok');
    else if (riuscite.length === 1 && prima != null && prima !== gruppoId) {
      avviso('Spostata in ' + gruppo.nome, 'ok', { etichetta: 'Annulla', fai: () => sposta(riuscite, prima, { impara, annullata: true }) });
    } else avviso((riuscite.length === 1 ? 'Spostata' : riuscite.length + ' mail spostate') + ' in ' + gruppo.nome + '.', 'ok');
    await dopoSmistamento(riuscite, foto);
  }

  /** "Va bene così": accetta il gruppo proposto (per le da controllare), segna letta e passa alla successiva. */
  async function vaBene(ids) {
    const domanda = { titolo: 'Accettare il gruppo proposto per ' + ids.length + ' mail?', testo: 'Le mail diventano anche "lette".', si: 'Sì, per ' + ids.length + ' mail' };
    if (!(await preparaSmistamento(ids, domanda))) return;
    const foto = fotografia();
    const riuscite = [];
    let motivo = null;
    S.occupato = 'va-bene';
    try {
      for (const id of ids) {
        const m = mailNota(id);
        let ok = true;
        if (m && m.smistamento === 'da_controllare') {
          const r = await Api.post('/server/api/mail/' + id + '/conferma');
          if (r.ok) aggiornaInElenco(r.dati && r.dati.id ? r.dati : { id, smistamento: 'smistata' });
          else { ok = false; motivo = motivo || r.errore; }
        }
        if (ok && m && !m.letta) {
          const r = await Api.post('/server/api/mail/' + id + '/letta', { letta: true });
          if (r.ok) aggiornaInElenco({ id, letta: true });
          else { ok = false; motivo = motivo || r.errore; }
        }
        if (ok) riuscite.push(id);
      }
    } finally {
      S.occupato = null;
    }
    const parziale = L.esitoMultiplo(riuscite.length, ids.length - riuscite.length, 'a posto', motivo);
    if (parziale) avviso(parziale, 'errore');
    await dopoSmistamento(riuscite, foto);
  }

  /**
   * Dopo uno spostamento o un "va bene così": le mail che escono dal filtro escono dall'elenco, e si passa alla
   * successiva SOLO se a schermo è tutto come all'inizio e nessun campo di testo ha il fuoco (§ revisione 4).
   */
  async function dopoSmistamento(ids, foto) {
    if (S.vista !== 'posta') return;
    // La riga che esce dalla vista scivola via (§ movimenti); intanto nessun'altra azione parte.
    const escono = ids.filter((id) => !restaNelFiltro(mailInElenco(id)));
    if (escono.length && conMovimenti() && S.dom.elenco && !S.occupato) {
      let mosse = 0;
      for (const id of escono) {
        const li = S.dom.elenco.querySelector('li.mail[data-id="' + id + '"]');
        if (li) { li.classList.add('uscita'); mosse++; }
      }
      if (mosse) {
        S.occupato = 'movimento';
        try { await new Promise((r) => setTimeout(r, 180)); } finally { S.occupato = null; }
      }
    }
    const ordinePrima = ordine();
    S.elenco.mail = S.elenco.mail.filter((m) => !ids.includes(m.id) || restaNelFiltro(m));
    const restanti = new Set(ordine());
    aggiornaContatori();
    const apertaId = S.aperta ? S.aperta.id : null;
    if (!ids.length || foto !== fotografia() || campoDiTesto(document.activeElement)) {
      S.selezione = new Set([...S.selezione].filter((id) => restanti.has(id) || id === apertaId));
      renderElenco();
      if (apertaId !== null && ids.includes(apertaId) && !campoDiTesto(document.activeElement)) aggiornaTestata();
      return;
    }
    const prossima = L.prossimaDopo(ordinePrima, ids, [...restanti]);
    if (prossima != null) {
      await cambiaSelezione({ selezione: new Set([prossima]), ancora: prossima, attiva: prossima });
    } else {
      await cambiaSelezione({ selezione: new Set(), ancora: null, attiva: null });
    }
  }

  async function cambiaLetta(ids) {
    const conosciute = ids.map(mailNota).filter(Boolean);
    const nuovoValore = conosciute.some((m) => !m.letta);
    const domanda = { titolo: (nuovoValore ? 'Segnare come lette ' : 'Segnare da leggere ') + ids.length + ' mail?', testo: 'Cambia anche nelle caselle vere.', si: 'Sì, ' + ids.length + ' mail' };
    if (!(await preparaSmistamento(ids, domanda))) return;
    const riuscite = [];
    let motivo = null;
    S.occupato = 'letta';
    try {
      for (const id of ids) {
        const r = await Api.post('/server/api/mail/' + id + '/letta', { letta: nuovoValore });
        if (r.ok) { riuscite.push(id); aggiornaInElenco({ id, letta: nuovoValore }); } else motivo = motivo || r.errore;
      }
    } finally {
      S.occupato = null;
    }
    const parziale = L.esitoMultiplo(riuscite.length, ids.length - riuscite.length, nuovoValore ? 'segnate lette' : 'segnate da leggere', motivo);
    if (parziale) avviso(parziale, 'errore');
    renderElenco();
    if (S.aperta && ids.includes(S.aperta.id)) aggiornaTestata();
    aggiornaContatori();
  }

  async function cambiaRispondere(ids) {
    const conosciute = ids.map(mailNota).filter(Boolean);
    const nuovoValore = !conosciute.every((m) => m.da_rispondere);
    const domanda = { titolo: (nuovoValore ? 'Segnare da rispondere ' : 'Togliere "da rispondere" a ') + ids.length + ' mail?', si: 'Sì, ' + ids.length + ' mail' };
    if (!(await preparaSmistamento(ids, domanda))) return;
    const riuscite = [];
    let motivo = null;
    S.occupato = 'rispondere';
    try {
      for (const id of ids) {
        const r = await Api.post('/server/api/mail/' + id + '/rispondere', { da_rispondere: nuovoValore });
        if (r.ok) { riuscite.push(id); aggiornaInElenco(r.dati && r.dati.id ? r.dati : { id, da_rispondere: nuovoValore }); } else motivo = motivo || r.errore;
      }
    } finally {
      S.occupato = null;
    }
    const parziale = L.esitoMultiplo(riuscite.length, ids.length - riuscite.length, nuovoValore ? 'segnate da rispondere' : 'tolte da "da rispondere"', motivo);
    if (parziale) avviso(parziale, 'errore');
    else avviso(nuovoValore ? (riuscite.length === 1 ? 'Segnata da rispondere.' : riuscite.length + ' mail segnate da rispondere.') : (riuscite.length === 1 ? 'Tolta da "da rispondere".' : riuscite.length + ' mail tolte da "da rispondere".'), 'ok');
    renderElenco();
    if (S.aperta && ids.includes(S.aperta.id)) {
      aggiornaTestata();
      if (S.editor && !S.editor.distrutto) S.editor.render();
    }
    aggiornaContatori();
  }

  /**
   * "Non è una truffa" (false) o "Segnala come possibile truffa" (true): solo con il pulsante, mai con una
   * scorciatoia. "Va bene così" (Invio) accetta il gruppo e NON tocca il sospetto (§16.3).
   */
  async function cambiaSospetta(id, valore) {
    if (S.occupato) { avviso('Un momento: Sol sta ancora finendo l\'azione di prima.', 'info'); return; }
    S.occupato = 'sospetta';
    let r;
    try {
      r = await Api.post('/server/api/mail/' + id + '/sospetta', { sospetta: valore });
    } finally {
      S.occupato = null;
    }
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    aggiornaInElenco(r.dati && r.dati.id ? r.dati : { id, sospetta: valore });
    avviso(valore ? 'Segnalata come possibile truffa: Sol non le prepara risposte da sola.' : 'Tolta l\'etichetta "Possibile truffa".', 'ok');
    renderElenco();
    if (S.aperta && S.aperta.id === id) {
      aggiornaTestata();
      if (S.editor && !S.editor.distrutto) S.editor.render();
      // Tornata normale, se va risposta Sol le prepara la bozza: si rilegge la mail per vederla arrivare.
      if (!valore) {
        const d = await Api.get('/server/api/mail/' + id);
        if (d.ok && d.dati && S.aperta && S.aperta.id === id && S.aperta.mail) {
          S.aperta.mail = Object.assign({}, S.aperta.mail, d.dati);
          aggiornaTestata();
          if (S.editor && !S.editor.distrutto && !S.editor.id && d.dati.bozza && d.dati.bozza.id) S.editor.carica(d.dati.bozza.id);
          else if (S.editor && !S.editor.distrutto) S.editor.render();
        }
      }
    }
    aggiornaContatori();
  }

  // --- Scorciatoie ---------------------------------------------------------------------------

  /** Un campo di testo dove si stava scrivendo è appena sparito (ridisegno): i tasti che seguono non diventano comandi. */
  function campoAppenaSparito() {
    const c = S.tastoInCampo;
    return !!c && !c.el.isConnected && Date.now() - c.t < 3000;
  }

  function suTasto(e) {
    if (S.abbinamento) return;
    if (S.tutorial) { tastoTutorial(e); return; }
    const b = e.target;
    const inCampo = campoDiTesto(b);
    if (inCampo) S.tastoInCampo = { el: b, t: Date.now() };
    if (S.velo) {
      if (e.key === 'Escape') { e.preventDefault(); S.velo.chiudi(); }
      return;
    }
    if (e.key === 'Escape' && ((S.dom.menuAzioni && !S.dom.menuAzioni.hidden) || (S.dom.spostaInElenco && !S.dom.spostaInElenco.hidden))) {
      e.preventDefault();
      chiudiFinestrelleMail();
      return;
    }
    if (e.key === 'F5') {
      e.preventDefault();
      if (!e.repeat) ricaricaTutto();
      return;
    }
    if (S.vista === 'smista') { tastoSmista(e, inCampo); return; }
    if (S.vista !== 'posta') {
      if (inCampo && e.key === 'Escape') b.blur();
      return;
    }
    if (!inCampo && campoAppenaSparito()) return;
    // Nei pulsanti e nei collegamenti Invio e spazio fanno il loro lavoro.
    if (!inCampo && b && (b.tagName === 'BUTTON' || b.tagName === 'A') && (e.key === 'Enter' || e.key === ' ')) return;
    const suElenco = !!(S.dom.elenco && (b === S.dom.elenco || S.dom.elenco.contains(b)));
    const azione = L.azioneTasto(
      { key: e.key, ctrl: e.ctrlKey, shift: e.shiftKey, alt: e.altKey, meta: e.metaKey, inCampo, ripetuto: e.repeat, suElenco },
      { haSelezione: S.selezione.size > 0, numeroGruppi: S.gruppi.length });
    if (!azione) return;
    e.preventDefault();
    // Una cosa alla volta, e niente mentre una risposta aspetta la conferma d'invio (§ revisione 3 e 10).
    if (azione.smista && (S.occupato || inviaInCorso())) return;
    const ids = [...S.selezione];
    switch (azione.tipo) {
      case 'esci-campo':
        if (b && b.id === 'cerca') chiudiRicerca();
        else { b.blur(); if (S.dom.elenco) S.dom.elenco.focus(); }
        break;
      case 'scorri': muovi(azione.passo, false); break;
      case 'estendi': muovi(azione.passo, true); break;
      case 'sposta': sposta(ids, S.gruppi[azione.indice].id); break;
      case 'va-bene': vaBene(ids); break;
      case 'bozza': apriOPreparaBozza(); break;
      case 'letta': cambiaLetta(ids); break;
      case 'rispondere': cambiaRispondere(ids); break;
      case 'cerca': apriRicerca(); break;
      case 'seleziona-tutto':
        cambiaSelezione({ selezione: new Set(ordine()), ancora: S.ancora, attiva: null });
        break;
      case 'annulla':
        if (S.selezione.size > 1) {
          const una = S.attiva != null && S.selezione.has(S.attiva) ? S.attiva : null;
          cambiaSelezione({ selezione: una != null ? new Set([una]) : new Set(), ancora: una, attiva: una });
        } else if (S.ricerca) chiudiRicerca();
        break;
      case 'aiuto': mostraAiuto(); break;
      default: break;
    }
  }

  /** Le scorciatoie, in una finestrella aperta apposta col tasto "?". */
  function mostraAiuto() {
    const righe = [
      ['↑ ↓  (o J K)', 'mail sopra o sotto'],
      ['Maiusc + frecce, Ctrl + clic', 'più mail insieme'],
      ['1 … 9', 'nel gruppo con quel numero'],
      ['Invio', 'va bene così'],
      ['R', 'la risposta (o falla preparare)'],
      ['L', 'letta o da leggere'],
      ['D', 'da rispondere sì o no'],
      ['/  oppure Ctrl + F', 'cerca'],
      ['F5', 'ricarica'],
      ['Smista: → o Invio, ←, 1 … 9', 'va bene, cambia, quel gruppo'],
    ];
    const chiudi = () => { velo.remove(); S.velo = null; };
    const velo = h('div', { class: 'velo', onclick: (e) => { if (e.target === velo) chiudi(); } },
      h('div', { class: 'finestrella', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Scorciatoie' },
        h('h2', null, 'Scorciatoie'),
        h('table', { class: 'tabella-tasti' }, h('tbody', null, righe.map(([t, d]) => h('tr', null, h('td', null, h('kbd', null, t)), h('td', null, d))))),
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante pieno', onclick: chiudi }, 'Ho capito'))));
    S.velo = { chiudi };
    document.body.append(velo);
    velo.querySelector('button').focus();
  }

  // --- Posta: la mail aperta, come il prototipo Mail (§5) -------------------------------------------

  async function apriMail(id, giaControllato) {
    if (S.aperta && S.aperta.id === id && (S.aperta.mail || S.aperta.caricando)) {
      S.attiva = id;
      renderElenco();
      return;
    }
    if (!giaControllato && !(await puoLasciare(() => apriMail(id)))) return;
    chiudiEditorPosta();
    S.attiva = id;
    S.aperta = { id, mail: null, caricando: true, errore: null };
    renderElenco();
    renderLettura();
    const r = await Api.get('/server/api/mail/' + id);
    if (!S.aperta || S.aperta.id !== id) return;
    if (!r.ok) {
      S.aperta = { id, mail: null, caricando: false, errore: r.errore };
      renderLettura();
      return;
    }
    S.aperta = { id, mail: r.dati, caricando: false, errore: null, memoria: r.memoria };
    if (r.dati && r.dati.mittente) S.nomiMail[id] = nomeMittente(r.dati);
    renderLettura();
    if (!r.dati.letta && !r.memoria) {
      // Aprire una mail la segna letta, anche nella casella vera: è un gesto dell'utente (§5).
      const l = await Api.post('/server/api/mail/' + id + '/letta', { letta: true });
      if (l.ok) {
        aggiornaInElenco({ id, letta: true });
        renderElenco();
        aggiornaContatori();
      }
    }
  }

  function renderLettura() {
    if (!S.dom.lettura || !S.dom.lettura.isConnected) return;
    S.dom.barraRisposta = null;
    if (S.selezione.size > 1) {
      chiudiEditorPosta();
      renderSelezioneMultipla();
      return;
    }
    if (!S.aperta) {
      chiudiEditorPosta();
      svuota(S.dom.lettura, h('div', { class: 'mail-scorre' }, h('div', { class: 'vuoto-lettura' }, sole(), 'Nessuna mail aperta')));
      return;
    }
    if (S.aperta.caricando) {
      svuota(S.dom.lettura, h('div', { class: 'mail-scorre' }, h('p', { class: 'vuoto-lettura' }, 'Apro la mail…')));
      return;
    }
    if (S.aperta.errore) {
      svuota(S.dom.lettura, h('div', { class: 'mail-scorre' }, h('div', { class: 'vuoto-lettura' }, messaggioInLinea(S.aperta.errore))));
      return;
    }
    const m = S.aperta.mail;
    S.dom.testata = h('article', { class: 'mail-aperta' });
    S.dom.bozza = h('section', { class: 'sezione-bozza', 'aria-label': 'Risposta' });
    // La barra fissa in basso alla colonna: "Invia risposta" (o "Prepara la risposta").
    S.dom.barraRisposta = h('div', { class: 'barra-risposta azioni-bozza', role: 'toolbar', 'aria-label': 'Risposta' });
    svuota(S.dom.lettura, h('div', { class: 'mail-scorre' }, S.dom.testata, S.dom.bozza), S.dom.barraRisposta);
    aggiornaTestata();
    if (S.editor && S.editor.mailId === m.id && !S.editor.distrutto) {
      S.editor.spostaIn(S.dom.bozza, S.dom.barraRisposta);
    } else {
      chiudiEditorPosta();
      S.editor = creaEditor({
        contenitore: S.dom.bozza,
        barra: S.dom.barraRisposta,
        prefisso: 'bozza',
        mailId: m.id,
        mail: () => (S.aperta && S.aperta.mail && S.aperta.mail.id === m.id ? S.aperta.mail : null),
        suCambio: suCambioBozzaPosta,
      });
      if (m.bozza && m.bozza.id) S.editor.carica(m.bozza.id);
      else S.editor.render();
    }
  }

  /** Chiude le finestrelle della mail aperta (menu ⋮, "Sposta in") se si clicca fuori o si preme Esc. */
  function chiudiFinestrelleMail() {
    for (const el of [S.dom.menuAzioni, S.dom.spostaInElenco]) {
      if (el && !el.hidden) {
        el.hidden = true;
        const pulsante = el.previousElementSibling;
        if (pulsante) pulsante.setAttribute('aria-expanded', 'false');
      }
    }
  }

  function apriFinestrella(el, pulsante) {
    const aperta = !el.hidden;
    chiudiFinestrelleMail();
    if (aperta) return;
    el.hidden = false;
    pulsante.setAttribute('aria-expanded', 'true');
    const primo = el.querySelector('button');
    if (primo) primo.focus();
  }

  /** Nei menu della mail aperta le frecce scorrono le voci (e non l'elenco delle mail), Esc chiude. */
  function tastiMenu(e, elenco, pulsante) {
    const voci = Array.from(elenco.querySelectorAll('button'));
    const i = voci.indexOf(document.activeElement);
    let prossima = null;
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      chiudiFinestrelleMail();
      pulsante.focus();
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'j') prossima = voci[(i + 1) % voci.length];
    else if (e.key === 'ArrowUp' || e.key === 'k') prossima = voci[(i - 1 + voci.length) % voci.length];
    else if (e.key === 'Home') prossima = voci[0];
    else if (e.key === 'End') prossima = voci[voci.length - 1];
    else if (e.key === 'Tab') { chiudiFinestrelleMail(); return; }
    if (!prossima) return;
    e.preventDefault();
    e.stopPropagation();
    prossima.focus();
  }

  /** La mail aperta com'è adesso (i menu si riempiono quando si aprono, non quando si disegna l'intestazione). */
  function mailAdesso(m) {
    return S.aperta && S.aperta.mail && S.aperta.mail.id === m.id ? S.aperta.mail : m;
  }

  /** "Sposta in": i gruppi coi loro colori, nome e descrizione corta (dalla pillola del gruppo o da "Cambia"). */
  function costruisciSpostaIn(m) {
    const g = gruppoDi(m);
    let chip = null;
    const elenco = h('div', { class: 'sposta-in-elenco', role: 'menu', 'aria-label': 'Sposta in', hidden: true,
      onkeydown: (e) => tastiMenu(e, elenco, chip) });
    const riempi = () => {
      const mm = mailAdesso(m);
      svuota(elenco,
        h('p', { class: 'titolo-menu', 'aria-hidden': 'true' }, 'Sposta in'),
        S.gruppi.map((x, i) => h('button', {
          type: 'button', role: 'menuitem',
          class: 'voce-menu voce-gruppo ' + classeColore(x) + (x.id === mm.gruppo_id ? ' attuale' : ''),
          'data-gruppo': String(x.id),
          'aria-current': x.id === mm.gruppo_id ? 'true' : null,
          onclick: () => { chiudiFinestrelleMail(); if (x.id !== mm.gruppo_id) sposta([mm.id], x.id); },
        },
          h('span', { class: 'avatar piccolo', 'aria-hidden': 'true' }, i < 9 ? String(i + 1) : ''),
          h('span', { class: 'testi' },
            h('span', { class: 'nome-gruppo' }, x.nome + (x.id === mm.gruppo_id ? ' (adesso è qui)' : '')),
            x.descrizione ? h('span', { class: 'descrizione-gruppo' }, x.descrizione) : null))));
    };
    const apri = () => { riempi(); apriFinestrella(elenco, chip); };
    chip = h('button', {
      type: 'button', class: 'chip-gruppo ' + classeColore(g), 'aria-haspopup': 'menu', 'aria-expanded': 'false', title: 'Sposta in un altro gruppo',
      onclick: (e) => { e.stopPropagation(); apri(); },
    }, (m.gruppo || (g && g.nome) || 'Senza gruppo'), icona('giu'));
    S.dom.spostaInElenco = elenco;
    return { ancora: h('span', { class: 'ancora-menu' }, chip, elenco), chip, elenco, apri };
  }

  /** Il menu "altre azioni" (§5): le azioni rare, compreso "Rispondi" sulle mail che non chiedono risposta. */
  function costruisciMenuAzioni(m) {
    let pulsante = null;
    const voce = (testo, azione, classe) => h('button', { type: 'button', role: 'menuitem', class: 'voce-menu' + (classe ? ' ' + classe : ''),
      onclick: () => { chiudiFinestrelleMail(); azione(); } }, testo);
    const elenco = h('div', { class: 'menu-azioni', role: 'menu', 'aria-label': 'Altre azioni', hidden: true,
      onkeydown: (e) => tastiMenu(e, elenco, pulsante) });
    const riempi = () => {
      const mm = mailAdesso(m);
      const ed = S.editor && !S.editor.distrutto && S.editor.mailId === mm.id ? S.editor : null;
      const conTesto = ed && ed.dati && ['pronta', 'errore'].includes(ed.dati.stato) && (ed.dati.testo || '').trim();
      const altri = L.altriDestinatari(mm, mm.casella);
      const puoRispondere = ed && !ed.dati && !ed.id && !ed.preparando && !ed.proponiRisposta();
      svuota(elenco,
        voce(mm.letta ? 'Segna come da leggere' : 'Segna come letta', () => cambiaLetta([mm.id])),
        voce(mm.da_rispondere ? 'Non serve rispondere' : 'Va risposta', () => cambiaRispondere([mm.id])),
        puoRispondere ? voce('Rispondi', () => ed.prepara(null)) : null,
        conTesto && altri.length ? voce('Rispondi a tutti', () => ed.aggiungiCc(altri)) : null,
        mm.sospetta ? null : voce('Segnala come possibile truffa', () => cambiaSospetta(mm.id, true), 'discreto'),
        conTesto ? voce('Scarta la risposta', async () => {
          const si = await chiediConferma({ titolo: 'Scartare la risposta?', testo: 'Sol la toglie; se serve, potrai fargliene preparare un\'altra.', si: 'Sì, scarta', pericolo: true });
          if (si) ed.scarta();
        }, 'pericolo') : null);
    };
    pulsante = h('button', {
      type: 'button', class: 'rotondo pulsante-menu', 'aria-label': 'Altre azioni', title: 'Altre azioni', 'aria-haspopup': 'menu', 'aria-expanded': 'false',
      onclick: (e) => { e.stopPropagation(); riempi(); apriFinestrella(elenco, pulsante); },
    }, puntini());
    S.dom.menuAzioni = elenco;
    return { ancora: h('span', { class: 'ancora-menu' }, pulsante, elenco), pulsante, elenco };
  }

  /** La parte alta della mail aperta: il riquadro della risposta e la barra in basso non si toccano. */
  function aggiornaTestata() {
    if (!S.dom.testata || !S.dom.testata.isConnected || !S.aperta || !S.aperta.mail) return;
    const m = S.aperta.mail;
    const g = gruppoDi(m);
    const spostaIn = costruisciSpostaIn(m);
    const menu = costruisciMenuAzioni(m);
    const allegati = (m.allegati || []).length
      ? h('ul', { class: 'allegati', 'aria-label': 'Allegati' }, m.allegati.map((a) => h('li', null, icona('allegato'), a.nome + (a.byte ? ' · ' + dimensione(a.byte) : ''))))
      : null;
    // Come nell'elenco: se è urgente l'ora è rossa (e il lettore dello schermo lo dice), nessuna pillola in più.
    const indirizzo = m.mittente && m.mittente.nome && m.mittente.indirizzo ? m.mittente.indirizzo : null;
    const ora = L.oraIntestazione(m.ricevuta_il);
    const dove = [indirizzo, indirizzo && ora ? ' · ' : null,
      ora ? h('span', m.urgenza === 'alta' ? { class: 'ora-urgente' } : null, ora) : null,
      m.urgenza === 'alta' ? h('span', { class: 'solo-lettore' }, ', urgente') : null];
    svuota(S.dom.testata,
      h('div', { class: 'riga-strumenti' }, menu.ancora),
      S.aperta.memoria ? messaggioInLinea('Copia salvata il ' + L.dataLunga(S.aperta.memoria) + ': il server ora non risponde.', 'info') : null,
      h('header', { class: 'testa-mail ' + classeColore(g) },
        h('span', { class: 'avatar grande', 'aria-hidden': 'true' }, inizialiDi(m)),
        h('div', { class: 'chi' }, h('span', { class: 'nome' }, nomeMittente(m)), h('span', { class: 'dove' }, dove)),
        spostaIn.ancora),
      // Una PEC ha due mittenti: chi ha scritto e la casella certificata che l'ha spedita (§15.8).
      m.pec ? h('p', { class: 'dettagli-pec' }, 'PEC spedita da ' + (m.pec.mittente_busta || '') + (m.pec.gestore ? ' (gestore ' + m.pec.gestore + ')' : '')) : null,
      h('div', { class: 'segni-mail' },
        m.smistamento === 'in_attesa' ? h('span', { class: 'pillola-info' }, 'Non ancora smistata') : null,
        m.risposta_data ? h('span', { class: 'pillola-info' }, icona('spunta'), 'Risposta data') : null),
      // Mail incerta: una riga sotto l'intestazione; le altre non hanno niente sul gruppo.
      m.smistamento === 'da_controllare' ? h('div', { class: 'riga-controllo' },
        h('span', { class: 'domanda' }, 'È nel gruppo giusto?'),
        h('button', { type: 'button', class: 'pulsante tenue piccolo', title: 'Va bene così (Invio)', onclick: () => vaBene([m.id]) }, 'Sì'),
        h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: (e) => { e.stopPropagation(); spostaIn.apri(); } }, 'Cambia'),
        m.sospetta ? h('p', { class: 'nota-truffa' }, 'Resta segnalata come possibile truffa finché non premi "Non è una truffa".') : null) : null,
      h('h1', { class: 'oggetto-grande' }, m.oggetto || '(senza oggetto)'),
      // §16.3: l'avviso truffa, sopra "In breve", col pulsante esplicito per toglierlo.
      m.sospetta ? h('div', { class: 'riquadro truffa', role: 'alert' },
        h('p', { class: 'testo-truffa' }, AVVISO_TRUFFA),
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante truffa-bianco piccolo', onclick: () => cambiaSospetta(m.id, false) }, 'Non è una truffa'))) : null,
      m.riassunto ? h('p', { class: 'in-breve' }, h('strong', null, 'In breve:'), ' ' + m.riassunto) : null,
      h('div', { class: 'corpo-mail' }, m.corpo || m.anteprima || ''),
      allegati);
  }

  function suCambioBozzaPosta(ed, evento) {
    if (!ed.mailId) return;
    const d = ed.dati;
    const cambio = { id: ed.mailId, bozza: d ? { id: d.id, stato: d.stato } : null };
    if (evento === 'inviata') cambio.risposta_data = true;
    aggiornaInElenco(cambio);
    renderElenco();
    if (evento === 'inviata') {
      // Partita la risposta, l'intestazione dice "Risposta data" (il riquadro della risposta non si tocca).
      if (S.aperta && S.aperta.id === ed.mailId && !(S.dom.testata && S.dom.testata.contains(document.activeElement))) aggiornaTestata();
      aggiornaContatori();
    }
  }

  function renderSelezioneMultipla() {
    const ids = [...S.selezione];
    svuota(S.dom.lettura, h('div', { class: 'mail-scorre' }, h('div', { class: 'selezione-multipla' },
      h('p', { class: 'grande' }, ids.length + ' mail selezionate'),
      h('div', { class: 'griglia-gruppi' }, S.gruppi.map((g, i) => h('button', { type: 'button', class: 'pulsante-gruppo ' + classeColore(g), onclick: () => sposta(ids, g.id) },
        h('span', { class: 'pallino', 'aria-hidden': 'true' }), h('span', { class: 'nome' }, g.nome), i < 9 ? h('kbd', null, String(i + 1)) : null))),
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante pieno', onclick: () => vaBene(ids) }, 'Vanno bene così'),
        h('button', { type: 'button', class: 'pulsante bianco', onclick: () => cambiaLetta(ids) }, 'Lette o da leggere')))));
  }

  function dimensione(byte) {
    if (byte < 1024) return byte + ' byte';
    if (byte < 1024 * 1024) return Math.round(byte / 1024) + ' kB';
    return (byte / 1024 / 1024).toFixed(1).replace('.', ',') + ' MB';
  }

  /** R: apre la risposta, oppure la fa preparare se non c'è. Mai mentre si carica, mai sopra una bozza esistente (§ revisione 6). */
  function apriOPreparaBozza() {
    if (!S.aperta || !S.aperta.mail || S.aperta.caricando) return;
    const ed = S.editor;
    if (!ed || ed.distrutto || ed.caricando || ed.preparando || ed.invio) return;
    const d = ed.dati;
    if (!d) {
      if (!ed.id) ed.prepara(null);
      return;
    }
    if (d.stato === 'pronta' || (d.stato === 'errore' && (d.testo || '').trim())) ed.focus();
    else if (d.stato === 'errore') ed.rifai(null);
    else if (d.stato === 'scartata') ed.prepara(null);
  }

  // --- Il riquadro di una risposta: ognuno lavora SOLO sulla sua bozza -------------------------------

  function campiDa(d) {
    return { a: (d.a || []).join(', '), cc: (d.cc || []).join(', '), oggetto: d.oggetto || '', testo: d.testo || '' };
  }

  function ugualiCampi(x, y) {
    return x.a === y.a && x.cc === y.cc && x.oggetto === y.oggetto && x.testo === y.testo;
  }

  /**
   * Un riquadro di risposta. Tutto quello che fa (salvare, inviare, rifare, scartare) lo fa sulla SUA bozza:
   * le chiusure portano `ed`, niente stato condiviso tra riquadri, id HTML unici (prefisso).
   * o: { contenitore, barra, prefisso, mailId, mail(), inviata, prepara(istruzioni), suCambio(ed, evento) }
   * Con `barra` è la risposta di Sol della mail aperta (§5): pillole di tono e "Invia risposta" in basso.
   * Senza, è un sollecito ("Aspetti risposta") con i pulsanti dentro il riquadro.
   */
  function creaEditor(o) {
    const ed = {
      id: null, mailId: o.mailId || null, inviata: o.inviata || null,
      dati: null, campi: null, sporca: false,
      catena: Promise.resolve(true), timerSalva: null, salvando: false,
      caricando: false, preparando: false, invio: false,
      messaggio: null, tipoMessaggio: 'errore', campoErrato: null, messaggioDaSalvataggio: false,
      rifaiAperto: false, chiediDaZero: false, nonSalvata: null,
      tono: null, tonoInCorso: null, testoVisto: null, campiAperti: false,
      distrutto: false, poll: null, contenitore: o.contenitore, barra: o.barra || null, prefisso: o.prefisso, el: {},
    };
    const mailDi = () => (o.mail ? o.mail() : null);
    const suCambio = (evento) => { if (o.suCambio) o.suCambio(ed, evento); };
    const idCampo = (nome) => ed.prefisso + '-' + nome;

    ed.distruggi = () => {
      ed.distrutto = true;
      clearTimeout(ed.timerSalva);
      clearTimeout(ed.poll);
    };

    ed.spostaIn = (contenitore, barra) => {
      ed.contenitore = contenitore;
      if (barra !== undefined) ed.barra = barra;
      render();
    };

    ed.focus = () => { if (ed.el.testo) ed.el.testo.focus(); };

    ed.carica = async (bozzaId) => {
      ed.id = bozzaId;
      ed.caricando = true;
      ed.dati = null;
      render();
      const r = await Api.get('/server/api/bozze/' + bozzaId);
      if (ed.distrutto) return;
      ed.caricando = false;
      if (!r.ok) {
        ed.messaggio = r.errore;
        render();
        return;
      }
      imposta(r.dati);
    };

    function imposta(d) {
      clearTimeout(ed.poll);
      clearTimeout(ed.timerSalva);
      ed.id = d.id;
      ed.dati = d;
      ed.campi = campiDa(d);
      ed.sporca = false;
      ed.caricando = false;
      ed.preparando = false;
      ed.messaggio = d.stato === 'errore' && (d.testo || '').trim() ? (d.errore || 'L\'ultimo invio non è riuscito.') : null;
      ed.tipoMessaggio = 'errore';
      ed.campoErrato = null;
      ed.rifaiAperto = false;
      ed.chiediDaZero = false;
      ed.nonSalvata = null;
      if (d.stato === 'in_preparazione') {
        ed.poll = setTimeout(ricontrolla, T.bozza);
      } else {
        // Finita la riscrittura col tono scelto: la pillola resta premuta finché non si cambia il testo.
        if (ed.tonoInCorso) ed.tono = ed.tonoInCorso;
        ed.tonoInCorso = null;
        ed.testoVisto = null;
      }
      suCambio(d.stato === 'inviata' ? 'inviata' : 'cambiata');
      render();
    }
    ed.imposta = imposta;

    async function ricontrolla() {
      if (ed.distrutto || !ed.id) return;
      const r = await Api.get('/server/api/bozze/' + ed.id);
      if (ed.distrutto) return;
      if (r.ok && r.dati && r.dati.stato !== 'in_preparazione') {
        imposta(r.dati);
        return;
      }
      ed.poll = setTimeout(ricontrolla, T.bozza);
    }

    /** Fa preparare la bozza che non c'è ancora (o una nuova dopo averla scartata). */
    ed.prepara = async (istruzioni) => {
      if (ed.preparando || ed.distrutto) return;
      ed.preparando = true;
      ed.messaggio = null;
      render();
      const r = o.prepara
        ? await o.prepara(istruzioni)
        : await Api.post('/server/api/mail/' + ed.mailId + '/bozza', { istruzioni: istruzioni || null });
      if (ed.distrutto) return;
      ed.preparando = false;
      if (!r.ok) {
        ed.messaggio = r.errore;
        render();
        return;
      }
      imposta(r.dati);
    };

    /**
     * Rifà la bozza (§15.5, §15.13): prima la si salva (così il server parte dalle modifiche di Manuel), e una
     * bozza ritoccata a mano si rifà da zero solo dopo un sì esplicito. Con le pillole di tono c'è sempre
     * un'istruzione, quindi parte subito; il testo di prima resta visibile, sbiadito, finché arriva il nuovo.
     */
    ed.rifai = async (istruzioni, daZero, tono) => {
      if (ed.distrutto || ed.invio || ed.preparando) return;
      if (!ed.id) return ed.prepara(istruzioni);
      if (!(await ed.salvaSeServe())) {
        mostraErrore('La risposta non si è salvata, quindi Sol non la rifà (le tue modifiche resterebbero fuori). Riprova.');
        return;
      }
      if (!istruzioni && !daZero && ed.dati && ed.dati.modificata) {
        ed.chiediDaZero = true;
        render();
        return;
      }
      ed.testoVisto = ed.campi ? ed.campi.testo : null;
      ed.tonoInCorso = tono || null;
      ed.tono = null;
      ed.preparando = true;
      ed.chiediDaZero = false;
      render();
      const corpo = daZero ? { istruzioni: null, da_zero: true } : { istruzioni: istruzioni || null };
      let r = await Api.post('/server/api/bozze/' + ed.id + '/rifai', corpo);
      if (!r.ok && (r.stato === 404 || r.stato === 405) && ed.mailId && !(ed.dati && ed.dati.modificata)) {
        // Server di prima della v1.1: si rifà dalla mail (una bozza mai toccata a mano non perde niente).
        r = await Api.post('/server/api/mail/' + ed.mailId + '/bozza', { istruzioni: istruzioni || null });
      }
      if (ed.distrutto) return;
      ed.preparando = false;
      if (!r.ok) {
        ed.testoVisto = null;
        ed.tonoInCorso = null;
        render();
        mostraErrore(r.errore);
        return;
      }
      imposta(r.dati);
    };

    /** "Rispondi a tutti": gli altri destinatari della mail entrano in Cc (e la risposta si salva da sola). */
    ed.aggiungiCc = (indirizzi) => {
      if (!ed.campi || ed.invio) return;
      const gia = new Set(L.elencoIndirizzi(ed.campi.a + ',' + ed.campi.cc).map((x) => x.toLowerCase()));
      const nuovi = indirizzi.filter((x) => !gia.has(x.toLowerCase()));
      if (!nuovi.length) { avviso('Ci sono già tutti.', 'info'); return; }
      ed.campi.cc = [ed.campi.cc, nuovi.join(', ')].filter((x) => x.trim()).join(', ');
      ed.campiAperti = true;
      ed.sporca = true;
      render();
      salva();
    };

    function modifica(campo, el) {
      if (ed.invio || !ed.campi) {
        // Durante la conferma d'invio i campi sono bloccati: niente cambia rispetto a quello che si sta confermando.
        el.value = ed.campi ? ed.campi[campo] : '';
        return;
      }
      ed.campi[campo] = el.value;
      ed.sporca = true;
      if (campo === 'testo' && ed.tono) {
        ed.tono = null;
        for (const p of ed.el.pillole || []) p.setAttribute('aria-pressed', 'false');
      }
      if (ed.campoErrato === campo) pulisciErrore();
      statoSalvataggio('Modifiche da salvare');
      clearTimeout(ed.timerSalva);
      ed.timerSalva = setTimeout(() => { if (!ed.invio) salva(); }, T.salva);
    }

    /** I salvataggi partono uno alla volta, in fila: mai due PUT insieme sulla stessa bozza. */
    function salva() {
      clearTimeout(ed.timerSalva);
      const prossimo = ed.catena.then(salvaOra, salvaOra);
      ed.catena = prossimo;
      return prossimo;
    }

    async function salvaOra() {
      if (ed.distrutto || !ed.sporca || !ed.id || !ed.campi) return !ed.sporca;
      const campi = Object.assign({}, ed.campi);
      const strani = L.indirizziStrani(campi.a).map((x) => ['A', 'a', x]).concat(L.indirizziStrani(campi.cc).map((x) => ['Cc', 'cc', x]));
      if (strani.length) {
        const [nome, campo, ind] = strani[0];
        mostraErrore('L\'indirizzo «' + ind + '» in «' + nome + '» non è scritto bene: correggilo e la risposta si salva da sola.', campo, 'errore', true);
        statoSalvataggio('Non salvata', true);
        return false;
      }
      ed.salvando = true;
      statoSalvataggio('Salvo…');
      const r = await Api.put('/server/api/bozze/' + ed.id, {
        a: L.elencoIndirizzi(campi.a),
        cc: L.elencoIndirizzi(campi.cc),
        oggetto: campi.oggetto,
        testo: campi.testo,
      });
      ed.salvando = false;
      if (ed.distrutto) return r.ok;
      if (!r.ok) {
        mostraErrore(r.errore, r.dati && r.dati.campo, 'errore', true);
        statoSalvataggio('Non salvata', true);
        return false;
      }
      if (r.dati && r.dati.id) ed.dati = r.dati;
      ed.sporca = !ugualiCampi(campi, ed.campi);
      if (ed.messaggio && ed.messaggioDaSalvataggio) pulisciErrore();
      if (ed.nonSalvata) nascondiNonSalvata();
      statoSalvataggio(ed.sporca ? 'Modifiche da salvare' : 'Modifiche salvate');
      return true;
    }

    /** Salva finché non resta niente da salvare. false = non si è salvato (il motivo è nel riquadro). */
    ed.salvaSeServe = async () => {
      clearTimeout(ed.timerSalva);
      for (let i = 0; i < 3; i++) {
        await ed.catena;
        if (!ed.sporca || ed.distrutto) return true;
        if (!(await salva())) return false;
      }
      return !ed.sporca;
    };

    ed.invia = async () => {
      if (ed.invio || !ed.id || ed.distrutto || !ed.campi) return;
      const c = ed.campi;
      const a = L.elencoIndirizzi(c.a);
      if (!a.length) return mostraErrore('Manca il destinatario: scrivi almeno un indirizzo nel campo A.', 'a');
      const strani = L.indirizziStrani(c.a + ',' + c.cc);
      if (strani.length) return mostraErrore('Questi indirizzi non sembrano giusti: ' + strani.join(', ') + '. Correggili prima di inviare.', L.indirizziStrani(c.a).length ? 'a' : 'cc');
      if (!c.testo.trim()) return mostraErrore('Il testo è vuoto: scrivi la risposta prima di inviarla.', 'testo');
      // Da qui alla fine della conferma: campi in sola lettura e nessun salvataggio automatico (§15.11).
      ed.invio = true;
      clearTimeout(ed.timerSalva);
      pulisciErrore();
      aggiornaBlocco();
      if (!(await ed.salvaSeServe())) {
        ed.invio = false;
        aggiornaBlocco();
        if (!ed.messaggio) mostraErrore('Le modifiche non si sono salvate, quindi la mail non è partita. Riprova.');
        return;
      }
      const versione = ed.dati && ed.dati.versione != null ? ed.dati.versione : null;
      const r = await Api.post('/locale/invia', { bozza_id: ed.id, versione });
      ed.invio = false;
      if (ed.distrutto) return;
      const d = r.dati || {};
      const errore = r.errore || d.errore || null;
      if (r.ok && d.annullato) {
        aggiornaBlocco();
        avviso('Invio annullato: la mail non è partita.', 'info');
        return;
      }
      if (r.ok && d.inviata) {
        avviso(d.gia_inviata
          ? 'Era già partita: Sol l\'ha trovata in Posta inviata e non l\'ha mandata una seconda volta.'
          : (ed.dati && ed.dati.tipo === 'sollecito' ? 'Sollecito inviato a ' : 'Mail inviata a ') + a.join(', ') + '.', 'ok');
        imposta(d.bozza && d.bozza.id ? d.bozza : Object.assign({}, ed.dati, { stato: 'inviata', inviata_il: new Date().toISOString() }));
        return;
      }
      if (d.bozza && d.bozza.id && (d.codice === 'conflitto' || d.bozza.stato === 'inviata')) {
        // La bozza è cambiata (o è già partita): si mostra quella vera, da ricontrollare.
        imposta(d.bozza);
        mostraErrore(errore || 'La risposta è cambiata: ricontrollala prima di inviarla.', null, d.bozza.stato === 'inviata' ? 'info' : 'avvertenza');
        return;
      }
      if (d.bozza && d.bozza.id) ed.dati = d.bozza;
      aggiornaBlocco();
      mostraErrore(errore || 'L\'invio non è riuscito.');
    };

    ed.scarta = async () => {
      if (!ed.id || ed.invio) return;
      clearTimeout(ed.timerSalva);
      const r = await Api.post('/server/api/bozze/' + ed.id + '/scarta');
      if (ed.distrutto) return;
      if (!r.ok) { mostraErrore(r.errore); return; }
      avviso('Risposta scartata.', 'ok');
      imposta(r.dati && r.dati.id ? r.dati : Object.assign({}, ed.dati, { stato: 'scartata' }));
    };

    /** Lasciando la bozza con il salvataggio fallito: si resta qui, e si può scegliere di scartare le modifiche. */
    ed.mostraNonSalvata = (dopo) => {
      ed.nonSalvata = { dopo: dopo || null };
      avviso('La risposta non è salvata: Sol resta qui per non perderla. Correggila, oppure scarta le modifiche.', 'errore');
      if (!ed.el.banner || !ed.el.banner.isConnected) render();
      else disegnaBanner();
      if (ed.el.banner && ed.el.banner.scrollIntoView) ed.el.banner.scrollIntoView({ block: 'nearest' });
    };

    function nascondiNonSalvata() {
      ed.nonSalvata = null;
      disegnaBanner();
    }

    function scartaModifiche() {
      const dopo = ed.nonSalvata && ed.nonSalvata.dopo;
      clearTimeout(ed.timerSalva);
      if (ed.dati) ed.campi = campiDa(ed.dati);
      ed.sporca = false;
      ed.nonSalvata = null;
      ed.messaggio = null;
      ed.campoErrato = null;
      render();
      if (dopo) dopo();
    }

    function statoSalvataggio(testo, errore) {
      if (!ed.el.salvataggio) return;
      ed.el.salvataggio.textContent = testo || '';
      ed.el.salvataggio.className = 'salvataggio' + (errore ? ' errore' : '');
    }

    function mostraErrore(testo, campo, tipo, daSalvataggio) {
      ed.messaggio = testo || null;
      ed.tipoMessaggio = tipo || 'errore';
      ed.messaggioDaSalvataggio = !!daSalvataggio;
      ed.campoErrato = ['a', 'cc', 'oggetto', 'testo'].includes(campo) ? campo : null;
      if (ed.campoErrato && ed.campoErrato !== 'testo' && !ed.campiAperti && ed.barra) {
        // Il campo sbagliato è nascosto (A, Cc, Oggetto si aprono dal destinatario): lo si apre.
        ed.campiAperti = true;
        render();
        return;
      }
      if (ed.el.messaggio && ed.el.messaggio.isConnected) {
        svuota(ed.el.messaggio, messaggioInLinea(ed.messaggio, ed.tipoMessaggio));
        for (const k of ['a', 'cc', 'oggetto', 'testo']) if (ed.el[k]) ed.el[k].classList.toggle('campo-errato', k === ed.campoErrato);
      } else {
        render();
      }
    }

    function pulisciErrore() {
      ed.messaggio = null;
      ed.campoErrato = null;
      if (ed.el.messaggio) svuota(ed.el.messaggio);
      for (const k of ['a', 'cc', 'oggetto', 'testo']) if (ed.el[k]) ed.el[k].classList.remove('campo-errato');
    }

    /** La scritta del pulsante d'invio: "Invia risposta" nella barra in basso, "Invia…" nei solleciti. */
    function etichettaInvio() {
      if (ed.invio) return 'In attesa della conferma…';
      const errore = ed.dati && ed.dati.stato === 'errore';
      if (ed.barra) return errore ? 'Riprova a inviare' : 'Invia risposta';
      return errore ? 'Riprova a inviare…' : 'Invia…';
    }

    /** Blocca o sblocca i campi senza ridisegnare (il fuoco e il punto in cui si scriveva restano). */
    function aggiornaBlocco() {
      const b = ed.invio;
      for (const k of ['a', 'cc', 'oggetto', 'testo']) if (ed.el[k]) ed.el[k].readOnly = b;
      for (const k of ['invia', 'rifai']) if (ed.el[k]) ed.el[k].disabled = b;
      for (const p of ed.el.pillole || []) p.disabled = b;
      if (ed.el.etichettaInvia) ed.el.etichettaInvia.textContent = etichettaInvio();
    }

    function disegnaBanner() {
      if (!ed.el.banner) return;
      if (ed.chiediDaZero) {
        svuota(ed.el.banner, h('div', { class: 'banner-bozza' },
          messaggioInLinea('Hai modificato questa risposta a mano. Rifarla da zero cancella le tue modifiche.', 'avvertenza'),
          h('div', { class: 'azioni' },
            h('button', { type: 'button', class: 'pulsante pericolo piccolo', onclick: () => ed.rifai(null, true) }, 'Sì, rifalla da zero'),
            h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: () => { ed.chiediDaZero = false; disegnaBanner(); } }, 'No, tienila'))));
        return;
      }
      if (ed.nonSalvata) {
        svuota(ed.el.banner, h('div', { class: 'banner-bozza' },
          messaggioInLinea('La risposta non è salvata' + (ed.messaggio ? ': ' + ed.messaggio : '.') + ' Per non perderla Sol resta qui.', 'avvertenza'),
          h('div', { class: 'azioni' },
            h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: () => { nascondiNonSalvata(); ed.focus(); } }, 'Resta qui'),
            h('button', { type: 'button', class: 'pulsante pericolo piccolo', onclick: scartaModifiche }, 'Scarta le modifiche'))));
        return;
      }
      svuota(ed.el.banner);
    }

    function campoIstruzioni(segnaposto, conAnnulla, vai, etichetta) {
      const testo = h('textarea', { id: idCampo('istruzioni'), class: 'istruzioni', rows: '2', placeholder: segnaposto, 'aria-label': 'Come rifarla',
        onkeydown: (e) => { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); invia(); } } });
      const invia = () => {
        const t = testo.value.trim();
        if (!t) { testo.focus(); return; }
        vai(t);
      };
      return h('div', { class: 'blocco-istruzioni' },
        h('div', { class: 'riga-istruzioni' }, testo, microfono(testo)),
        h('div', { class: 'azioni' },
          h('button', { type: 'button', class: 'pulsante pieno piccolo', onclick: invia }, etichetta || 'Prepara con queste istruzioni'),
          conAnnulla ? h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => { ed.rifaiAperto = false; render(); } }, 'Annulla') : null));
    }

    function render() {
      if (ed.distrutto || !ed.contenitore) return;
      ed.el = {};
      ed.el.banner = h('div', { class: 'spazio-banner' });
      svuota(ed.contenitore, ed.el.banner, ed.barra ? contenutoNellaMail() : contenutoSollecito());
      if (ed.barra) svuota(ed.barra, azioniBarra());
      disegnaBanner();
    }
    ed.render = render;

    function titolo() {
      return ed.inviata || (ed.dati && ed.dati.tipo === 'sollecito') ? 'Sollecito' : 'Risposta';
    }

    /** Nella mail aperta: si propone di preparare la risposta solo se va risposta e non è una possibile truffa. */
    function proponiRisposta() {
      const m = mailDi();
      return !!m && !!m.da_rispondere && !m.risposta_data && !m.sospetta;
    }
    ed.proponiRisposta = proponiRisposta;

    /** La barra in basso (§5): "Invia risposta", "Prepara la risposta", oppure Sol che scrive. */
    function azioniBarra() {
      if (ed.caricando) return [];
      const scrive = (frase) => [h('div', { class: 'attesa', role: 'status' }, h('span', { class: 'indicatore', 'aria-hidden': 'true' }), frase)];
      if (ed.preparando) return scrive(ed.testoVisto != null ? 'Sol riscrive la risposta…' : 'Sol sta scrivendo la risposta…');
      if (!ed.dati) {
        if (ed.id) return [];
        // Mail che non chiede risposta, o possibile truffa senza risposta chiesta: nessuna barra ("Rispondi" è nel menu).
        return proponiRisposta() ? [h('button', { type: 'button', class: 'pulsante pieno grande', title: 'Prepara la risposta (R)', onclick: () => ed.prepara(null) }, 'Prepara la risposta')] : [];
      }
      const d = ed.dati;
      if (d.stato === 'in_preparazione') return scrive(ed.testoVisto != null ? 'Sol riscrive la risposta…' : 'Sol sta scrivendo la risposta…');
      if (d.stato === 'inviata' || d.stato === 'scartata') return [];
      if (d.stato === 'errore' && !(d.testo || '').trim()) return [h('button', { type: 'button', class: 'pulsante pieno grande', onclick: () => ed.rifai(null) }, 'Riprova a preparare')];
      ed.el.etichettaInvia = h('span', null, etichettaInvio());
      ed.el.invia = h('button', { type: 'button', class: 'pulsante pieno primario grande', disabled: ed.invio ? true : null, onclick: () => ed.invia() }, ed.el.etichettaInvia, icona('freccia'));
      return [ed.el.invia];
    }

    function campo(chiave, etichetta) {
      const c = ed.campi;
      const input = h('input', {
        id: idCampo(chiave), type: 'text', value: c[chiave], class: ed.campoErrato === chiave ? 'campo-errato' : null,
        spellcheck: chiave === 'oggetto' ? 'true' : 'false', readonly: ed.invio ? true : null,
        oninput: (e) => modifica(chiave, e.target),
      });
      ed.el[chiave] = input;
      return h('div', { class: 'campo-bozza' }, h('label', { for: input.id }, etichetta), input);
    }

    function avvisiRisposta() {
      const m = mailDi();
      const giaRisposto = !ed.inviata && m && m.risposta_data;
      const sospetta = !ed.inviata && m && m.sospetta;
      return [
        sospetta ? messaggioInLinea('Stai rispondendo a una mail segnalata come possibile truffa: non mandare soldi, coordinate bancarie, documenti o password senza aver prima telefonato al mittente a un numero che conosci già.', 'truffa') : null,
        giaRisposto ? messaggioInLinea('Hai già risposto a questa mail (forse da un altro programma). Prima di inviare questa, controlla che serva davvero.', 'avvertenza') : null,
      ];
    }

    function testaRisposta(destra) {
      return h('div', { class: 'testa-risposta' }, h('span', { class: 'titolo-risposta' }, sole(), 'Risposta di Sol'), destra || null);
    }

    /** La risposta di Sol nella mail aperta: scheda in rilievo, testo modificabile, pillole di tono. */
    function contenutoNellaMail() {
      if (ed.caricando) return h('div', { class: 'risposta-sol' }, testaRisposta(), scheletro());
      const d = ed.dati;
      const riscrive = (ed.preparando || (d && d.stato === 'in_preparazione')) && ed.testoVisto != null;
      if (riscrive) {
        // Mentre Sol riscrive, il testo di prima resta lì, sbiadito, con una barra sottile che scorre una volta.
        const t = h('textarea', { id: idCampo('testo'), class: 'testo-bozza', readonly: true, 'aria-label': 'Testo della risposta', value: ed.testoVisto });
        ed.el.testo = t;
        return h('div', { class: 'risposta-sol in-riscrittura', 'aria-busy': 'true' },
          h('span', { class: 'barra-scorre', 'aria-hidden': 'true' }), testaRisposta(), t, pilloleTono(true));
      }
      if (ed.preparando || (d && d.stato === 'in_preparazione')) {
        return h('div', { class: 'risposta-sol', 'aria-busy': 'true' }, testaRisposta(), scheletro());
      }
      if (!d) {
        if (ed.id) {
          return h('div', null, messaggioInLinea(ed.messaggio || 'La risposta non si è caricata.'),
            h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: () => ed.carica(ed.id) }, 'Riprova a caricarla')));
        }
        return messaggioInLinea(ed.messaggio);
      }
      if (d.stato === 'inviata') {
        // Dopo l'invio la risposta si chiude con una spunta.
        return h('div', { class: 'risposta-sol risposta-inviata' },
          h('span', { class: 'cerchio-spunta', 'aria-hidden': 'true' }, icona('spunta')),
          h('div', { class: 'testi' },
            h('p', { class: 'titolo-voce' }, titolo() + ' inviata'),
            h('p', { class: 'sotto-voce' }, (d.inviata_il ? 'Partita ' + L.dataLunga(d.inviata_il) : 'Partita') + ' verso ' + (d.a || []).join(', ') + '.')),
          messaggioInLinea(ed.messaggio, ed.tipoMessaggio));
      }
      if (d.stato === 'scartata') {
        return h('div', { class: 'pannello-bozza scartata' },
          h('p', { class: 'tenue' }, 'Risposta scartata.'),
          h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => ed.prepara(null) }, 'Preparane un\'altra')));
      }
      if (d.stato === 'errore' && !(d.testo || '').trim()) {
        // È fallita la scrittura della bozza, non l'invio: si può solo riprovare a prepararla.
        return h('div', { class: 'risposta-sol' }, testaRisposta(),
          messaggioInLinea(ed.messaggio || d.errore || 'Sol non è riuscito a scrivere la risposta.'));
      }
      // pronta o errore d'invio: si scrive direttamente qui, e si (re)invia.
      const a = L.elencoIndirizzi(ed.campi.a);
      const destinatario = h('button', {
        type: 'button', class: 'destinatario', 'aria-expanded': ed.campiAperti ? 'true' : 'false', title: 'Destinatari e oggetto',
        onclick: () => { ed.campiAperti = !ed.campiAperti; render(); if (ed.campiAperti && ed.el.a) ed.el.a.focus(); },
      }, a.length ? 'a ' + a[0] + (a.length > 1 ? ' e altri ' + (a.length - 1) : '') : 'manca il destinatario');
      ed.el.salvataggio = h('span', { id: idCampo('salvataggio'), class: 'salvataggio' }, ed.sporca ? 'Modifiche da salvare' : '');
      ed.el.messaggio = h('div', { class: 'messaggio-bozza' }, messaggioInLinea(ed.messaggio, ed.tipoMessaggio));
      const testo = h('textarea', {
        id: idCampo('testo'), class: 'testo-bozza' + (ed.campoErrato === 'testo' ? ' campo-errato' : ''), 'aria-label': 'Testo della risposta',
        spellcheck: 'true', value: ed.campi.testo, readonly: ed.invio ? true : null, oninput: (e) => modifica('testo', e.target),
      });
      ed.el.testo = testo;
      // Il testo cresce col contenuto: si legge tutto senza una finestrella che scorre dentro la scheda.
      const adatta = () => { testo.style.height = 'auto'; if (testo.scrollHeight) testo.style.height = testo.scrollHeight + 'px'; };
      testo.addEventListener('input', adatta);
      setTimeout(adatta, 0);
      return h('div', { class: 'risposta-sol' + (d.stato === 'errore' ? ' con-errore' : '') },
        testaRisposta(destinatario),
        avvisiRisposta(),
        ed.el.messaggio,
        ed.campiAperti ? h('div', { class: 'campi-risposta' }, campo('a', 'A'), campo('cc', 'Cc'), campo('oggetto', 'Oggetto')) : null,
        testo,
        pilloleTono(false),
        ed.rifaiAperto ? campoIstruzioni('Come deve cambiare?', true, (t) => ed.rifai(t), 'Rifalla') : null,
        ed.el.salvataggio);
    }

    /** Più breve, Più formale, Chiedi più tempo, Altro… (§5): ognuna chiede a Sol di rifarla. */
    function pilloleTono(ferme) {
      ed.el.pillole = L.TONI.map((t) => h('button', {
        type: 'button', class: 'pillola-tono', 'data-tono': t.chiave, 'aria-pressed': ed.tono === t.chiave || ed.tonoInCorso === t.chiave ? 'true' : 'false',
        disabled: ferme || ed.invio ? true : null, onclick: () => ed.rifai(t.istruzioni, false, t.chiave),
      }, t.etichetta));
      const altro = h('button', {
        type: 'button', class: 'pillola-tono', 'data-tono': 'altro', 'aria-pressed': ed.rifaiAperto ? 'true' : 'false', disabled: ferme || ed.invio ? true : null,
        onclick: () => {
          ed.rifaiAperto = !ed.rifaiAperto;
          render();
          const t = document.getElementById(idCampo('istruzioni'));
          if (t) t.focus();
        },
      }, 'Altro…');
      ed.el.pillole.push(altro);
      return h('div', { class: 'pillole-tono', role: 'group', 'aria-label': 'Cambia il tono' }, ed.el.pillole);
    }

    /** Un sollecito ("Aspetti risposta"): campi, testo e pulsanti dentro il riquadro. */
    function contenutoSollecito() {
      if (ed.caricando) return h('p', { class: 'attesa' }, 'Carico la bozza…');
      if (ed.preparando) return h('div', { class: 'pannello-bozza' }, h('h3', null, titolo()), scheletro());
      if (!ed.dati) {
        if (ed.id) {
          return h('div', null, messaggioInLinea(ed.messaggio || 'La bozza non si è caricata.'),
            h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: () => ed.carica(ed.id) }, 'Riprova a caricarla'));
        }
        return h('div', null, messaggioInLinea(ed.messaggio),
          h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => ed.prepara(null) }, ed.inviata ? 'Prepara il sollecito' : 'Prepara la risposta'));
      }
      const d = ed.dati;
      if (d.stato === 'in_preparazione') return h('div', { class: 'pannello-bozza' }, h('h3', null, titolo()), scheletro());
      if (d.stato === 'inviata') {
        return h('div', { class: 'risposta-sol risposta-inviata' },
          h('span', { class: 'cerchio-spunta', 'aria-hidden': 'true' }, icona('spunta')),
          h('div', { class: 'testi' }, h('p', { class: 'titolo-voce' }, titolo() + ' inviato'),
            h('p', { class: 'sotto-voce' }, (d.inviata_il ? 'Partito ' + L.dataLunga(d.inviata_il) : 'Partito') + ' verso ' + (d.a || []).join(', ') + '.')),
          messaggioInLinea(ed.messaggio, ed.tipoMessaggio));
      }
      if (d.stato === 'scartata') {
        return h('div', { class: 'pannello-bozza scartata' }, h('p', { class: 'tenue' }, 'Bozza scartata.'),
          h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => ed.prepara(null) }, 'Prepara di nuovo il sollecito')));
      }
      if (d.stato === 'errore' && !(d.testo || '').trim()) {
        return h('div', { class: 'pannello-bozza' }, h('h3', null, titolo()),
          messaggioInLinea(ed.messaggio || d.errore || 'Sol non è riuscito a scrivere la bozza.'),
          h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => ed.rifai(null) }, 'Riprova a preparare')));
      }
      ed.el.salvataggio = h('span', { id: idCampo('salvataggio'), class: 'salvataggio' }, ed.sporca ? 'Modifiche da salvare' : '');
      ed.el.messaggio = h('div', { class: 'messaggio-bozza' }, messaggioInLinea(ed.messaggio, ed.tipoMessaggio));
      const testo = h('textarea', {
        id: idCampo('testo'), class: 'testo-bozza' + (ed.campoErrato === 'testo' ? ' campo-errato' : ''), 'aria-label': 'Testo del sollecito',
        spellcheck: 'true', value: ed.campi.testo, readonly: ed.invio ? true : null, oninput: (e) => modifica('testo', e.target),
      });
      ed.el.testo = testo;
      ed.el.etichettaInvia = h('span', null, etichettaInvio());
      ed.el.invia = h('button', { type: 'button', class: 'pulsante pieno primario', disabled: ed.invio ? true : null, onclick: () => ed.invia() }, ed.el.etichettaInvia);
      ed.el.rifai = ed.rifaiAperto ? null : h('button', { type: 'button', class: 'pulsante bianco', disabled: ed.invio ? true : null, onclick: () => {
        ed.rifaiAperto = true;
        render();
        const t = document.getElementById(idCampo('istruzioni'));
        if (t) t.focus();
      } }, 'Rifai con istruzioni');
      return h('div', { class: 'pannello-bozza' + (d.stato === 'errore' ? ' con-errore' : '') },
        h('div', { class: 'testa-risposta' }, h('span', { class: 'titolo-risposta' }, sole(), titolo() + (d.modificata ? ' (ritoccato da te)' : '')), ed.el.salvataggio),
        avvisiRisposta(),
        ed.el.messaggio,
        campo('a', 'A'),
        campo('cc', 'Cc'),
        campo('oggetto', 'Oggetto'),
        testo,
        ed.rifaiAperto ? campoIstruzioni('Cosa deve cambiare?', true, (t) => ed.rifai(t)) : null,
        h('div', { class: 'azioni-bozza' },
          ed.el.invia,
          ed.el.rifai,
          h('span', { class: 'spazio' }),
          conConferma('Scarta', 'Scartare la bozza?', 'Sì, scarta', () => ed.scarta())));
    }

    return ed;
  }

  // --- Smista: le mail incerte, una alla volta, scheda grande al centro (§6) ------------------------

  function vistaSmista() {
    S.smista = { mail: [], i: 0, totale: 0, gruppiAperti: false, finito: false, caricando: true, errore: null };
    S.dom.smista = h('div', { class: 'smista-dentro' });
    svuota(S.dom.area, h('div', { class: 'smista-vista' }, S.dom.smista));
    disegnaSmista();
    caricaSmista();
  }

  async function caricaSmista() {
    const r = await Api.get('/server/api/mail?smistamento=da_controllare&sospetta=false&limite=200');
    if (S.vista !== 'smista' || !S.smista) return;
    S.smista.caricando = false;
    if (!r.ok || !r.dati || !Array.isArray(r.dati.mail)) {
      S.smista.errore = r.errore || 'Le mail da smistare non si sono caricate.';
      disegnaSmista();
      return;
    }
    // Le possibili truffe NON passano da qui: hanno la loro scheda.
    S.smista.mail = r.dati.mail.filter((m) => !m.sospetta);
    S.smista.totale = S.smista.mail.length;
    S.smista.finito = S.smista.totale === 0;
    S.smista.errore = null;
    disegnaSmista();
  }

  function esciSmista() {
    return vaiVista(S.smistaDa && S.smistaDa !== 'smista' ? S.smistaDa : 'oggi', { forza: true });
  }

  function disegnaSmista() {
    const z = S.dom.smista;
    const s = S.smista;
    if (!z || !z.isConnected || !s) return;
    const fatte = Math.min(s.i, s.totale);
    const pieno = h('span');
    pieno.style.width = (s.totale ? Math.round((fatte / s.totale) * 100) : (s.finito ? 100 : 0)) + '%';
    const contatore = s.caricando ? '' : (s.finito ? 'Fatto' : (s.i + 1) + ' di ' + s.totale);
    const testa = h('div', { class: 'testa-smista' },
      h('button', { type: 'button', class: 'rotondo', 'aria-label': 'Chiudi', title: 'Chiudi (Esc)', onclick: esciSmista }, icona('chiudi')),
      h('span', { class: 'titolo' }, 'Smista'),
      h('span', { class: 'contatore' }, contatore));
    const barra = h('div', { class: 'barra-avanzamento', role: 'progressbar', 'aria-label': contatore || 'Smista', 'aria-valuemin': '0', 'aria-valuemax': String(s.totale), 'aria-valuenow': String(fatte) }, pieno);
    if (s.caricando) {
      svuota(z, testa, barra, h('div', { class: 'pila' }, h('div', { class: 'scheda-grande' }, scheletro())));
      return;
    }
    if (s.errore) {
      svuota(z, testa, barra, h('div', { class: 'vuoto-centrato' }, messaggioInLinea(s.errore),
        h('button', { type: 'button', class: 'pulsante bianco', onclick: () => { s.caricando = true; disegnaSmista(); caricaSmista(); } }, 'Riprova')));
      return;
    }
    if (s.finito) {
      const tornaA = S.smistaDa === 'posta' ? 'Torna alla posta' : 'Torna a Oggi';
      svuota(z, testa, barra, h('div', { class: 'fine-smista', role: 'status' },
        h('span', { class: 'cerchio-spunta', 'aria-hidden': 'true' }, icona('spunta')),
        h('h2', null, s.totale ? 'Tutto sistemato' : 'Niente da smistare'),
        s.totale ? h('p', null, 'Sol si ricorderà queste scelte per le prossime mail degli stessi mittenti.') : null,
        h('button', { type: 'button', class: 'pulsante pieno', onclick: esciSmista }, tornaA)));
      const b = z.querySelector('.fine-smista .pulsante');
      if (b) b.focus();
      return;
    }
    const m = s.mail[s.i];
    const g = gruppoDi(m);
    const scheda = h('article', { class: 'scheda-grande', 'aria-label': 'Mail da smistare', 'data-id': String(m.id) },
      h('div', { class: 'chi' },
        h('span', { class: 'avatar', 'aria-hidden': 'true' }, inizialiDi(m)),
        h('div', { class: 'testi' }, h('span', { class: 'nome' }, nomeMittente(m)), h('span', { class: 'indirizzo' }, (m.mittente && m.mittente.nome && m.mittente.indirizzo) || '')),
        h('span', { class: 'ora-smista' }, L.oraBreve(m.ricevuta_il))),
      h('h2', null, m.oggetto || '(senza oggetto)'),
      h('p', { class: 'riassunto-smista' }, m.riassunto || m.anteprima || ''),
      h('span', { class: 'spazio' }),
      h('div', { class: 'separatore' }),
      h('span', { class: 'etichetta' }, 'Sol propone'),
      h('div', { class: 'proposta' },
        h('span', { class: 'pillola-gruppo ' + classeColore(g) }, h('span', { class: 'pallino', 'aria-hidden': 'true' }), (g && g.nome) || m.gruppo || 'Senza gruppo'),
        h('span', { class: 'sicurezza' }, L.paroleSicurezza(m.sicurezza))));
    S.dom.schedaSmista = scheda;
    const restano = s.totale - s.i;
    svuota(z, testa, barra,
      h('div', { class: 'pila' }, restano > 2 ? h('div', { class: 'dietro-2' }) : null, restano > 1 ? h('div', { class: 'dietro-1' }) : null, scheda),
      s.gruppiAperti
        ? h('div', { class: 'scelta-gruppo' },
          h('p', { class: 'domanda' }, 'In quale gruppo va?'),
          h('div', { class: 'griglia-gruppi' }, S.gruppi.map((x, i) => h('button', { type: 'button', class: 'pulsante-gruppo ' + classeColore(x), 'data-gruppo': String(x.id), onclick: () => smistaIn(x.id) },
            h('span', { class: 'pallino', 'aria-hidden': 'true' }), h('span', { class: 'nome' }, x.nome), i < 9 ? h('kbd', null, String(i + 1)) : null))),
          h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => { s.gruppiAperti = false; disegnaSmista(); } }, 'Annulla')))
        : h('div', { class: 'azioni-smista' },
          h('button', { type: 'button', class: 'pulsante bianco', onclick: () => { s.gruppiAperti = true; disegnaSmista(); } }, icona('indietro'), 'Cambia', h('kbd', { 'aria-hidden': 'true' }, '←')),
          h('button', { type: 'button', class: 'pulsante pieno', onclick: smistaVaBene }, 'Va bene', icona('spunta'), h('kbd', { 'aria-hidden': 'true' }, '→'))));
  }

  async function smistaVaBene() {
    const s = S.smista;
    if (!s || s.finito || s.caricando || S.occupato) return;
    const m = s.mail[s.i];
    S.occupato = 'smista';
    let r;
    try {
      r = await Api.post('/server/api/mail/' + m.id + '/conferma');
    } finally {
      S.occupato = null;
    }
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    await avantiSmista('destra');
  }

  /** Cambia gruppo: Sol impara il mittente (una mail alla volta, come nella Posta). */
  async function smistaIn(gruppoId) {
    const s = S.smista;
    if (!s || s.finito || s.caricando || S.occupato) return;
    const m = s.mail[s.i];
    if (gruppoId === m.gruppo_id) { await smistaVaBene(); return; }
    S.occupato = 'smista';
    let r;
    try {
      r = await Api.post('/server/api/mail/' + m.id + '/sposta', { gruppo_id: gruppoId, impara: true });
    } finally {
      S.occupato = null;
    }
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    await avantiSmista('sinistra');
  }

  async function avantiSmista(verso) {
    const s = S.smista;
    if (conMovimenti() && S.dom.schedaSmista && S.dom.schedaSmista.isConnected) {
      S.dom.schedaSmista.classList.add(verso === 'destra' ? 'esce-destra' : 'esce-sinistra');
      S.occupato = 'movimento';
      try { await new Promise((r) => setTimeout(r, 200)); } finally { S.occupato = null; }
    }
    if (S.smista !== s) return;
    s.i += 1;
    s.gruppiAperti = false;
    if (s.i >= s.totale) s.finito = true;
    disegnaSmista();
    aggiornaContatori();
  }

  function tastoSmista(e, inCampo) {
    const s = S.smista;
    if (!s) return;
    const azione = L.azioneTastoSmista({ key: e.key, ctrl: e.ctrlKey, alt: e.altKey, meta: e.metaKey, inCampo, ripetuto: e.repeat },
      { gruppiAperti: s.gruppiAperti, numeroGruppi: S.gruppi.length });
    if (!azione) return;
    // Invio su un pulsante fa il lavoro di quel pulsante.
    if (e.key === 'Enter' && e.target && (e.target.tagName === 'BUTTON' || e.target.tagName === 'A')) return;
    e.preventDefault();
    if (azione.tipo === 'chiudi') { esciSmista(); return; }
    if (s.finito) { if (azione.tipo === 'va-bene') esciSmista(); return; }
    if (s.caricando || S.occupato) return;
    if (azione.tipo === 'va-bene') smistaVaBene();
    else if (azione.tipo === 'cambia') { s.gruppiAperti = !s.gruppiAperti; disegnaSmista(); }
    else if (azione.tipo === 'chiudi-gruppi') { s.gruppiAperti = false; disegnaSmista(); }
    else if (azione.tipo === 'gruppo') smistaIn(S.gruppi[azione.indice].id);
  }

  // --- Riassunti -------------------------------------------------------------------------------

  function vistaRiassunti() {
    const oggi = h('div', null, scheletro());
    const oggiInfo = h('p', { class: 'tenue' });
    const risultato = h('div', { class: 'risultato-riassunto' });
    const recenti = h('ul', { class: 'elenco-semplice' });
    const richiesta = h('textarea', {
      id: 'richiesta-riassunto', rows: '3', placeholder: 'Per esempio: cosa ha scritto Bianchi questa settimana', 'aria-label': 'Cosa vuoi sapere?',
      onkeydown: (e) => { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); chiedi(); } },
    });
    const pulsante = h('button', { type: 'button', class: 'pulsante pieno', onclick: () => chiedi() }, 'Chiedi');

    const disegnaOggiRiassunto = (d) => {
      svuota(oggi, d && d.testo ? h('p', { class: 'testo-punto' }, testoConRiferimenti(d.testo)) : h('p', { class: 'testo-punto tenue' }, 'Il riassunto di oggi non è ancora pronto.'));
    };

    async function caricaOggiRiassunto() {
      const r = await Api.get('/server/api/riassunti/oggi');
      if (!oggi.isConnected) return;
      if (!r.ok) { svuota(oggi, messaggioInLinea(r.errore)); return; }
      const d = r.dati || {};
      disegnaOggiRiassunto(d);
      oggiInfo.textContent = d.aggiornato_il ? 'Aggiornato alle ' + oraDi(d.aggiornato_il) : '';
      if (d.testo) risolviNomi(d.testo, () => { if (oggi.isConnected) disegnaOggiRiassunto(d); });
    }

    async function caricaRecenti() {
      const r = await Api.get('/server/api/riassunti');
      if (!recenti.isConnected || !r.ok || !Array.isArray(r.dati)) return;
      svuota(recenti, r.dati.length === 0 ? h('li', { class: 'tenue' }, 'Nessuna richiesta finora.') : r.dati.map((x) => h('li', null,
        h('button', { type: 'button', class: 'pulsante testo', onclick: () => segui(x.id) }, x.richiesta || 'Riassunto'),
        h('span', { class: 'spazio' }), h('span', { class: 'tenue' }, L.oraBreve(x.creato_il)))));
    }

    async function chiedi() {
      const t = richiesta.value.trim();
      if (!t) { richiesta.focus(); return; }
      if (pulsante.disabled) return;
      pulsante.disabled = true;
      svuota(risultato, h('div', { class: 'scheda' }, scheletro()));
      const r = await Api.post('/server/api/riassunti', { richiesta: t });
      pulsante.disabled = false;
      if (!r.ok) { svuota(risultato, messaggioInLinea(r.errore)); return; }
      segui(r.dati.id);
    }

    async function segui(id) {
      risultato.dataset.segue = String(id);
      const r = await Api.get('/server/api/riassunti/' + id);
      if (!risultato.isConnected || risultato.dataset.segue !== String(id)) return;
      if (!r.ok) { svuota(risultato, messaggioInLinea(r.errore)); return; }
      const d = r.dati || {};
      const testo = d.stato === 'pronto' ? d.testo : d.testo_parziale;
      svuota(risultato, h('section', { class: 'scheda' },
        h('h3', null, d.richiesta || 'Riassunto'),
        d.stato === 'errore' ? messaggioInLinea(d.errore || 'Il riassunto non è riuscito. Riprova tra poco.') : null,
        testo ? h('div', { class: 'riassunto-testo' }, testoConRiferimenti(testo)) : null,
        d.stato === 'in_coda' || d.stato === 'in_corso' ? scheletro() : null));
      if (d.stato === 'in_coda' || d.stato === 'in_corso') setTimeout(() => segui(id), T.riassunto);
      else {
        caricaRecenti();
        if (testo) risolviNomi(testo, () => segui(id));
      }
    }

    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('div', { class: 'testa-pagina' }, h('h1', null, 'Riassunti')),
      h('section', { class: 'scheda punto-oggi', 'aria-label': 'Oggi' }, h('span', { class: 'etichetta' }, 'Oggi'), oggi, oggiInfo),
      h('section', { class: 'scheda blocco' },
        h('label', { for: 'richiesta-riassunto' }, 'Cosa vuoi sapere?'),
        h('div', { class: 'riga-istruzioni' }, richiesta, microfono(richiesta)),
        h('div', { class: 'azioni' }, pulsante)),
      h('div', { class: 'blocco' }, risultato),
      h('div', { class: 'titolo-sezione' }, h('h2', null, 'Chiesti di recente')),
      h('div', { class: 'scheda-elenco blocco' }, recenti)));
    caricaOggiRiassunto();
    caricaRecenti();
  }

  // --- Aspetti risposta ("niente resta indietro") ------------------------------------------------

  function vistaAspetti() {
    const zona = h('div', null, h('p', { class: 'vuoto' }, 'Carico…'));
    svuota(S.dom.area, h('div', { class: 'pagina' }, h('div', { class: 'testa-pagina' }, h('h1', null, 'Aspetti risposta')), zona));
    caricaAspetti(zona, S.vistaOpzioni && S.vistaOpzioni.apriSollecito);
  }

  async function caricaAspetti(zona, apriSubito) {
    const r = await Api.get('/server/api/indietro');
    if (!zona.isConnected) return;
    if (!r.ok) { svuota(zona, messaggioInLinea(r.errore)); return; }
    const d = r.dati || {};
    const aspettanoTe = d.aspettano_te || [];
    const aspettiTu = d.aspetti_tu || [];
    let daAprire = null;

    const listaTe = aspettanoTe.length === 0
      ? h('p', { class: 'vuoto' }, 'Nessuna: sei in pari.')
      : h('div', { class: 'scheda-elenco' }, aspettanoTe.map(({ mail: m, giorni }) => {
        const pronta = m.bozza && m.bozza.stato === 'pronta' && !m.risposta_data;
        const riga = h('div', { class: 'riga-aspetti' },
          h('span', { class: 'cerchio ' + classeColore(gruppoDi(m)), 'aria-hidden': 'true' }, inizialiDi(m)),
          h('div', { class: 'testi' },
            h('div', { class: 'titolo-voce' }, nomeMittente(m) + ' · da ' + L.giorni(giorni)),
            h('div', { class: 'sotto-voce' }, m.oggetto || '(senza oggetto)')),
          h('div', { class: 'azioni' },
            h('a', { class: 'pulsante ' + (pronta ? 'pieno' : 'tenue') + ' piccolo', href: '#mail/' + m.id }, pronta ? 'Rivedi' : 'Rispondi'),
            h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: async () => {
              const x = await Api.post('/server/api/mail/' + m.id + '/rispondere', { da_rispondere: false });
              if (x.ok) { riga.remove(); avviso('Tolta dalla lista.', 'ok'); } else avviso(x.errore, 'errore');
            } }, 'Non serve')));
        return riga;
      }));

    const listaTu = aspettiTu.length === 0
      ? h('p', { class: 'vuoto' }, 'Nessuna: tutte le tue richieste hanno avuto risposta.')
      : h('div', { class: 'scheda-elenco' }, aspettiTu.map((voce) => {
        const inv = voce.inviata || {};
        const spazioBozza = h('div', { class: 'spazio-bozza' });
        // Il riquadro di QUESTO sollecito: ognuno ha il suo, con la sua bozza e i suoi id.
        const apri = () => {
          let ed = S.solleciti.get(inv.id);
          if (ed && !ed.distrutto) { ed.focus(); return; }
          ed = creaEditor({
            contenitore: spazioBozza,
            prefisso: 'sollecito-' + inv.id,
            inviata: inv,
            prepara: () => Api.post('/server/api/indietro/' + inv.id + '/sollecito'),
            suCambio: (e2) => { if (e2.dati) voce.bozza_sollecito = { id: e2.dati.id, stato: e2.dati.stato }; },
          });
          S.solleciti.set(inv.id, ed);
          if (voce.bozza_sollecito && voce.bozza_sollecito.id) ed.carica(voce.bozza_sollecito.id);
          else ed.prepara(null);
        };
        if (apriSubito != null && Number(apriSubito) === inv.id) daAprire = apri;
        const a = (inv.a || [])[0] || '';
        const riga = h('div', { class: 'riga-aspetti colonna' },
          h('div', { class: 'riga' },
            h('span', { class: 'cerchio neutro', 'aria-hidden': 'true' }, L.iniziali('', a)),
            h('div', { class: 'testi' },
              h('div', { class: 'titolo-voce' }, 'A ' + (inv.a || []).join(', ') + ' · ' + L.giorni(voce.giorni) + ' fa'),
              h('div', { class: 'sotto-voce' }, inv.oggetto || '(senza oggetto)')),
            h('div', { class: 'azioni' },
              h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: apri }, voce.bozza_sollecito ? 'Rivedi' : 'Prepara sollecito'),
              h('button', { type: 'button', class: 'pulsante bianco piccolo', onclick: async () => {
                const ed = S.solleciti.get(inv.id);
                if (ed && ed.invio) { avviso('Aspetta: il sollecito sta aspettando la conferma d\'invio.', 'info'); return; }
                const x = await Api.post('/server/api/indietro/' + inv.id + '/ignora');
                if (!x.ok) { avviso(x.errore, 'errore'); return; }
                if (ed) { ed.distruggi(); S.solleciti.delete(inv.id); }
                riga.remove();
                avviso('Tolta dalla lista.', 'ok');
              } }, 'Non serve'))),
          spazioBozza);
        return riga;
      }));

    svuota(zona,
      h('div', { class: 'titolo-sezione' }, h('h2', null, 'Aspettano te'), aspettanoTe.length ? h('span', { class: 'conto' }, String(aspettanoTe.length)) : null),
      h('div', { class: 'blocco' }, listaTe),
      h('div', { class: 'titolo-sezione' }, h('h2', null, 'Aspetti tu'), aspettiTu.length ? h('span', { class: 'conto' }, String(aspettiTu.length)) : null),
      h('div', { class: 'blocco' }, listaTu));
    if (daAprire) daAprire();
  }

  // --- Scadenze ----------------------------------------------------------------------------------

  function vistaScadenze() {
    const zona = h('div', null, h('p', { class: 'vuoto' }, 'Carico…'));
    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('div', { class: 'testa-pagina' }, h('h1', null, 'Scadenze'),
        h('button', { type: 'button', class: 'rotondo', 'aria-label': 'Aggiungi una scadenza', title: 'Aggiungi', onclick: () => nuovaScadenza(zona) }, icona('piu'))),
      zona));
    caricaScadenze(zona);
  }

  function nuovaScadenza(zona) {
    apriFoglio('Nuova scadenza', (corpo, chiudi) => {
      const descr = h('input', { type: 'text', id: 'nuova-descr', placeholder: 'Per esempio: offerta capannone a Rossi', maxlength: '200' });
      const quando = h('input', { type: 'date', id: 'nuova-data' });
      const esito = h('div');
      svuota(corpo,
        h('div', { class: 'campo' }, h('label', { for: 'nuova-descr' }, 'Cosa'), descr),
        h('div', { class: 'campo' }, h('label', { for: 'nuova-data' }, 'Quando'), quando),
        esito,
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
          if (!descr.value.trim() || !quando.value) { svuota(esito, messaggioInLinea('Scrivi cosa scade e scegli il giorno.')); return; }
          const r = await Api.post('/server/api/scadenze', { descrizione: descr.value.trim(), quando: quando.value });
          if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); return; }
          chiudi();
          avviso('Scadenza aggiunta.', 'ok');
          caricaScadenze(zona);
        } }, 'Aggiungi')));
    });
  }

  async function caricaScadenze(zona) {
    const r = await Api.get('/server/api/scadenze?stato=aperta');
    if (!zona.isConnected) return;
    if (!r.ok) { svuota(zona, messaggioInLinea(r.errore)); return; }
    const lista = (Array.isArray(r.dati) ? r.dati : []).slice().sort((a, b) => String(a.quando).localeCompare(String(b.quando)) || String(a.ora || '').localeCompare(String(b.ora || '')));
    if (lista.length === 0) { svuota(zona, h('p', { class: 'vuoto' }, 'Nessuna scadenza aperta.')); return; }
    const cambia = async (s, dati, frase) => {
      const x = await Api.patch('/server/api/scadenze/' + s.id, dati);
      if (!x.ok) { avviso(x.errore, 'errore'); return; }
      if (frase) avviso(frase, 'ok');
      caricaScadenze(zona);
    };
    const giorni = [];
    for (const s of lista) {
      const etichetta = L.etichettaGiorno(s.quando);
      if (!giorni.length || giorni[giorni.length - 1].etichetta !== etichetta) giorni.push({ etichetta, voci: [] });
      giorni[giorni.length - 1].voci.push(s);
    }
    svuota(zona, giorni.map((g) => h('section', { class: 'giorno-scadenze', 'aria-label': g.etichetta },
      h('span', { class: 'etichetta' }, g.etichetta),
      h('div', { class: 'scheda-elenco' }, g.voci.map((s) => {
        // La data si salva quando si esce dal campo (o con Invio), non a ogni cifra (§ revisione 11).
        const salvaData = (campo) => {
          if (campo.value && campo.value !== s.quando) {
            const nuova = campo.value;
            s.quando = nuova;
            cambia(s, { quando: nuova }, 'Data cambiata.');
          }
        };
        const data = h('input', {
          type: 'date', value: s.quando, 'aria-label': 'Nuova data',
          onblur: (e) => salvaData(e.target),
          onkeydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); salvaData(e.target); } },
        });
        return h('div', { class: 'riga-scadenza' },
          h('div', { class: 'testi' },
            h('div', { class: 'titolo-voce' }, s.descrizione || ''),
            h('div', { class: 'sotto-voce' }, s.ora ? 'alle ' + s.ora : '', s.mail_id ? h('a', { href: '#mail/' + s.mail_id, class: 'riferimento' }, (s.ora ? ' · ' : '') + 'apri la mail') : null)),
          data,
          h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => cambia(s, { stato: 'fatta' }, 'Fatto.') }, 'Fatto'),
          h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => cambia(s, { stato: 'scartata' }, 'Scadenza tolta.') }, 'Togli'));
      })))));
  }

  // --- Notifiche recenti --------------------------------------------------------------------------

  /** Le notifiche già arrivate: ognuna ricorda la sua mail, anche se è stata cliccata tardi dal Centro notifiche. */
  async function vistaNotifiche() {
    const zona = h('div', null, h('p', { class: 'vuoto' }, 'Carico…'));
    svuota(S.dom.area, h('div', { class: 'pagina' }, h('div', { class: 'testa-pagina' }, h('h1', null, 'Notifiche recenti')), zona));
    const r = await Api.get('/locale/notifiche');
    if (!zona.isConnected) return;
    const lista = r.ok && r.dati && Array.isArray(r.dati.notifiche) ? r.dati.notifiche : [];
    if (!lista.length) { svuota(zona, h('p', { class: 'vuoto' }, 'Nessuna notifica da quando Sol è acceso.')); return; }
    svuota(zona, h('div', { class: 'scheda-elenco blocco' }, lista.map((n) => h('div', { class: 'riga-aspetti' },
      h('span', { class: 'cerchio neutro', 'aria-hidden': 'true' }, icona('campana')),
      h('div', { class: 'testi' },
        h('div', { class: 'titolo-voce' }, n.titolo || ''),
        h('div', { class: 'sotto-voce' }, (n.testo || '') + ' · ' + L.oraBreve(n.quando))),
      L.destinazioneDaFrammento(n.destinazione) ? h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => vaiAlFrammento(n.destinazione) }, 'Apri') : null))));
  }

  // --- Impostazioni: elenco a gruppi in schede bianche, ogni voce apre un foglio (§7) ---------------

  const NOMI_TIPO = { outlook: 'Outlook', gmail: 'Gmail', finta: 'Casella di prova' };
  const NOMI_STATO = { collegata: 'collegata', in_collegamento: 'in collegamento', errore: 'non funziona' };
  const NOMI_TEMA = { auto: 'automatico', chiaro: 'chiaro', scuro: 'scuro' };

  function percento(x, base) {
    const v = x == null ? base : x;
    return v == null ? '' : Math.round(Number(v) * 100) + '%';
  }

  function vistaImpostazioni() {
    S.dom.imp = h('div', { class: 'pagina impostazioni' });
    svuota(S.dom.area, S.dom.imp);
    disegnaImpostazioni();
    Promise.all([leggiImpostazioni(), leggiCaselle(), leggiStato()]).then(() => disegnaImpostazioni());
  }

  async function leggiCaselle() {
    const r = await Api.get('/server/api/caselle');
    if (r.ok && Array.isArray(r.dati)) S.caselle = r.dati;
  }

  function disegnaImpostazioni() {
    const p = S.dom.imp;
    if (!p || !p.isConnected) return;
    const imp = S.impostazioni || {};
    const m = S.motore || {};
    const riga = (chiave, ic, nome, valore, azione) => h('button', { type: 'button', class: 'riga-imp', 'data-imp': chiave, onclick: azione || (() => apriImpostazione(chiave)) },
      h('span', { class: 'icona-imp', 'aria-hidden': 'true' }, icona(ic)),
      h('span', { class: 'nome-imp' }, nome),
      h('span', { class: 'valore-imp' }, valore || ''),
      icona('destra'));
    const gruppo = (titolo, righe) => h('section', { class: 'gruppo-imp', 'aria-label': titolo }, h('span', { class: 'etichetta' }, titolo), h('div', { class: 'scheda-elenco' }, righe));
    const caselle = S.caselle || [];
    const primaRiga = (t) => String(t || '').split('\n')[0];
    svuota(p,
      h('div', { class: 'testa-pagina' }, h('h1', null, 'Impostazioni')),
      gruppo('Tu', [
        riga('nome', 'persona', 'Nome', imp.nome || ''),
        riga('firma', 'matita', 'Firma', primaRiga(imp.firma)),
        riga('tono', 'fumetto', 'Tono delle risposte', imp.tono || ''),
      ]),
      gruppo('Caselle di posta', [
        caselle.map((c) => riga('casella-' + c.id, 'busta', c.nome || c.indirizzo || 'Casella',
          [h('span', { class: 'pallino-stato' + (c.stato === 'errore' ? ' errore' : c.stato === 'collegata' ? '' : ' attesa'), 'aria-hidden': 'true' }), NOMI_STATO[c.stato] || c.stato],
          () => apriCasella(c))),
        riga('gmail', 'piu', 'Aggiungi Gmail', ''),
        riga('outlook', 'piu', 'Aggiungi Outlook', ''),
        riga('casella-prova', 'piu', 'Casella di prova', ''),
      ]),
      gruppo('Smistamento', [
        riga('gruppi', 'cartella', 'Gruppi', S.gruppi.length ? L.quante(S.gruppi.length, 'gruppo', 'gruppi') : ''),
        riga('regole', 'persona', 'Mittenti ricordati', ''),
        riga('sicurezza', 'soglia', 'Quanto deve essere sicuro Sol', percento(imp.soglia_sicurezza, 0.85)),
        riga('prova', 'prova', 'Modalità prova', imp.modalita_prova ? 'accesa' : 'spenta'),
      ]),
      gruppo('Sicurezza', [
        riga('truffe', 'scudo', 'Controllo truffe', percento(imp.soglia_truffa, 0.5)),
        riga('dispositivi', 'telefono', 'Dispositivi collegati', ''),
        riga('scollega', 'catena', 'Scollega questo computer', ''),
      ]),
      gruppo('Promemoria', [
        riga('promemoria', 'giorni', 'Giorni per i promemoria', imp.giorni_risposta != null ? imp.giorni_risposta + ' e ' + imp.giorni_sollecito : ''),
      ]),
      gruppo('Questo computer', [
        riga('computer', 'computer', 'Avvio e collegamento', m.avvio_al_login ? 'si apre all\'accensione' : ''),
        riga('notifiche', 'campana', 'Notifiche recenti', '', () => vaiVista('notifiche')),
      ]),
      gruppo('App', [
        riga('tema', 'luna', 'Tema', NOMI_TEMA[m.tema || 'auto'] || 'automatico'),
        riga('tutorial', 'aiuto', 'Come funziona Sol', '', () => apriTutorial(false)),
        riga('versione', 'info', 'Versione e aggiornamento', m.versione ? 'Sol ' + m.versione : ''),
      ]));
  }

  const TITOLI_IMP = {
    nome: 'Nome', firma: 'Firma', tono: 'Tono delle risposte', gmail: 'Aggiungi Gmail', outlook: 'Aggiungi Outlook', 'casella-prova': 'Casella di prova',
    gruppi: 'Gruppi', regole: 'Mittenti ricordati', sicurezza: 'Quanto deve essere sicuro Sol', prova: 'Modalità prova', truffe: 'Controllo truffe',
    dispositivi: 'Dispositivi collegati', scollega: 'Scollega questo computer', promemoria: 'Giorni per i promemoria', computer: 'Questo computer',
    tema: 'Tema', versione: 'Versione e aggiornamento',
  };

  /** Apre il foglio di un'impostazione (anche da fuori: la pillola "Prova", la versione nuova). */
  async function apriImpostazione(chiave) {
    if (S.vista !== 'impostazioni') await vaiVista('impostazioni');
    apriFoglio(TITOLI_IMP[chiave] || 'Impostazioni', (corpo, chiudi) => {
      const fogli = {
        nome: () => campoImpostazione(corpo, chiudi, 'nome', 'Come ti saluta Sol nella pagina Oggi', h('input', { type: 'text', id: 'imp-nome', maxlength: '40', value: (S.impostazioni || {}).nome || '', 'data-campo': 'nome' })),
        firma: () => campoImpostazione(corpo, chiudi, 'firma', 'Firma in fondo alle risposte', h('textarea', { id: 'imp-firma', rows: '4', value: (S.impostazioni || {}).firma || '', 'data-campo': 'firma' })),
        tono: () => campoImpostazione(corpo, chiudi, 'tono', 'Tono delle risposte', h('input', { type: 'text', id: 'imp-tono', maxlength: '80', placeholder: 'Per esempio: cortese e diretto', value: (S.impostazioni || {}).tono || '', 'data-campo': 'tono' })),
        gmail: () => moduloGmail(corpo, chiudi),
        outlook: () => moduloOutlook(corpo, chiudi),
        'casella-prova': () => moduloProva(corpo, chiudi),
        gruppi: () => impGruppi(corpo),
        regole: () => impRegole(corpo),
        sicurezza: () => sogliaImpostazione(corpo, chiudi, 'soglia_sicurezza', 'imp-soglia', 50, 99, 1, 0.85,
          'Sotto questa soglia la mail finisce tra quelle da smistare e decidi tu.'),
        prova: () => impProva(corpo),
        truffe: () => sogliaImpostazione(corpo, chiudi, 'soglia_truffa', 'imp-truffa', 20, 90, 5, 0.5,
          'Più basso: Sol segnala più mail, anche qualcuna normale. Più alto: solo i casi più evidenti. Meglio una segnalazione in più che una truffa non vista.'),
        dispositivi: () => impDispositivi(corpo),
        scollega: () => svuota(corpo,
          h('p', { class: 'spiega' }, 'Il computer non vedrà più la posta finché non lo ricolleghi con un codice nuovo.'),
          h('div', { class: 'azioni' }, conConferma('Scollega questo computer', 'Scollegare?', 'Sì, scollega', scollegaQuesto, 'pulsante bianco'))),
        promemoria: () => impPromemoria(corpo, chiudi),
        computer: () => impComputer(corpo),
        tema: () => impTema(corpo),
        versione: () => impVersione(corpo),
      };
      (fogli[chiave] || (() => svuota(corpo, h('p', { class: 'vuoto' }, 'Niente da impostare qui.'))))();
    });
  }

  /** Salva una voce di /api/impostazioni; ridisegna l'elenco dietro il foglio. false = non salvata. */
  async function salvaImpostazioni(dati, radice, esito) {
    const x = await Api.patch('/server/api/impostazioni', dati);
    if (!x.ok) {
      if (esito) svuota(esito, messaggioInLinea(x.errore));
      evidenziaCampo(radice, x.dati && x.dati.campo);
      return false;
    }
    S.impostazioni = x.dati && typeof x.dati === 'object' ? Object.assign({}, S.impostazioni, x.dati) : Object.assign({}, S.impostazioni, dati);
    disegnaImpostazioni();
    renderRail();
    return true;
  }

  function campoImpostazione(corpo, chiudi, chiave, etichetta, campo) {
    const esito = h('div');
    const salva = async () => {
      if (await salvaImpostazioni({ [chiave]: campo.value }, corpo, esito)) { avviso('Salvato.', 'ok'); chiudi(); }
    };
    if (campo.tagName === 'INPUT') campo.addEventListener('keydown', (e) => { if (e.key === 'Enter') salva(); });
    svuota(corpo, h('div', { class: 'campo' }, h('label', { for: campo.id }, etichetta), campo), esito,
      h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante pieno', onclick: salva }, 'Salva')));
  }

  function sogliaImpostazione(corpo, chiudi, chiave, id, min, max, passo, base, spiega) {
    const imp = S.impostazioni || {};
    const iniziale = Math.round((imp[chiave] ?? base) * 100);
    const cursore = h('input', { type: 'range', id, min: String(min), max: String(max), step: String(passo), value: String(iniziale), 'data-campo': chiave });
    const valore = h('span', { class: 'valore' }, cursore.value + '%');
    cursore.addEventListener('input', () => { valore.textContent = cursore.value + '%'; });
    const esito = h('div');
    svuota(corpo,
      h('p', { class: 'spiega' }, spiega),
      h('label', { for: id }, 'Soglia: ', valore),
      cursore,
      esito,
      h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
        if (Number(cursore.value) === iniziale) { chiudi(); return; }
        if (await salvaImpostazioni({ [chiave]: Number(cursore.value) / 100 }, corpo, esito)) { avviso('Salvato.', 'ok'); chiudi(); }
      } }, 'Salva')));
  }

  function impProva(corpo) {
    const imp = S.impostazioni || {};
    const prova = h('input', { type: 'checkbox', id: 'imp-prova', checked: !!imp.modalita_prova, 'data-campo': 'modalita_prova' });
    const esito = h('div');
    prova.addEventListener('change', async () => {
      const prima = !!(S.impostazioni || {}).modalita_prova;
      const ok = await salvaImpostazioni({ modalita_prova: prova.checked }, corpo, esito);
      if (!ok) { prova.checked = prima; return; }
      await leggiSalute();
      renderStato();
      if (S.vista === 'posta') disegnaTestaPosta();
      avviso(prova.checked ? 'Modalità prova accesa.' : 'Modalità prova spenta.', 'ok');
      if (prima && !prova.checked) await chiediArretrato();
    });
    svuota(corpo,
      h('p', { class: 'spiega' }, 'Accesa: Sol decide i gruppi ma non sposta niente nelle caselle vere, mostra solo cosa farebbe. Spenta: Sol sposta davvero le mail nuove nelle cartelle (Outlook) o etichette (Gmail); per quelle già smistate prima, ti chiede. Sol non cancella mai nessuna mail.'),
      h('label', { class: 'interruttore' }, prova, 'Modalità prova'),
      esito);
  }

  function impPromemoria(corpo, chiudi) {
    const imp = S.impostazioni || {};
    const gRisposta = h('input', { type: 'number', id: 'imp-gr', min: '1', max: '30', value: String(imp.giorni_risposta ?? 2), 'data-campo': 'giorni_risposta' });
    const gSollecito = h('input', { type: 'number', id: 'imp-gs', min: '1', max: '60', value: String(imp.giorni_sollecito ?? 7), 'data-campo': 'giorni_sollecito' });
    const esito = h('div');
    svuota(corpo,
      h('div', { class: 'campo' }, h('label', { for: 'imp-gr' }, 'Una mail aspetta te dopo (giorni)'), gRisposta),
      h('div', { class: 'campo' }, h('label', { for: 'imp-gs' }, 'Proponi un sollecito dopo (giorni)'), gSollecito),
      esito,
      h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
        const dati = {};
        if (Number(gRisposta.value) !== imp.giorni_risposta) dati.giorni_risposta = Number(gRisposta.value);
        if (Number(gSollecito.value) !== imp.giorni_sollecito) dati.giorni_sollecito = Number(gSollecito.value);
        if (!Object.keys(dati).length) { chiudi(); return; }
        if (await salvaImpostazioni(dati, corpo, esito)) { avviso('Salvato.', 'ok'); chiudi(); }
      } }, 'Salva')));
  }

  function impTema(corpo) {
    const attuale = (S.motore && S.motore.tema) || 'auto';
    const scegli = async (t) => {
      const r = await Api.post('/locale/tema', { tema: t });
      if (!r.ok) { avviso(r.errore, 'errore'); return; }
      if (S.motore) S.motore.tema = t;
      applicaTema();
      disegnaImpostazioni();
      impTema(corpo);
    };
    svuota(corpo, h('div', { class: 'scheda-elenco' }, ['auto', 'chiaro', 'scuro'].map((t) => h('button', {
      type: 'button', class: 'riga-imp', role: 'radio', 'aria-checked': t === attuale ? 'true' : 'false', 'data-tema': t, onclick: () => scegli(t),
    }, h('span', { class: 'nome-imp' }, { auto: 'Automatico (come Windows)', chiaro: 'Chiaro', scuro: 'Scuro' }[t]), h('span', { class: 'spazio' }),
      t === attuale ? icona('spunta') : null))));
  }

  /** §15.2: spenta la modalità prova, le mail già smistate si spostano nella casella solo con un sì esplicito. */
  async function chiediArretrato() {
    const r = await Api.get('/server/api/arretrato');
    const n = r.ok && r.dati ? Number(r.dati.da_applicare) || 0 : 0;
    if (n <= 0) return;
    const si = await chiediConferma({
      titolo: 'Spostare anche le mail già smistate?',
      testo: 'Ci sono ' + n + ' mail già smistate rimaste in Posta in arrivo. Sol tocca solo quelle che sono ancora lì.',
      si: 'Sì, spostale',
      no: 'No, lasciale dove sono',
    });
    if (!si) { avviso('Le mail già smistate restano dove sono. Sol sposterà solo le prossime.', 'info'); return; }
    const x = await Api.post('/server/api/arretrato/applica', { conferma: true });
    if (!x.ok) { avviso(x.errore, 'errore'); return; }
    const quante = x.dati && x.dati.da_applicare != null ? x.dati.da_applicare : n;
    avviso('Sol sposta ' + quante + ' mail nelle caselle: ci vuole qualche minuto.', 'ok');
  }

  /** Su un collegamento http (di casa, non protetto) le password non si mandano mai (§15.13). */
  function bloccoPassword() {
    if (!S.motore || !L.senzaProtezione(S.motore.server)) return null;
    return messaggioInLinea('Questo computer è collegato al server con un indirizzo di casa non protetto (http): da lì Sol non manda password. '
      + 'Collega il computer con l\'indirizzo https di Tailscale, per esempio ' + ESEMPIO_SERVER + ', e riprova.', 'avvertenza');
  }

  /** Una casella: stato, errore e azioni (Ricollega per quelle in errore, §15.3). */
  function apriCasella(c) {
    apriFoglio(c.nome || c.indirizzo || 'Casella', (corpo, chiudi) => {
      const zona = h('div', { class: 'zona-casella' });
      svuota(corpo,
        h('dl', { class: 'intestazioni' },
          h('dt', null, 'Indirizzo'), h('dd', null, c.indirizzo || ''),
          h('dt', null, 'Tipo'), h('dd', null, NOMI_TIPO[c.tipo] || c.tipo || ''),
          h('dt', null, 'Stato'), h('dd', null, h('span', { class: 'pallino-stato' + (c.stato === 'errore' ? ' errore' : c.stato === 'collegata' ? '' : ' attesa'), 'aria-hidden': 'true' }), NOMI_STATO[c.stato] || c.stato || ''),
          c.ultimo_controllo ? [h('dt', null, 'Ultimo controllo'), h('dd', null, L.oraBreve(c.ultimo_controllo))] : null,
          c.attiva_dal ? [h('dt', null, 'Mail dal'), h('dd', null, L.dataLunga(c.attiva_dal))] : null),
        c.errore ? messaggioInLinea(c.errore) : null,
        h('div', { class: 'azioni' },
          c.stato === 'errore' ? h('button', { type: 'button', class: 'pulsante pieno', onclick: () => ricollega(c, zona, chiudi) }, 'Ricollega') : null,
          h('button', { type: 'button', class: 'pulsante bianco', onclick: async () => {
            const x = await Api.post('/server/api/caselle/' + c.id + '/controlla');
            avviso(x.ok ? 'Controllo avviato: le mail nuove arrivano tra poco.' : x.errore, x.ok ? 'ok' : 'errore');
          } }, 'Controlla adesso'),
          h('button', { type: 'button', class: 'pulsante testo', onclick: async () => {
            const nuovo = await chiediTesto(zona, 'Nuovo nome', c.nome || '');
            if (nuovo == null) return;
            const x = await Api.patch('/server/api/caselle/' + c.id, { nome: nuovo });
            if (!x.ok) { avviso(x.errore, 'errore'); return; }
            await leggiCaselle();
            disegnaImpostazioni();
            chiudi();
          } }, 'Rinomina')),
        zona,
        h('div', { class: 'azioni' }, conConferma('Scollega la casella', 'Sol dimenticherà le mail di questa casella (nella casella vera non cambia niente). Scollegare?', 'Sì, scollega', async () => {
          const x = await Api.elimina('/server/api/caselle/' + c.id);
          if (!x.ok) { avviso(x.errore, 'errore'); return; }
          avviso('Casella scollegata.', 'ok');
          await leggiCaselle();
          disegnaImpostazioni();
          chiudi();
        }, 'pulsante testo')));
    });
  }

  async function caselleCambiate(chiudi) {
    await leggiCaselle();
    disegnaImpostazioni();
    if (chiudi) chiudi();
  }

  /** Un campo di testo sul posto, al posto di una finestra a comparsa. Restituisce il testo o null. */
  function chiediTesto(dove, domanda, valore, lunghezza) {
    return new Promise((fine) => {
      const campo = h('input', { type: 'text', value: valore, 'aria-label': domanda, maxlength: String(lunghezza || 80) });
      const chiudi = (esito) => { blocco.remove(); fine(esito); };
      const blocco = h('div', { class: 'chiedi-testo' }, h('label', null, domanda), campo,
        h('div', { class: 'azioni' },
          h('button', { type: 'button', class: 'pulsante pieno piccolo', onclick: () => chiudi(campo.value.trim() || null) }, 'Salva'),
          h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => chiudi(null) }, 'Annulla')));
      campo.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') chiudi(campo.value.trim() || null);
        if (e.key === 'Escape') { e.stopPropagation(); chiudi(null); }
      });
      dove.append(blocco);
      campo.focus();
      campo.select();
    });
  }

  function passiPasswordApp() {
    return [
      h('p', { class: 'spiega' }, 'Google non lascia entrare Sol con la password normale: serve una "password per le app", una password a parte, solo per Sol, che puoi togliere quando vuoi.'),
      h('ol', { class: 'passi' },
        h('li', null, 'Apri la pagina di Google ', h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => Api.post('/locale/apri', { url: 'https://myaccount.google.com/apppasswords' }) }, '"Password per le app"'), ' ed entra con l\'account della casella.'),
        h('li', null, 'Scrivi un nome, per esempio "Sol", e premi Crea.'),
        h('li', null, 'Google mostra 16 lettere: copiale qui sotto.')),
    ];
  }

  function evidenziaCampo(radice, campo) {
    for (const el of radice.querySelectorAll('[data-campo]')) el.classList.toggle('campo-errato', !!campo && el.getAttribute('data-campo') === campo);
  }

  function moduloGmail(corpo, chiudi) {
    const blocco = bloccoPassword();
    if (blocco) { svuota(corpo, blocco); return; }
    const indirizzo = h('input', { type: 'email', id: 'gm-indirizzo', placeholder: 'nome@gmail.com', autocomplete: 'off', spellcheck: 'false', 'data-campo': 'indirizzo' });
    const nome = h('input', { type: 'text', id: 'gm-nome', placeholder: 'Per esempio: Personale', maxlength: '40', 'data-campo': 'nome' });
    const password = h('input', { type: 'password', id: 'gm-password', placeholder: '16 lettere, anche con gli spazi', autocomplete: 'new-password', spellcheck: 'false', 'data-campo': 'password_app' });
    const esito = h('div');
    const modulo = h('div', { class: 'modulo-casella' });
    const pulsante = h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
      if (!indirizzo.value.trim() || !password.value.trim()) { svuota(esito, messaggioInLinea('Servono l\'indirizzo Gmail e la password per le app.')); return; }
      if (pulsante.disabled) return;
      pulsante.disabled = true;
      svuota(esito, h('p', { class: 'attesa' }, 'Sol prova a collegarsi a Gmail…'));
      const r = await Api.post('/server/api/caselle', { tipo: 'gmail', indirizzo: indirizzo.value.trim(), nome: nome.value.trim() || indirizzo.value.trim(), password_app: password.value.trim() });
      pulsante.disabled = false;
      if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); evidenziaCampo(modulo, r.dati && r.dati.campo); return; }
      password.value = '';
      avviso('Gmail collegata.', 'ok');
      caselleCambiate(chiudi);
    } }, 'Collega Gmail');
    svuota(corpo, svuota(modulo,
      passiPasswordApp(),
      h('label', { for: 'gm-indirizzo' }, 'Indirizzo Gmail'), indirizzo,
      h('label', { for: 'gm-nome' }, 'Nome della casella in Sol'), nome,
      h('label', { for: 'gm-password' }, 'Password per le app'), password,
      esito,
      h('div', { class: 'azioni' }, pulsante)));
    indirizzo.focus();
  }

  /** §15.3: ricollegare una casella in errore (Gmail: password per le app nuova; Outlook: codice Microsoft). */
  async function ricollega(c, zona, chiudi) {
    if (c.tipo === 'gmail') {
      const blocco = bloccoPassword();
      if (blocco) { svuota(zona, blocco); return; }
      const password = h('input', { type: 'password', id: 'ric-' + c.id, placeholder: '16 lettere, anche con gli spazi', autocomplete: 'new-password', spellcheck: 'false', 'data-campo': 'password_app' });
      const esito = h('div');
      const modulo = h('div', { class: 'modulo-casella' });
      const pulsante = h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
        if (!password.value.trim()) { password.focus(); return; }
        if (pulsante.disabled) return;
        pulsante.disabled = true;
        svuota(esito, h('p', { class: 'attesa' }, 'Sol riprova a collegarsi a Gmail…'));
        const r = await Api.post('/server/api/caselle/' + c.id + '/ricollega', { password_app: password.value.trim() });
        pulsante.disabled = false;
        if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); evidenziaCampo(modulo, r.dati && r.dati.campo); return; }
        password.value = '';
        avviso('Casella ricollegata: le mail arrivate nel frattempo entrano lo stesso.', 'ok');
        caselleCambiate(chiudi);
      } }, 'Ricollega');
      svuota(zona, svuota(modulo,
        h('p', { class: 'spiega' }, 'Sol tiene mail, bozze, scadenze e regole di questa casella: serve solo una password per le app nuova.'),
        passiPasswordApp(),
        h('label', { for: password.id }, 'Password per le app'), password,
        esito,
        h('div', { class: 'azioni' }, pulsante, h('button', { type: 'button', class: 'pulsante testo', onclick: () => svuota(zona) }, 'Annulla'))));
      password.focus();
      return;
    }
    svuota(zona, h('p', { class: 'attesa' }, c.tipo === 'outlook' ? 'Chiedo il codice a Microsoft…' : 'Ricollego…'));
    const r = await Api.post('/server/api/caselle/' + c.id + '/ricollega', {});
    if (!r.ok) { svuota(zona, messaggioInLinea(r.errore)); return; }
    if (r.dati && r.dati.codice_utente) {
      attesaOutlook(zona, r.dati, c.id, chiudi);
      return;
    }
    avviso('Casella ricollegata.', 'ok');
    caselleCambiate(chiudi);
  }

  function moduloOutlook(corpo, chiudi) {
    const nome = h('input', { type: 'text', id: 'ol-nome', value: 'Studio', maxlength: '40' });
    const esito = h('div');
    const zona = h('div');
    const pulsante = h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
      if (pulsante.disabled) return;
      pulsante.disabled = true;
      svuota(esito, h('p', { class: 'attesa' }, 'Chiedo il codice a Microsoft…'));
      const r = await Api.post('/server/api/caselle', { tipo: 'outlook', nome: nome.value.trim() || 'Outlook' });
      pulsante.disabled = false;
      if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); return; }
      svuota(esito);
      attesaOutlook(zona, r.dati, r.dati && r.dati.casella && r.dati.casella.id, chiudi);
    } }, 'Continua');
    svuota(corpo, h('div', { class: 'modulo-casella' },
      h('p', { class: 'spiega' }, 'Sol ti darà un codice da scrivere in una pagina di Microsoft. Lì entri con l\'account della casella e dai il permesso a Sol di leggere e spedire la posta.'),
      h('label', { for: 'ol-nome' }, 'Nome della casella in Sol'), nome,
      esito,
      h('div', { class: 'azioni' }, pulsante)), zona);
    nome.focus();
  }

  function attesaOutlook(zona, dati, casellaId, chiudi) {
    const stato = h('p', { class: 'attesa' }, 'Sol aspetta che tu finisca nella pagina di Microsoft…');
    svuota(zona, h('div', { class: 'modulo-casella' },
      h('h3', null, 'Ultimo passo nella pagina di Microsoft'),
      h('ol', { class: 'passi' },
        h('li', null, 'Premi il pulsante qui sotto: si apre la pagina di Microsoft nel browser.'),
        h('li', null, 'Scrivi questo codice:'),
        h('li', { class: 'senza-numero' }, h('span', { class: 'codice-grande' }, dati.codice_utente || '')),
        h('li', null, 'Entra con l\'account della casella e accetta. Poi torna qui: Sol se ne accorge da solo.')),
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
          const x = await Api.post('/locale/apri', { url: dati.url_verifica });
          if (!x.ok) avviso(x.errore + ' Indirizzo: ' + (dati.url_verifica || ''), 'errore');
        } }, 'Apri la pagina di Microsoft')),
      dati.scade_il ? h('p', { class: 'tenue' }, 'Il codice vale fino alle ' + L.oraBreve(dati.scade_il) + '.') : null,
      stato));
    const scade = dati.scade_il ? new Date(dati.scade_il).getTime() : Date.now() + 15 * 60000;
    const controlla = async () => {
      if (!stato.isConnected || !casellaId) return;
      if (Date.now() > scade) { svuota(stato, messaggioInLinea('Il codice è scaduto. Chiudi e riprova.')); return; }
      const r = await Api.get('/server/api/caselle/' + casellaId);
      if (!stato.isConnected) return;
      if (r.ok && r.dati && r.dati.stato === 'collegata') {
        avviso('Outlook collegata.', 'ok');
        caselleCambiate(chiudi);
        return;
      }
      if (r.ok && r.dati && r.dati.stato === 'errore') {
        svuota(stato, messaggioInLinea(r.dati.errore || 'Microsoft non ha dato il permesso. Riprova.'));
        return;
      }
      setTimeout(controlla, T.outlook);
    };
    setTimeout(controlla, T.outlook);
  }

  function moduloProva(corpo, chiudi) {
    const esito = h('div');
    svuota(corpo,
      h('p', { class: 'spiega' }, 'Una casella finta con mail inventate: serve per provare Sol o mostrarlo senza toccare la posta vera.'),
      esito,
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante pieno', onclick: async () => {
          const r = await Api.post('/server/api/caselle', { tipo: 'finta', nome: 'Prova' });
          if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); return; }
          avviso('Casella di prova aggiunta.', 'ok');
          caselleCambiate(chiudi);
        } }, 'Aggiungi la casella di prova')));
  }

  /** I gruppi: colore (i 12 del contratto, §17), descrizione (§15.14), raccolta sì/no (§18.2), ordine, nome. */
  async function impGruppi(sez) {
    await leggiGruppi();
    if (!sez.isConnected) return;
    const pieni = S.gruppi.length >= MAX_GRUPPI;
    const nuovo = h('input', { type: 'text', id: 'nuovo-gruppo', placeholder: 'Per esempio: Condomini', maxlength: '40', disabled: pieni ? true : null, 'data-campo': 'nome' });
    // §15.14: la descrizione dice al decisore che mail ci vanno; se manca il server usa il nome.
    const descrizione = h('input', { type: 'text', id: 'nuovo-gruppo-descrizione', placeholder: 'Per esempio: richieste e preventivi degli amministratori di condominio', maxlength: '200', disabled: pieni ? true : null, 'data-campo': 'descrizione' });
    const esito = h('div');
    const ricarica = async () => { await aggiornaContatori(); impGruppi(sez); disegnaImpostazioni(); };
    const aggiungi = async () => {
      if (!nuovo.value.trim()) { nuovo.focus(); return; }
      const corpo = { nome: nuovo.value.trim() };
      if (descrizione.value.trim()) corpo.descrizione = descrizione.value.trim();
      const r = await Api.post('/server/api/gruppi', corpo);
      if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); evidenziaCampo(sez, r.dati && r.dati.campo); return; }
      ricarica();
    };
    nuovo.addEventListener('keydown', (e) => { if (e.key === 'Enter') descrizione.focus(); });
    descrizione.addEventListener('keydown', (e) => { if (e.key === 'Enter') aggiungi(); });
    const scambia = async (i, j) => {
      const a = S.gruppi[i];
      const b = S.gruppi[j];
      if (!a || !b) return;
      const oa = a.ordine ?? i + 1;
      const ob = b.ordine ?? j + 1;
      const r1 = await Api.patch('/server/api/gruppi/' + a.id, { ordine: ob === oa ? (j + 1) : ob });
      const r2 = await Api.patch('/server/api/gruppi/' + b.id, { ordine: ob === oa ? (i + 1) : oa });
      if (!r1.ok || !r2.ok) avviso((r1.ok ? r2 : r1).errore, 'errore');
      ricarica();
    };
    const sceltaColore = (g) => {
      const attuale = L.coloreGruppo(g.colore);
      const riquadro = h('div', { class: 'scelta-colori', role: 'group', 'aria-label': 'Colore di ' + g.nome, hidden: true },
        L.COLORI_GRUPPI.map((c) => h('button', {
          type: 'button', class: 'colore-scelta col-' + c, 'data-colore': c,
          'aria-pressed': c === attuale ? 'true' : 'false', 'aria-label': NOMI_COLORI[c], title: NOMI_COLORI[c],
          onclick: async () => {
            if (c === attuale) { riquadro.hidden = true; return; }
            const r = await Api.patch('/server/api/gruppi/' + g.id, { colore: c });
            if (!r.ok) { avviso(r.errore, 'errore'); return; }
            avviso(g.nome + ': colore ' + NOMI_COLORI[c].toLowerCase() + '.', 'ok');
            ricarica();
          },
        }, c === attuale ? icona('spunta') : null)));
      const pulsante = h('button', { type: 'button', class: 'pulsante testo piccolo', 'aria-expanded': 'false', onclick: () => {
        riquadro.hidden = !riquadro.hidden;
        pulsante.setAttribute('aria-expanded', riquadro.hidden ? 'false' : 'true');
        if (!riquadro.hidden) { const b = riquadro.querySelector('[aria-pressed="true"]') || riquadro.querySelector('button'); if (b) b.focus(); }
      } }, 'Colore');
      return { riquadro, pulsante, attuale };
    };
    svuota(sez,
      h('ul', { class: 'elenco-schede' }, S.gruppi.map((g, i) => {
        const col = sceltaColore(g);
        const raccolta = h('input', { type: 'checkbox', checked: !!g.raccolta, 'aria-label': 'Raccolta: ' + g.nome, onchange: async (e) => {
          const r = await Api.patch('/server/api/gruppi/' + g.id, { raccolta: e.target.checked });
          if (!r.ok) { avviso(r.errore, 'errore'); e.target.checked = !e.target.checked; return; }
          avviso(e.target.checked ? g.nome + ': in una riga sola tra le raccolte.' : g.nome + ': mail per mail tra le importanti.', 'ok');
          ricarica();
        } });
        return h('li', { class: 'scheda-riga colonna gruppo-impostato ' + classeColore(g) },
          h('div', { class: 'riga' },
            h('div', { class: 'principale' },
              h('p', null, i < 9 ? h('kbd', null, String(i + 1)) : null, ' ', h('span', { class: 'pallino-gruppo', 'aria-hidden': 'true' }),
                h('span', { class: 'forte' }, g.nome), h('span', { class: 'tenue' }, ' · ' + NOMI_COLORI[col.attuale].toLowerCase())),
              h('p', { class: g.descrizione ? null : 'tenue' }, g.descrizione ? 'Ci vanno: ' + g.descrizione : 'Nessuna descrizione: Sol si basa solo sul nome.'),
              h('label', { class: 'spunta' }, raccolta, 'Raccolta (una riga sola)')),
            h('div', { class: 'azioni' },
              col.pulsante,
              h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: async () => {
                const t = await chiediTesto(sez, 'Che mail vanno in ' + g.nome + '? (una frase)', g.descrizione || '', 200);
                if (t == null) return;
                const r = await Api.patch('/server/api/gruppi/' + g.id, { descrizione: t });
                if (!r.ok) avviso(r.errore, 'errore');
                ricarica();
              } }, 'Descrizione'),
              h('button', { type: 'button', class: 'pulsante testo piccolo', 'aria-label': 'Sposta su', disabled: i === 0 ? true : null, onclick: () => scambia(i, i - 1) }, 'Su'),
              h('button', { type: 'button', class: 'pulsante testo piccolo', 'aria-label': 'Sposta giù', disabled: i === S.gruppi.length - 1 ? true : null, onclick: () => scambia(i, i + 1) }, 'Giù'),
              h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: async () => {
                const t = await chiediTesto(sez, 'Nuovo nome per ' + g.nome, g.nome);
                if (t == null) return;
                const r = await Api.patch('/server/api/gruppi/' + g.id, { nome: t });
                if (!r.ok) avviso(r.errore, 'errore');
                ricarica();
              } }, 'Rinomina'),
              g.nome === 'Altro' ? null : conConferma('Togli', 'Le sue mail passano ad Altro. Togliere il gruppo?', 'Sì, togli', async () => {
                const r = await Api.elimina('/server/api/gruppi/' + g.id);
                if (!r.ok) avviso(r.errore, 'errore');
                ricarica();
              }, 'pulsante testo piccolo'))),
          col.riquadro);
      })),
      h('h3', null, 'Gruppo nuovo'),
      h('div', { class: 'campo' }, h('label', { for: 'nuovo-gruppo' }, 'Nome'), nuovo),
      h('div', { class: 'campo' }, h('label', { for: 'nuovo-gruppo-descrizione' }, 'Che mail ci vanno? (una frase)'), descrizione),
      h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante tenue', disabled: pieni ? true : null, onclick: aggiungi }, 'Aggiungi gruppo')),
      pieni ? messaggioInLinea('Puoi avere al massimo ' + MAX_GRUPPI + ' gruppi: togline uno prima di aggiungerne un altro.', 'info') : null,
      esito);
  }

  async function impRegole(sez) {
    svuota(sez, h('p', { class: 'vuoto' }, 'Carico…'));
    const r = await Api.get('/server/api/regole');
    if (!sez.isConnected) return;
    const regole = r.ok && Array.isArray(r.dati) ? r.dati : [];
    const nomeGruppo = (id) => (S.gruppi.find((g) => g.id === id) || {}).nome || 'un gruppo tolto';
    svuota(sez,
      r.ok ? null : messaggioInLinea(r.errore),
      regole.length === 0 ? h('p', { class: 'vuoto' }, 'Nessun mittente ricordato finora.') : h('ul', { class: 'elenco-schede' }, regole.map((x) => h('li', { class: 'scheda-riga' },
        h('div', { class: 'principale' },
          h('p', null, (x.tipo === 'dominio' ? 'Tutte le mail da ' : 'Le mail di '), h('span', { class: 'forte' }, x.valore), ' vanno in ', h('span', { class: 'forte' }, nomeGruppo(x.gruppo_id))),
          h('p', { class: 'tenue' }, (x.origine === 'imparata' ? 'Imparata da una tua correzione' : 'Aggiunta a mano') + (x.creata_il ? ', ' + L.dataLunga(x.creata_il) : ''))),
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: async () => {
          const y = await Api.elimina('/server/api/regole/' + x.id);
          if (!y.ok) { avviso(y.errore, 'errore'); return; }
          avviso('Tolto.', 'ok');
          impRegole(sez);
        } }, 'Togli'))))));
  }

  async function impComputer(sez) {
    await leggiStato();
    if (!sez.isConnected) return;
    const m = S.motore || {};
    const avvio = h('input', { type: 'checkbox', id: 'imp-avvio', checked: !!m.avvio_al_login, disabled: m.avvio_disponibile ? null : true, onchange: async (e) => {
      const r = await Api.post('/locale/avvio-login', { attivo: e.target.checked });
      if (!r.ok) { avviso(r.errore, 'errore'); e.target.checked = !e.target.checked; return; }
      avviso(e.target.checked ? 'Sol si aprirà all\'accensione del computer.' : 'Sol non si aprirà più da solo.', 'ok');
      await leggiStato();
      disegnaImpostazioni();
    } });
    svuota(sez,
      h('dl', { class: 'intestazioni' },
        h('dt', null, 'Nome'), h('dd', null, m.nome_dispositivo || ''),
        h('dt', null, 'Server'), h('dd', null, (m.server || '') + (L.senzaProtezione(m.server) ? ' (collegamento di casa non protetto)' : '')),
        h('dt', null, 'Ultimo contatto'), h('dd', null, m.ultimo_contatto ? L.dataLunga(m.ultimo_contatto) : 'non ancora')),
      h('label', { class: 'interruttore' }, avvio, 'Apri Sol quando accendo il computer'),
      h('p', { class: 'spiega' }, 'Chiudendo la finestra Sol resta acceso vicino all\'orologio e manda le notifiche. Per chiuderlo del tutto: tasto destro sull\'icona, "Chiudi Sol".'));
  }

  /** Scollega questo computer: revoca sul server e pulizia dei dati qui. Se il server non risponde, lo dice. */
  async function scollegaQuesto() {
    if (!(await puoLasciare())) return;
    const r = await Api.post('/locale/scollega');
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    if (S.velo) S.velo.chiudi();
    await leggiStato();
    mostraAbbinamento(r.dati && r.dati.avviso ? r.dati.avviso : 'Computer scollegato.');
  }

  async function impDispositivi(sez) {
    svuota(sez, h('p', { class: 'vuoto' }, 'Carico…'));
    const r = await Api.get('/server/api/dispositivi');
    if (!sez.isConnected) return;
    const lista = r.ok && Array.isArray(r.dati) ? r.dati : [];
    const nostro = S.motore && S.motore.dispositivo_id;
    svuota(sez,
      r.ok ? null : messaggioInLinea(r.errore),
      h('ul', { class: 'elenco-schede' }, lista.map((d) => {
        const questo = nostro != null && d.id === nostro;
        return h('li', { class: 'scheda-riga' },
          h('div', { class: 'principale' },
            h('p', null, h('span', { class: 'forte' }, d.nome || 'Dispositivo'), h('span', { class: 'tenue' }, ' · ' + (d.tipo === 'android' ? 'telefono' : 'computer') + (questo ? ' (questo computer)' : ''))),
            h('p', { class: 'tenue' }, 'Collegato ' + L.dataLunga(d.abbinato_il) + (d.ultimo_accesso ? ' · ultimo accesso ' + L.oraBreve(d.ultimo_accesso) : ''))),
          h('div', { class: 'azioni' }, questo
            // Scollegare QUESTO computer pulisce anche i dati qui, come "Scollega questo computer".
            ? conConferma('Scollega', 'Questo computer non vedrà più la posta finché non lo ricolleghi. Scollegarlo?', 'Sì, scollega', scollegaQuesto)
            : conConferma('Scollega', 'Questo dispositivo non vedrà più la posta. Scollegarlo?', 'Sì, scollega', async () => {
              const x = await Api.elimina('/server/api/dispositivi/' + d.id);
              if (!x.ok) { avviso(x.errore, 'errore'); return; }
              avviso('Dispositivo scollegato.', 'ok');
              impDispositivi(sez);
            })));
      })));
  }

  async function impVersione(sez) {
    await leggiStato();
    if (!sez.isConnected) return;
    const m = S.motore || {};
    const a = m.aggiornamento || {};
    let corpo;
    if (a.scaricamento_in_corso) {
      corpo = h('p', { class: 'attesa' }, 'Sol sta scaricando la versione ' + (a.disponibile || 'nuova') + ' e ne controlla l\'impronta…');
      setTimeout(() => impVersione(sez), 2000);
    } else if (a.scaricato) {
      corpo = [h('p', null, 'Versione ' + (a.disponibile || 'nuova') + ' scaricata e controllata: ', h('span', { class: 'forte' }, a.scaricato)), passiAggiornamento()];
    } else if (a.disponibile) {
      corpo = [
        a.errore_scaricamento ? messaggioInLinea(a.errore_scaricamento) : null,
        h('p', null, 'È uscita la versione ' + a.disponibile + '.'),
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante pieno', onclick: () => scaricaAggiornamento(sez) }, a.errore_scaricamento ? 'Riprova a scaricarla' : 'Scaricala')),
        passiAggiornamento(),
      ];
    } else {
      corpo = h('p', { class: 'tenue' }, a.attivo ? 'Questa è l\'ultima versione.' : 'Il controllo delle versioni nuove vale per il programma installato dallo zip.');
    }
    svuota(sez,
      h('dl', { class: 'intestazioni' },
        h('dt', null, 'Programma'), h('dd', null, 'Sol ' + (m.versione || '')),
        h('dt', null, 'Server'), h('dd', null, (S.salute && S.salute.versione) || 'non noto')),
      corpo);
  }

  function passiAggiornamento() {
    return h('ol', { class: 'passi' },
      h('li', null, 'Premi "Scaricala": Sol scarica sol-pc.zip dal server di casa nella cartella Download e controlla che sia il file giusto (se non lo è, lo scarta).'),
      h('li', null, 'Chiudi Sol: tasto destro sull\'icona vicino all\'orologio, "Chiudi Sol".'),
      h('li', null, 'Scompatta lo zip nella stessa cartella di prima, sostituendo i file.'),
      h('li', null, 'Riapri Sol: i dati e il collegamento restano.'));
  }

  async function scaricaAggiornamento(sez) {
    const r = await Api.post('/locale/scarica-aggiornamento');
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    avviso('Sol scarica la versione nuova e controlla il file: quando ha finito, te lo dice.', 'info');
    if (sez) impVersione(sez);
  }

  // --- Tutorial interattivo (§10): stile dell'app, elementi veri su dati finti, niente server --------

  const PASSI_TUTORIAL = 6;
  const FINTO = {
    punto: 'Rossi aspetta l\'offerta per il capannone di Bassano. Il Comune chiede di integrare la pratica 114.',
    risposte: {
      base: 'Buongiorno Mario,\ngrazie per la richiesta. Le mando l\'offerta per il capannone di Bassano entro giovedì, così ha un giorno di margine.\n\nCordiali saluti',
      breve: 'Buongiorno Mario,\nl\'offerta per il capannone arriva entro giovedì.\n\nCordiali saluti',
      formale: 'Gentile sig. Rossi,\nla ringrazio per la richiesta. Le invierò l\'offerta relativa al capannone di Bassano entro giovedì.\n\nCordiali saluti',
      tempo: 'Buongiorno Mario,\nper preparare bene l\'offerta mi servono due giorni in più: gliela mando entro martedì. Le va bene?\n\nCordiali saluti',
    },
    righe: [
      { id: 1, iniziali: 'MR', colore: 'blu', nome: 'Mario Rossi', ora: '08:12', oggetto: 'Offerta capannone Bassano', riassunto: 'Chiede l\'offerta entro venerdì' },
      { id: 2, iniziali: 'LB', colore: 'blu', nome: 'Laura Bianchi', ora: '07:40', oggetto: 'Conferma sopralluogo giovedì', riassunto: 'Va bene giovedì alle 10 in via Roma' },
      { id: 3, iniziali: 'FN', colore: 'verde', nome: 'Ferramenta Neri', ora: 'ieri', oggetto: 'Preventivo compressore', riassunto: '2.400 euro più IVA' },
    ],
    gruppi: [{ nome: 'Clienti', colore: 'blu' }, { nome: 'Fornitori', colore: 'verde' }, { nome: 'Amministrazione', colore: 'ambra' }],
  };

  /** Si apre da solo al primo avvio (dopo l'abbinamento) e da Impostazioni > "Come funziona Sol". */
  function apriTutorial(primoAvvio) {
    if (S.tutorial) return;
    if (S.velo) S.velo.chiudi();
    S.tutorial = { passo: 0, primoAvvio: !!primoAvvio, stato: {} };
    S.dom.tutorial = h('div', { class: 'tutorial', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Come funziona Sol' });
    document.body.append(S.dom.tutorial);
    disegnaTutorial();
  }

  /** Chiude il tutorial; `segna` (di base sì) dice al programma che è stato visto: al prossimo avvio non riparte. */
  function chiudiTutorial(segna) {
    if (!S.tutorial) return;
    clearTimeout(S.tutorial.timer);
    if (S.dom.tutorial) S.dom.tutorial.remove();
    S.dom.tutorial = null;
    S.tutorial = null;
    if (segna !== false) {
      Api.post('/locale/tutorial-visto', {}).then((r) => { if (r.ok && S.motore) S.motore.tutorial_visto = true; });
    }
  }

  function avantiTutorial() {
    if (!S.tutorial) return;
    clearTimeout(S.tutorial.timer);
    S.tutorial.passo = Math.min(PASSI_TUTORIAL - 1, S.tutorial.passo + 1);
    S.tutorial.stato = {};
    disegnaTutorial();
  }

  function dopoUnPo(fai, ms) {
    const t = S.tutorial;
    if (!t) return;
    clearTimeout(t.timer);
    t.timer = setTimeout(() => { if (S.tutorial === t) fai(); }, conMovimenti() ? ms : Math.min(ms, 600));
  }

  function disegnaTutorial() {
    const t = S.tutorial;
    if (!t || !S.dom.tutorial) return;
    const [frase, scena] = [scenaOggi, scenaRisposta, scenaTasti, scenaSmista, scenaTruffa, scenaFine][t.passo](t.stato);
    svuota(S.dom.tutorial,
      h('div', { class: 'tutorial-alto' },
        h('div', { class: 'marchio' }, sole(), 'Sol'),
        t.passo < PASSI_TUTORIAL - 1 ? h('button', { type: 'button', class: 'pulsante testo', onclick: () => chiudiTutorial(true) }, 'Salta') : h('span')),
      h('div', { class: 'tutorial-corpo' },
        h('p', { class: 'tutorial-frase', 'aria-live': 'polite' }, frase),
        h('div', { class: 'tutorial-scena', 'data-passo': String(t.passo) }, scena)),
      h('div', { class: 'tutorial-basso' },
        h('div', { class: 'pallini-avanzamento', role: 'img', 'aria-label': 'Passo ' + (t.passo + 1) + ' di ' + PASSI_TUTORIAL },
          Array.from({ length: PASSI_TUTORIAL }, (_, i) => h('span', { class: i === t.passo ? 'attivo' : null })))));
    const fuoco = S.dom.tutorial.querySelector('[data-fuoco]');
    if (fuoco) fuoco.focus();
  }

  function voceFinta(cerchio, titolo, sotto, pulsante, stile, fai) {
    return h('div', { class: 'voce-oggi' }, cerchio,
      h('div', { class: 'testi' }, h('div', { class: 'titolo-voce' }, titolo), h('div', { class: 'sotto-voce' }, sotto)),
      h('button', { type: 'button', class: 'pulsante ' + stile, 'data-fuoco': fai ? '' : null, onclick: fai || null }, pulsante));
  }

  function scenaOggi() {
    return ['Ogni mattina qui trovi il punto della giornata e cosa fare.', [
      h('section', { class: 'scheda' }, h('span', { class: 'etichetta' }, 'Il punto di oggi'), h('p', { class: 'testo-punto' }, FINTO.punto)),
      h('div', { class: 'elenco-voci' },
        voceFinta(h('span', { class: 'cerchio col-blu', 'aria-hidden': 'true' }, 'MR'), 'Rispondi a Mario Rossi', 'Offerta capannone · risposta pronta', 'Rivedi', 'pieno', avantiTutorial),
        voceFinta(h('span', { class: 'cerchio neutro', 'aria-hidden': 'true' }, icona('strati')), 'Smista 3 mail', 'Sol non è sicuro del gruppo', 'Inizia', 'tenue', null)),
    ]];
  }

  function scenaRisposta(st) {
    const scegli = (chiave) => { if (st.inviata) return; st.tono = st.tono === chiave ? null : chiave; disegnaTutorial(); };
    const pillola = (chiave, etichetta) => h('button', { type: 'button', class: 'pillola-tono', 'data-tono': chiave, 'aria-pressed': st.tono === chiave ? 'true' : 'false', onclick: () => scegli(chiave) }, etichetta);
    const frase = st.inviata ? 'Fatto. Qui è tutto finto: non è partito niente.' : st.tono ? 'Ora inviala.' : 'Sol ti prepara la risposta. Cambiale il tono con un clic.';
    return [frase, [
      h('div', { class: 'testa-mail col-blu' }, h('span', { class: 'avatar grande', 'aria-hidden': 'true' }, 'MR'),
        h('div', { class: 'chi' }, h('span', { class: 'nome' }, 'Mario Rossi'), h('span', { class: 'dove' }, 'rossi@esempio.it · oggi 08:12'))),
      st.inviata
        ? h('div', { class: 'risposta-sol risposta-inviata' }, h('span', { class: 'cerchio-spunta', 'aria-hidden': 'true' }, icona('spunta')),
          h('div', { class: 'testi' }, h('p', { class: 'titolo-voce' }, 'Risposta inviata')))
        : h('div', { class: 'risposta-sol' },
          h('div', { class: 'testa-risposta' }, h('span', { class: 'titolo-risposta' }, sole(), 'Risposta di Sol'), h('span', { class: 'destinatario' }, 'a rossi@esempio.it')),
          h('p', { class: 'corpo-mail testo-finto' }, FINTO.risposte[st.tono || 'base']),
          h('div', { class: 'pillole-tono' }, pillola('breve', 'Più breve'), pillola('formale', 'Più formale'), pillola('tempo', 'Chiedi più tempo'))),
      st.inviata ? null : h('button', { type: 'button', class: 'pulsante pieno grande', disabled: st.tono ? null : true, 'data-fuoco': st.tono ? '' : null, onclick: () => {
        st.inviata = true;
        disegnaTutorial();
        dopoUnPo(avantiTutorial, 1100);
      } }, 'Invia risposta', icona('freccia')),
    ]];
  }

  function rigaFinta(r, selezionata, onclick) {
    return h('li', { class: 'mail col-' + r.colore + (selezionata ? ' selezionata' : '') + (r.uscita ? ' uscita' : ''), 'data-id': String(r.id), onclick },
      h('span', { class: 'avatar', 'aria-hidden': 'true' }, r.iniziali),
      h('span', { class: 'contenuto-mail' },
        h('span', { class: 'riga-1' }, h('span', { class: 'mittente' }, r.nome), h('span', { class: 'ora' }, r.ora)),
        h('span', { class: 'oggetto' }, r.oggetto),
        h('span', { class: 'riga-3' }, h('span', { class: 'riassunto-breve' }, r.riassunto), r.truffa ? h('span', { class: 'segno truffa' }, icona('triangolo'), 'Possibile truffa') : null)));
  }

  function scenaTasti(st) {
    if (!st.righe) { st.righe = FINTO.righe.map((r) => Object.assign({}, r)); st.sel = 0; st.fase = 'frecce'; }
    const frase = st.fase === 'frecce' ? 'Con le frecce passi da una mail all\'altra.'
      : st.fase === 'numero' ? 'Premi un numero per spostarla in un gruppo.' : 'Spostata. Sol se lo ricorda per le prossime dello stesso mittente.';
    return [frase, [
      h('ul', { class: 'gruppo-righe' }, st.righe.map((r, i) => rigaFinta(r, i === st.sel, () => { if (st.fase === 'fatto') return; st.sel = i; st.fase = 'numero'; disegnaTutorial(); }))),
      st.fase === 'frecce'
        ? h('div', { class: 'tutorial-tasti' }, h('kbd', null, '↑'), h('kbd', null, '↓'))
        : st.fase === 'numero'
          ? h('div', { class: 'tutorial-tasti' }, FINTO.gruppi.map((g, i) => h('button', { type: 'button', class: 'pulsante-gruppo col-' + g.colore, onclick: () => tutorialSposta(i) },
            h('kbd', null, String(i + 1)), h('span', { class: 'nome' }, g.nome))))
          : h('div', { class: 'toast-finto', role: 'status' }, 'Spostata in ' + st.dove, h('button', { type: 'button' }, 'Annulla')),
    ]];
  }

  function tutorialSposta(i) {
    const st = S.tutorial && S.tutorial.stato;
    if (!st || st.fase !== 'numero') return;
    st.righe[st.sel].uscita = true;
    st.dove = FINTO.gruppi[i].nome;
    st.fase = 'fatto';
    disegnaTutorial();
    dopoUnPo(avantiTutorial, 1600);
  }

  function scenaSmista(st) {
    const vaBeneFinto = (verso) => {
      if (st.fatto) return;
      st.fatto = verso;
      disegnaTutorial();
      dopoUnPo(avantiTutorial, 450);
    };
    st.vaBene = () => vaBeneFinto('destra');
    st.cambia = () => { if (!st.fatto) { st.gruppi = !st.gruppi; disegnaTutorial(); } };
    st.scegli = () => vaBeneFinto('sinistra');
    return ['Quando Sol non è sicuro, decidi tu: Invio va bene, freccia a sinistra cambi.', [
      h('article', { class: 'scheda-grande' + (st.fatto === 'destra' ? ' esce-destra' : st.fatto === 'sinistra' ? ' esce-sinistra' : '') },
        h('div', { class: 'chi' }, h('span', { class: 'avatar', 'aria-hidden': 'true' }, 'FN'),
          h('div', { class: 'testi' }, h('span', { class: 'nome' }, 'Ferramenta Neri'), h('span', { class: 'indirizzo' }, 'ordini@neri.esempio.it')),
          h('span', { class: 'ora-smista' }, 'ieri')),
        h('h2', null, 'Preventivo compressore'),
        h('p', { class: 'riassunto-smista' }, 'Manda il preventivo del compressore: 2.400 euro più IVA, consegna in due settimane.'),
        h('span', { class: 'spazio' }),
        h('div', { class: 'separatore' }),
        h('span', { class: 'etichetta' }, 'Sol propone'),
        h('div', { class: 'proposta' }, h('span', { class: 'pillola-gruppo col-verde' }, h('span', { class: 'pallino', 'aria-hidden': 'true' }), 'Fornitori'),
          h('span', { class: 'sicurezza' }, 'abbastanza sicuro'))),
      st.gruppi
        ? h('div', { class: 'griglia-gruppi' }, FINTO.gruppi.map((g, i) => h('button', { type: 'button', class: 'pulsante-gruppo col-' + g.colore, onclick: st.scegli },
          h('span', { class: 'pallino', 'aria-hidden': 'true' }), h('span', { class: 'nome' }, g.nome), h('kbd', null, String(i + 1)))))
        : h('div', { class: 'azioni-smista' },
          h('button', { type: 'button', class: 'pulsante bianco', onclick: st.cambia }, icona('indietro'), 'Cambia', h('kbd', { 'aria-hidden': 'true' }, '←')),
          h('button', { type: 'button', class: 'pulsante pieno', onclick: st.vaBene, 'data-fuoco': '' }, 'Va bene', icona('spunta'), h('kbd', { 'aria-hidden': 'true' }, '→'))),
    ]];
  }

  function scenaTruffa() {
    return ['Se vedi questo, telefona al mittente a un numero che conosci già prima di pagare.', [
      h('ul', { class: 'gruppo-righe' }, rigaFinta({ id: 9, iniziali: 'FN', colore: 'ambra', nome: 'Ferramenta Neri', ora: '08:40', oggetto: 'Nuovo IBAN per i pagamenti', riassunto: 'Chiede di pagare su un conto nuovo', truffa: true }, false, null)),
      h('div', { class: 'riquadro truffa' }, h('p', { class: 'testo-truffa' }, AVVISO_TRUFFA)),
      h('button', { type: 'button', class: 'pulsante pieno grande', 'data-fuoco': '', onclick: avantiTutorial }, 'Ho capito'),
    ]];
  }

  function scenaFine() {
    return ['Sei pronto.', [
      h('div', { class: 'tutto-fatto' }, h('span', { class: 'cerchio-spunta', 'aria-hidden': 'true' }, icona('spunta'))),
      h('button', { type: 'button', class: 'pulsante pieno grande', 'data-fuoco': '', onclick: () => { chiudiTutorial(true); vaiVista('oggi'); } }, 'Inizia'),
    ]];
  }

  /** I tasti nel tutorial: si provano davvero (frecce, numeri, Invio), su dati finti. */
  function tastoTutorial(e) {
    const t = S.tutorial;
    if (!t || e.repeat) return;
    const k = e.key;
    if (k === 'Escape') { e.preventDefault(); chiudiTutorial(true); return; }
    const st = t.stato;
    if (t.passo === 2) {
      if (st.fase === 'frecce' || st.fase === 'numero') {
        if (k === 'ArrowDown' || k === 'j') { e.preventDefault(); st.sel = Math.min(st.righe.length - 1, st.sel + 1); st.fase = 'numero'; disegnaTutorial(); return; }
        if (k === 'ArrowUp' || k === 'k') { e.preventDefault(); st.sel = Math.max(0, st.sel - 1); st.fase = 'numero'; disegnaTutorial(); return; }
      }
      if (st.fase === 'numero' && /^[1-3]$/.test(k)) { e.preventDefault(); tutorialSposta(Number(k) - 1); }
      return;
    }
    if (t.passo === 3) {
      if (k === 'Enter' || k === 'ArrowRight') { e.preventDefault(); if (!st.gruppi) st.vaBene(); return; }
      if (k === 'ArrowLeft') { e.preventDefault(); st.cambia(); return; }
      if (st.gruppi && /^[1-3]$/.test(k)) { e.preventDefault(); st.scegli(); }
    }
  }

  // --- Quello che il programma chiede alla pagina ------------------------------------------------

  // Il clic su una notifica di Windows porta la finestra su una mail o una sezione.
  window.solApri = (frammento) => { vaiAlFrammento(frammento); };

  // Prima di chiudere la finestra (o il programma): si salvano le risposte aperte e si dice com'è andata.
  window.solSalvaPrimaDiChiudere = () => {
    const editori = editoriAperti();
    if (!editori.some((ed) => ed.sporca || ed.salvando)) return 'pulito';
    const stato = { finito: false };
    S.chiusura = stato;
    Promise.all(editori.map((ed) => ed.salvaSeServe())).then(() => { stato.finito = true; }, () => { stato.finito = true; });
    return 'in_corso';
  };

  window.solStatoSalvataggio = () => {
    if (S.chiusura && !S.chiusura.finito) return 'in_corso';
    return editoriAperti().some((ed) => ed.sporca) ? 'fallito' : 'pulito';
  };

  avvia();
})();
