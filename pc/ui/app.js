/*
 * Sol per il computer: l'interfaccia.
 *
 * Una finestra sul server di casa: qui non si decide niente sulla posta, si mostra
 * quello che il server sa e si mandano i gesti dell'utente. Parla SOLO col programma,
 * attraverso api.js (/locale/* e /server/*).
 *
 * Regole:
 *  - tutto il testo che arriva dalle mail entra nella pagina come testo, mai come codice;
 *  - l'invio passa sempre da /locale/invia, dove il programma chiede la conferma con una
 *    finestra di Windows che mostra destinatari, oggetto e prima riga DELLA VERSIONE che parte;
 *  - ogni riquadro di bozza lavora solo sulla sua bozza (niente stato condiviso tra riquadri);
 *  - smistare a mano deve essere rapidissimo ma mai pericoloso: un'azione alla volta, niente
 *    ripetizione automatica dei tasti, niente scorciatoie con il fuoco in un campo di testo, e la
 *    pagina non cambia mail da sola se nel frattempo Manuel ha fatto altro.
 */
(function () {
  'use strict';

  const L = window.SolLogica;
  const ApiBase = window.SolApi;
  // I tempi si possono accorciare solo nei test (window.SolTempi): nel programma valgono questi.
  const T = Object.assign({ minuto: 60000, bozza: 2000, salva: 1500, outlook: 5000, riassunto: 2000 }, window.SolTempi || {});
  const MAX_SENZA_CONFERMA = 5;
  const MAX_GRUPPI = 16;
  const ESEMPIO_SERVER = 'https://sol.esempio.ts.net';
  /** I 12 colori dei gruppi a parole (contratto §17): il colore non è mai l'unica informazione. */
  const NOMI_COLORI = {
    blu: 'Blu', verde: 'Verde', ambra: 'Ambra', viola: 'Viola', grigio: 'Grigio', ardesia: 'Ardesia',
    petrolio: 'Petrolio', rosa: 'Rosa', indaco: 'Indaco', oliva: 'Oliva', marrone: 'Marrone', ciano: 'Ciano',
  };

  const S = {
    motore: null,             // GET /locale/stato
    salute: null,             // GET /server/api/salute
    conteggi: null,           // GET /server/api/conteggi (§15.4)
    gruppi: [],               // GET /server/api/gruppi, già ordinati
    vista: 'posta',
    filtro: { tipo: 'tutte' },
    cerca: '',
    elenco: { mail: [], altre: false, caricando: false, errore: null, giro: 0 },
    selezione: new Set(),
    ancora: null,
    attiva: null,
    aperta: null,             // { id, mail, caricando, errore, memoria }
    editor: null,             // il riquadro della bozza della mail aperta
    solleciti: new Map(),     // id della mail inviata -> riquadro del suo sollecito ("Niente resta indietro")
    ricorda: true,            // "ricordati questo mittente" acceso di base (vale per una mail alla volta)
    occupato: null,           // l'azione di smistamento in corso: finché non finisce, niente azioni nuove
    velo: null,               // la finestrella aperta sopra la pagina (conferma, scorciatoie)
    tastoInCampo: null,       // l'ultimo tasto battuto in un campo di testo: { el, t }
    chiusura: null,           // il salvataggio chiesto dal programma prima di chiudere
    memoriaDel: null,         // dati vecchi mostrati perché il server non risponde
    abbinamento: false,
    aggiornando: false,
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
        ridisegnaBarraPresto();
      } else if (prima !== S.memoriaDel) {
        ridisegnaBarraPresto();
      }
    }
    return r;
  }

  let barraInAttesa = false;
  function ridisegnaBarraPresto() {
    if (barraInAttesa) return;
    barraInAttesa = true;
    setTimeout(() => { barraInAttesa = false; renderBarra(); }, 0);
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
    posta: 'M3.5 6.5h17v11h-17z M3.5 7l8.5 6 8.5-6',
    rispondi: 'M9 7L4 12l5 5 M4 12h10a6 6 0 0 1 6 6',
    urgente: 'M12 4l9 16H3z M12 10v4 M12 17v.5',
    controlla: 'M4 12l5 5L20 6',
    riassunto: 'M5 5h14 M5 10h14 M5 15h9 M5 20h6',
    indietro: 'M12 4a8 8 0 1 0 8 8 M12 8v4l3 2 M20 4v4h-4',
    scadenze: 'M4 6.5h16v13H4z M4 10.5h16 M8.5 4v4 M15.5 4v4',
    impostazioni: 'M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5v4 M9 15v4',
    cerca: 'M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z M15.5 15.5L20 20',
    invia: 'M4 12l16-7-6 16-3-6z M11 15l9-10',
    aggiorna: 'M19.5 12a7.5 7.5 0 1 1-2.3-5.4 M19.5 4.5v4.2h-4.2',
    gruppo: 'M4 6.5h6l2 2h8v10H4z',
    allegato: 'M8 12.5l5.5-5.5a2.8 2.8 0 0 1 4 4L10 18.5a4.6 4.6 0 0 1-6.5-6.5L10 5.5',
    chiudi: 'M6 6l12 12 M18 6L6 18',
    rete: 'M3 9a13 13 0 0 1 18 0 M6 12.5a8.5 8.5 0 0 1 12 0 M9.5 16a3.5 3.5 0 0 1 5 0',
    tutte: 'M4 5h16v14H4z M4 14h4.5l1.5 2h4l1.5-2H20',
    campana: 'M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z M10 20.5h4',
    truffa: 'M12 3l8 3v5c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z M12 8v5 M12 16v.5',
    triangolo: 'M12 3.5l9.5 16.5h-19z M12 9.5v5 M12 17.3v.2',
    esclamativo: 'M12 4.5v10 M12 18.8v.2',
    interrogativo: 'M9 8.5a3 3 0 1 1 4.2 2.8c-.8.4-1.2 1-1.2 1.9v.8 M12 18.3v.2',
    matita: 'M4 20l1-4.5L15.5 5a2 2 0 0 1 3 3L8 18.5z M13.5 7l3 3',
    freccia: 'M5 12h14 M13 6l6 6-6 6',
    punti: 'M12 5.5v.4 M12 11.8v.4 M12 18.1v.4',
    giu: 'M7 10l5 5 5-5',
    spunta: 'M5 12.5l4.2 4.2L19 7',
  };

  // §16.3: il testo dell'avviso in cima a una mail segnalata, parola per parola.
  const AVVISO_TRUFFA = 'Attenzione: questa mail potrebbe essere una truffa. Prima di pagare, cambiare coordinate bancarie o mandare documenti, telefona al mittente a un numero che conosci già, non a quello scritto nella mail.';

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

  // --- Avvisi brevi, conferme sul posto, finestrelle ---------------------------------------------

  function avviso(testo, tipo) {
    if (!testo) return;
    const zona = document.getElementById('avvisi');
    const el = h('div', { class: 'avviso ' + (tipo || 'info'), role: tipo === 'errore' ? 'alert' : null }, testo);
    el.append(h('button', { class: 'avviso-chiudi', type: 'button', 'aria-label': 'Chiudi', onclick: () => el.remove() }, icona('chiudi')));
    zona.append(el);
    setTimeout(() => el.remove(), tipo === 'errore' ? 12000 : 5000);
  }

  /** Un pulsante che al primo clic chiede "Sei sicuro?" sul posto, senza finestre a comparsa. */
  function conConferma(etichetta, domanda, siEtichetta, azione, classe) {
    const contenitore = h('span', { class: 'con-conferma' });
    const iniziale = () => svuota(contenitore, h('button', { type: 'button', class: classe || 'pulsante secondario', onclick: chiedi }, etichetta));
    function chiedi() {
      svuota(contenitore,
        h('span', { class: 'domanda' }, domanda),
        h('button', { type: 'button', class: 'pulsante pericolo piccolo', onclick: async () => { svuota(contenitore, h('span', { class: 'attesa' }, 'Un momento…')); await azione(); if (contenitore.isConnected) iniziale(); } }, siEtichetta),
        h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: iniziale }, 'No'));
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
      const chiudi = (esito) => {
        if (chiusa) return;
        chiusa = true;
        velo.remove();
        S.velo = null;
        fine(esito);
      };
      const si = h('button', { type: 'button', class: 'pulsante ' + (o.pericolo ? 'pericolo' : 'primario'), onclick: () => chiudi(true) }, o.si);
      const no = h('button', { type: 'button', class: 'pulsante secondario', onclick: () => chiudi(false) }, o.no || 'Annulla');
      const velo = h('div', { class: 'velo', onclick: (e) => { if (e.target === velo) chiudi(false); } },
        h('div', { class: 'scheda finestrella', role: 'dialog', 'aria-modal': 'true', 'aria-label': o.titolo },
          h('h2', null, o.titolo),
          o.testo ? h('p', null, o.testo) : null,
          h('div', { class: 'azioni' }, si, no)));
      S.velo = { chiudi: () => chiudi(false) };
      document.body.append(velo);
      no.focus();
    });
  }

  function campoDiTesto(el) {
    return !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));
  }

  // --- Avvio -------------------------------------------------------------------------------

  async function avvia() {
    document.addEventListener('keydown', suTasto);
    document.addEventListener('click', (e) => {
      if (!e.target.closest || !e.target.closest('.ancora-menu')) chiudiFinestrelleMail();
    });
    window.addEventListener('hashchange', () => vaiAlFrammento(location.hash));
    await leggiStato();
    if (!S.motore || !S.motore.abbinato) {
      mostraAbbinamento();
    } else {
      costruisciScheletro();
      await Promise.all([leggiSalute(), leggiGruppi(), leggiConteggi()]);
      renderTutto();
      const frammento = location.hash;
      const d = frammento ? L.destinazioneDaFrammento(frammento) : null;
      // Una mail da aprire (clic su una notifica) si apre sopra l'elenco della posta, che si carica comunque.
      if (!d || d.mailId) await caricaElenco();
      if (frammento) await vaiAlFrammento(frammento);
    }
    document.getElementById('app').removeAttribute('aria-busy');
    setInterval(giroPeriodico, T.minuto);
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

  /** I contatori dei filtri e dei gruppi (§15.4). Un server di prima della v1.1 non li ha: niente contatori sui filtri. */
  async function leggiConteggi() {
    const r = await Api.get('/server/api/conteggi');
    if (r.ok && r.dati && typeof r.dati === 'object') S.conteggi = r.dati;
    else if (r.stato === 404 || r.stato === 405) S.conteggi = null;
  }

  async function aggiornaContatori() {
    await Promise.all([leggiGruppi(), leggiConteggi()]);
    renderMenu();
  }

  async function giroPeriodico() {
    if (S.abbinamento) return;
    await leggiStato();
    if (!S.motore || !S.motore.abbinato) return;
    await Promise.all([leggiSalute(), leggiGruppi(), leggiConteggi()]);
    renderBarra();
    renderMenu();
    if (S.vista === 'posta') await caricaElenco({ silenzioso: true });
  }

  // --- Abbinamento ---------------------------------------------------------------------------

  function mostraAbbinamento(messaggio) {
    S.abbinamento = true;
    distruggiEditori();
    const app = document.getElementById('app');
    const m = S.motore || {};
    const campi = {
      server: h('input', { type: 'text', id: 'ab-server', value: m.server || '', placeholder: ESEMPIO_SERVER, autocomplete: 'off', spellcheck: 'false' }),
      codice: h('input', { type: 'text', id: 'ab-codice', inputmode: 'numeric', maxlength: '9', placeholder: '8 cifre', autocomplete: 'off' }),
      nome: h('input', { type: 'text', id: 'ab-nome', value: m.nome_dispositivo || m.nome_suggerito || '', maxlength: '60' }),
      avvio: h('input', { type: 'checkbox', id: 'ab-avvio', checked: true }),
    };
    const esito = h('div', { class: 'esito' }, messaggioInLinea(messaggio, 'avvertenza'));
    const pulsante = h('button', { type: 'submit', class: 'pulsante primario grande' }, 'Collega questo computer');
    const modulo = h('form', {
      class: 'scheda abbinamento',
      onsubmit: async (e) => {
        e.preventDefault();
        if (pulsante.disabled) return;
        pulsante.disabled = true;
        svuota(esito, h('p', { class: 'attesa' }, 'Collegamento in corso…'));
        const r = await Api.post('/locale/abbina', {
          server: campi.server.value,
          codice: campi.codice.value,
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
        costruisciScheletro();
        await Promise.all([leggiSalute(), leggiGruppi(), leggiConteggi()]);
        renderTutto();
        await caricaElenco();
        avviso('Computer collegato. Da qui in avanti Sol si apre già pronto.', 'ok');
      },
    },
      h('div', { class: 'marchio-grande' }, h('span', { class: 'sole', 'aria-hidden': 'true' }), 'Sol'),
      h('h1', null, 'Colleghiamo questo computer'),
      h('p', { class: 'spiega' }, 'Sol lavora sul server di casa. Questo programma è una finestra su quel server: per collegarlo servono il suo indirizzo e un codice di 8 cifre, che si crea sul server e vale 15 minuti.'),
      h('label', { for: 'ab-server' }, 'Indirizzo del server'),
      campi.server,
      h('p', { class: 'aiuto' }, 'Di solito è l\'indirizzo di Tailscale, per esempio ' + ESEMPIO_SERVER + '. Solo dentro casa si può usare anche quello di rete, per esempio http://192.168.1.51:8200.'),
      h('label', { for: 'ab-codice' }, 'Codice di abbinamento'),
      campi.codice,
      h('label', { for: 'ab-nome' }, 'Nome di questo computer'),
      campi.nome,
      h('p', { class: 'aiuto' }, 'Serve a riconoscerlo nell\'elenco dei dispositivi collegati.'),
      m.avvio_disponibile ? h('label', { class: 'spunta' }, campi.avvio, ' Apri Sol quando accendo il computer (così le notifiche arrivano sempre)') : null,
      esito,
      pulsante);
    svuota(app, h('div', { class: 'pagina-abbinamento' }, modulo));
    (m.server ? campi.codice : campi.server).focus();
  }

  // --- Scheletro --------------------------------------------------------------------------

  function costruisciScheletro() {
    const app = document.getElementById('app');
    S.dom.barra = h('header', { class: 'barra' });
    S.dom.strisce = h('div', { class: 'strisce' });
    S.dom.menu = h('nav', { class: 'menu', 'aria-label': 'Sezioni' });
    S.dom.area = h('main', { class: 'area' });
    svuota(app, S.dom.barra, S.dom.strisce, h('div', { class: 'corpo' }, S.dom.menu, S.dom.area));
  }

  function renderTutto() {
    renderBarra();
    renderMenu();
    renderArea();
  }

  function minutiDa(iso) {
    const t = new Date(iso).getTime();
    return isNaN(t) ? 0 : Math.max(0, Math.round((Date.now() - t) / 60000));
  }

  function renderBarra() {
    if (!S.dom.barra || S.abbinamento) return;
    const m = S.motore || {};
    const sa = S.salute || {};
    let statoRete;
    if (m.non_riconosciuto) statoRete = h('span', { class: 'stato-rete male' }, 'Computer non più collegato');
    else if (m.raggiungibile === false || S.memoriaDel) statoRete = h('span', { class: 'stato-rete male' }, icona('rete'), 'Server non raggiungibile');
    else statoRete = h('span', { class: 'stato-rete bene' }, icona('rete'), 'Collegato al server di casa');
    S.dom.aggiorna = h('button', { type: 'button', class: 'pulsante testo', title: 'Ricarica (F5)', disabled: S.aggiornando ? true : null, onclick: ricaricaTutto }, icona('aggiorna'), 'Aggiorna');
    svuota(S.dom.barra,
      h('div', { class: 'marchio' }, h('span', { class: 'sole', 'aria-hidden': 'true' }), 'Sol'),
      statoRete,
      m.senza_protezione || L.senzaProtezione(m.server) ? h('span', { class: 'nota-rete', title: 'Il programma parla col server con un indirizzo http di casa: va bene in casa, ma per le password serve l\'indirizzo https di Tailscale.' }, 'Collegamento di casa non protetto') : null,
      h('span', { class: 'spazio' }),
      S.dom.aggiorna);

    const strisce = [];
    if (m.non_riconosciuto) {
      strisce.push(h('div', { class: 'striscia pericolo fissa' },
        h('span', null, 'Il server di casa non riconosce più questo computer: le mail e le notifiche non arrivano. Forse è stato scollegato dalle impostazioni.'),
        h('button', { type: 'button', class: 'pulsante piccolo primario', onclick: () => mostraAbbinamento('Collega di nuovo questo computer con un codice nuovo.') }, 'Collega di nuovo')));
    } else if (m.raggiungibile === false || S.memoriaDel) {
      const da = m.irraggiungibile_dal ? minutiDa(m.irraggiungibile_dal) : 0;
      const quanto = da >= 2 ? ' da ' + da + ' minuti' : '';
      const quando = S.memoriaDel ? ' Stai vedendo gli ultimi dati arrivati (' + L.dataLunga(S.memoriaDel) + ').' : '';
      strisce.push(h('div', { class: 'striscia avvertenza fissa' }, 'Sol non riesce a raggiungere il server di casa' + quanto + '.' + quando + ' Riprova da solo ogni minuto.'));
    }
    // La modalità prova ha la sua striscia sottile in cima alla Posta (docs/aspetto.md, §2).
    if (S.dom.prova) S.dom.prova.hidden = !sa.modalita_prova;
    if (sa.scrittore && sa.scrittore.stato === 'non_disponibile') {
      strisce.push(h('div', { class: 'striscia avvertenza' }, 'In questo momento Sol non riesce a scrivere bozze e riassunti. Le mail arrivano e si smistano lo stesso.'));
    }
    if (sa.decisore && sa.decisore.stato === 'in_avvio') {
      const pct = typeof sa.decisore.percentuale === 'number' ? ' (' + Math.round(sa.decisore.percentuale) + '%)' : '';
      strisce.push(h('div', { class: 'striscia info' }, 'Sol si sta avviando sul server' + pct + ': lo smistamento delle mail nuove riparte tra poco.'));
    } else if (sa.decisore && sa.decisore.stato === 'non_disponibile') {
      strisce.push(h('div', { class: 'striscia avvertenza' }, 'Sol in questo momento non riesce a smistare le mail nuove: arrivano lo stesso, restano "non ancora smistate".'));
    }
    if (m.aggiornamento && m.aggiornamento.disponibile) {
      strisce.push(h('div', { class: 'striscia info' },
        h('span', null, 'È uscita la versione ' + m.aggiornamento.disponibile + ' di Sol.'),
        h('button', { type: 'button', class: 'pulsante piccolo testo', onclick: mostraVersione }, 'Vedi come aggiornare')));
    }
    svuota(S.dom.strisce, strisce);
  }

  async function ricaricaTutto() {
    if (S.aggiornando) return;
    // Le viste con riquadri di bozza si ridisegnano: prima si salva quello che si sta scrivendo.
    if (S.vista !== 'posta' && !(await puoLasciare())) return;
    S.aggiornando = true;
    if (S.dom.aggiorna) S.dom.aggiorna.disabled = true;
    try {
      await Api.post('/locale/aggiorna-adesso');
      await Promise.all([leggiStato(), leggiSalute(), leggiGruppi(), leggiConteggi()]);
      renderBarra();
      renderMenu();
      if (S.vista === 'posta') await caricaElenco({ silenzioso: true });
      else {
        distruggiSolleciti();
        renderArea();
      }
    } finally {
      S.aggiornando = false;
      if (S.dom.aggiorna) S.dom.aggiorna.disabled = false;
    }
  }

  // --- Menu a sinistra -----------------------------------------------------------------------

  function contatore(n, piu) {
    if (!n) return null;
    return h('span', { class: 'contatore' }, String(n) + (piu ? '+' : ''));
  }

  function voceMenu(attiva, nomeIcona, titolo, cont, azione) {
    return h('li', null, h('button', {
      type: 'button',
      class: 'voce' + (attiva ? ' attiva' : ''),
      'aria-current': attiva ? 'page' : null,
      onclick: azione,
    }, icona(nomeIcona), h('span', { class: 'etichetta' }, titolo), cont));
  }

  /** La classe del colore di un gruppo (il server manda il nome; fuori elenco → ardesia). */
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

  function daLeggereGruppo(g) {
    const c = S.conteggi && S.conteggi.gruppi && S.conteggi.gruppi[String(g.id)];
    return c && typeof c.da_leggere === 'number' ? c.da_leggere : g.da_leggere;
  }

  function renderMenu() {
    if (!S.dom.menu) return;
    const inPosta = S.vista === 'posta';
    const c = S.conteggi;
    // "Da fare" in cima (docs/aspetto.md, §2): piastrelle che sono filtri, nascoste quando sono a zero.
    const piastrelle = L.daFare(c).map((v) => {
      const attiva = v.vista ? S.vista === v.vista : inPosta && L.stessoFiltro(v.filtro, S.filtro);
      return h('button', {
        type: 'button',
        class: 'piastrella ' + v.chiave + (attiva ? ' attiva' : ''),
        'data-filtro': v.chiave,
        'aria-pressed': attiva ? 'true' : 'false',
        onclick: () => (v.vista ? vaiVista(v.vista) : scegliFiltro(v.filtro)),
      }, v.numero != null ? h('span', { class: 'numero' }, String(v.numero)) : null, h('span', { class: 'etichetta' }, v.etichetta));
    });
    const daFare = piastrelle.length ? h('section', { class: 'da-fare', 'aria-label': 'Da fare' }, h('h2', null, 'Da fare'), h('div', { class: 'piastrelle' }, piastrelle)) : null;
    const tutte = voceMenu(inPosta && S.filtro.tipo === 'tutte', 'tutte', 'Tutta la posta',
      c && c.da_leggere ? h('span', { class: 'contatore tenue', title: 'Da leggere' }, String(c.da_leggere)) : null,
      () => scegliFiltro({ tipo: 'tutte' }));
    const gruppi = S.gruppi.map((g, i) => {
      const daLeggere = daLeggereGruppo(g);
      const attivo = inPosta && S.filtro.tipo === 'gruppo' && Number(S.filtro.id) === g.id;
      const bersaglio = h('button', {
        type: 'button',
        class: 'voce gruppo ' + classeColore(g) + (attivo ? ' attiva' : ''),
        'aria-current': attivo ? 'page' : null,
        'data-gruppo': String(g.id),
        title: g.descrizione || null,
        onclick: () => scegliFiltro({ tipo: 'gruppo', id: g.id }),
      },
        h('span', { class: 'pallino', 'aria-hidden': 'true' }),
        h('span', { class: 'etichetta' }, g.nome),
        h('span', { class: 'gruppo-dx' },
          daLeggere ? h('span', { class: 'contatore tenue', title: 'Da leggere' }, String(daLeggere)) : null,
          i < 9 ? h('kbd', { title: 'Premi ' + (i + 1) + ' per spostare qui le mail selezionate' }, String(i + 1)) : null));
      // Trascinando una o più mail, il gruppo di destinazione si illumina del suo colore.
      bersaglio.addEventListener('dragover', (e) => {
        if (!e.dataTransfer || !Array.from(e.dataTransfer.types || []).includes('text/x-sol-mail')) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        bersaglio.classList.add('sopra');
      });
      bersaglio.addEventListener('dragleave', () => bersaglio.classList.remove('sopra'));
      bersaglio.addEventListener('drop', (e) => {
        e.preventDefault();
        bersaglio.classList.remove('sopra');
        let ids = [];
        try { ids = JSON.parse(e.dataTransfer.getData('text/x-sol-mail')); } catch (err) { ids = []; }
        if (Array.isArray(ids) && ids.length) sposta(ids.map(Number), g.id);
      });
      return h('li', null, bersaglio);
    });
    const altre = [
      voceMenu(S.vista === 'riassunti', 'riassunto', 'Riassunti', null, () => vaiVista('riassunti')),
      voceMenu(S.vista === 'indietro', 'indietro', 'Niente resta indietro', null, () => vaiVista('indietro')),
      voceMenu(S.vista === 'scadenze', 'scadenze', 'Scadenze', null, () => vaiVista('scadenze')),
      voceMenu(S.vista === 'notifiche', 'campana', 'Notifiche recenti', null, () => vaiVista('notifiche')),
      voceMenu(S.vista === 'impostazioni', 'impostazioni', 'Impostazioni', null, () => vaiVista('impostazioni')),
    ];
    svuota(S.dom.menu,
      daFare,
      h('ul', { class: 'lista-menu' }, tutte),
      h('p', { class: 'titoletto' }, 'Gruppi', h('span', { class: 'suggerimento' }, 'trascina qui le mail')),
      h('ul', { class: 'lista-menu gruppi' }, gruppi.length ? gruppi : h('li', { class: 'vuoto-piccolo' }, 'Nessun gruppo')),
      h('ul', { class: 'lista-menu viste' }, altre));
  }

  // --- Spostarsi tra le viste e le mail, senza perdere quello che si sta scrivendo -------------------

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

  async function scegliFiltro(filtro) {
    if (!(await puoLasciare(() => scegliFiltro(filtro)))) return;
    const cambiaVista = S.vista !== 'posta';
    if (cambiaVista) distruggiSolleciti();
    S.vista = 'posta';
    if (!L.stessoFiltro(filtro, S.filtro) || cambiaVista) {
      S.filtro = filtro;
      S.selezione = new Set();
      S.attiva = null;
      S.ancora = null;
      S.aperta = null;
      chiudiEditorPosta();
      S.elenco.mail = [];
      history.replaceState(null, '', location.pathname);
    }
    renderMenu();
    renderArea();
    await caricaElenco();
  }

  async function vaiVista(vista) {
    if (vista === S.vista) return;
    if (!(await puoLasciare(() => vaiVista(vista)))) return;
    distruggiEditori();
    S.aperta = null;
    S.vista = vista;
    history.replaceState(null, '', location.pathname);
    renderMenu();
    renderArea();
  }

  async function vaiAlFrammento(frammento) {
    const d = L.destinazioneDaFrammento(frammento);
    if (!d && String(frammento || '').replace(/^#/, '') === 'notifiche') return vaiVista('notifiche');
    if (!d || S.abbinamento) return;
    if (d.mailId) {
      if (!(await puoLasciare(() => vaiAlFrammento(frammento)))) return;
      if (S.vista !== 'posta') {
        distruggiSolleciti();
        S.vista = 'posta';
        renderMenu();
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
    if (d.vista === 'posta') await scegliFiltro(d.filtro);
    else await vaiVista(d.vista);
  }

  function renderArea() {
    if (!S.dom.area) return;
    S.dom.area.className = 'area ' + S.vista;
    if (S.vista === 'posta') return costruisciPosta();
    if (S.vista === 'riassunti') return vistaRiassunti();
    if (S.vista === 'indietro') return vistaIndietro();
    if (S.vista === 'scadenze') return vistaScadenze();
    if (S.vista === 'notifiche') return vistaNotifiche();
    if (S.vista === 'impostazioni') return vistaImpostazioni();
    return null;
  }

  // --- Posta: elenco ----------------------------------------------------------------------------

  function titoloFiltro() {
    if (S.filtro.tipo === 'gruppo') {
      const g = S.gruppi.find((x) => x.id === Number(S.filtro.id));
      return g ? g.nome : 'Gruppo';
    }
    return (L.FILTRI[S.filtro.tipo] || L.FILTRI.tutte).titolo;
  }

  function costruisciPosta() {
    let timerCerca = null;
    const cerca = h('input', {
      type: 'search', class: 'cerca', id: 'cerca', value: S.cerca,
      placeholder: 'Cerca mittente, oggetto o testo', 'aria-label': 'Cerca nella posta', spellcheck: 'false',
      oninput: (e) => {
        clearTimeout(timerCerca);
        const valore = e.target.value;
        timerCerca = setTimeout(() => cercaTesto(valore), 350);
      },
      onkeydown: (e) => {
        if (e.key === 'Enter') { clearTimeout(timerCerca); cercaTesto(e.target.value); }
        if (e.key === 'ArrowDown') { e.preventDefault(); S.dom.elenco.focus(); muovi(1, false); }
      },
    });
    const ricorda = h('input', { type: 'checkbox', id: 'ricorda', checked: S.ricorda, onchange: (e) => { S.ricorda = e.target.checked; } });
    S.dom.titoloElenco = h('h2', { class: 'titolo-elenco' });
    S.dom.filtroAttivo = h('p', { class: 'filtro-attivo' });
    S.dom.infoSelezione = h('p', { class: 'info-selezione' });
    S.dom.elenco = h('ul', { class: 'elenco-mail', role: 'listbox', 'aria-multiselectable': 'true', 'aria-label': 'Mail', tabindex: '0' });
    S.dom.lettura = h('section', { class: 'lettura', 'aria-label': 'Mail aperta' });
    // La modalità prova: una striscia sottile ambra in cima alla Posta, non ripetuta in ogni mail.
    S.dom.prova = h('div', { class: 'striscia-prova', role: 'status', hidden: !(S.salute && S.salute.modalita_prova) },
      h('strong', null, 'Prova:'), ' Sol suggerisce e non sposta niente.');
    svuota(S.dom.area,
      S.dom.prova,
      h('section', { class: 'colonna-elenco' },
        h('div', { class: 'testa-elenco' },
          h('div', { class: 'riga-cerca' }, icona('cerca', 'icona-cerca'), cerca),
          h('div', { class: 'riga-titolo' }, S.dom.titoloElenco,
            h('label', { class: 'spunta piccola', title: 'Quando sposti UNA mail, Sol impara a mettere in quel gruppo anche le prossime dello stesso mittente.' }, ricorda, ' Ricordati questo mittente')),
          S.dom.filtroAttivo,
          S.dom.infoSelezione),
        S.dom.elenco),
      S.dom.lettura);
    renderElenco();
    renderLettura();
  }

  async function cercaTesto(valore) {
    if (valore === S.cerca) return;
    S.cerca = valore;
    await caricaElenco();
  }

  /**
   * L'elenco dal server. Normale (filtro o ricerca nuovi): si sostituisce. Silenzioso (il giro del minuto,
   * "Aggiorna"): si UNISCE a quello che c'è, senza perdere le mail già caricate né la selezione (§ revisione 8).
   */
  async function caricaElenco(opzioni) {
    const o = opzioni || {};
    if (S.vista !== 'posta') return;
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
    renderBarra();
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

  function ordine() {
    return S.elenco.mail.map((m) => m.id);
  }

  function nomeMittente(m) {
    return (m.mittente && (m.mittente.nome || m.mittente.indirizzo)) || '(mittente sconosciuto)';
  }

  const SEGNALI = {
    truffa: () => h('span', { class: 'segno truffa' }, icona('triangolo'), 'Possibile truffa'),
    urgente: () => h('span', { class: 'segno urgente' }, icona('esclamativo'), 'urgente'),
    controllare: () => h('span', { class: 'segno controllare' }, icona('interrogativo'), 'da controllare'),
    pronta: () => h('span', { class: 'segno pronta' }, icona('matita'), 'risposta pronta'),
  };
  const PAROLE_SEGNALI = { truffa: 'possibile truffa', urgente: 'urgente', controllare: 'da controllare', pronta: 'risposta pronta' };

  /**
   * Al massimo due segnali, nell'ordine della guida (truffa, urgente, da controllare, risposta pronta).
   * Se resta posto, una mail già risposta lo dice piano ("risposta data", grigio): non è un segnale da guardare.
   */
  function segni(m) {
    const l = L.segnaliMail(m).map((k) => SEGNALI[k]());
    if (m.risposta_data && l.length < 2) l.push(h('span', { class: 'segno tenue' }, icona('spunta'), 'risposta data'));
    return l;
  }

  function renderElenco() {
    if (!S.dom.elenco || !S.dom.elenco.isConnected) return;
    const filtrato = S.filtro.tipo !== 'tutte' && S.filtro.tipo !== 'gruppo';
    S.dom.titoloElenco.textContent = (filtrato ? 'Tutta la posta' : titoloFiltro()) + (S.cerca ? ' · "' + S.cerca + '"' : '');
    // Con un filtro attivo, sopra l'elenco: "Da rispondere · togli filtro" (docs/aspetto.md, §2).
    svuota(S.dom.filtroAttivo, filtrato ? [h('strong', null, titoloFiltro()), ' · ',
      h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => scegliFiltro({ tipo: 'tutte' }) }, 'togli filtro')] : null);
    const n = S.selezione.size;
    S.dom.infoSelezione.textContent = n > 1
      ? n + ' mail selezionate: trascinale su un gruppo o premi il suo numero (1-9).'
      : '';
    if (S.elenco.caricando && S.elenco.mail.length === 0) {
      svuota(S.dom.elenco, h('li', { class: 'vuoto' }, 'Carico le mail…'));
      return;
    }
    if (S.elenco.errore && S.elenco.mail.length === 0) {
      svuota(S.dom.elenco, h('li', { class: 'vuoto' }, messaggioInLinea(S.elenco.errore)));
      return;
    }
    if (S.elenco.mail.length === 0) {
      svuota(S.dom.elenco, h('li', { class: 'vuoto' }, S.cerca ? 'Nessuna mail trovata con questa ricerca.' : 'Nessuna mail qui.'));
      return;
    }
    const nuove = S.elenco.nuove || new Set();
    S.elenco.nuove = null;
    const righe = S.elenco.mail.map((m) => {
      const sel = S.selezione.has(m.id);
      const g = gruppoDi(m);
      const colore = classeColore(g);
      const chiavi = L.segnaliMail(m);
      const li = h('li', {
        class: 'mail' + (m.letta ? '' : ' non-letta') + (sel ? ' selezionata' : '') + (S.attiva === m.id ? ' attiva' : '') + (nuove.has(m.id) ? ' entrata' : ''),
        role: 'option',
        'aria-selected': sel ? 'true' : 'false',
        // Il lettore schermo legge prima i segnali importanti, poi chi scrive e l'oggetto.
        'aria-label': chiavi.map((k) => PAROLE_SEGNALI[k]).concat(m.letta ? [] : ['non letta'], [nomeMittente(m), m.oggetto || '(senza oggetto)'], m.risposta_data ? ['risposta data'] : []).join(', '),
        draggable: 'true',
        onclick: (e) => suClicMail(e, m.id),
        ondragstart: (e) => {
          const ids = L.idsTrascinati(m.id, S.selezione);
          e.dataTransfer.setData('text/x-sol-mail', JSON.stringify(ids));
          e.dataTransfer.effectAllowed = 'move';
          const etichetta = h('div', { class: 'trascinata' }, ids.length === 1 ? (m.oggetto || '(senza oggetto)') : ids.length + ' mail');
          document.body.append(etichetta);
          if (e.dataTransfer.setDragImage) e.dataTransfer.setDragImage(etichetta, 10, 10);
          setTimeout(() => etichetta.remove(), 0);
        },
      },
        h('span', { class: 'avatar ' + colore, 'aria-hidden': 'true' }, L.iniziali(m.mittente && m.mittente.nome, m.mittente && m.mittente.indirizzo)),
        h('div', { class: 'contenuto-mail' },
          h('div', { class: 'riga-1' },
            h('span', { class: 'mittente' }, nomeMittente(m)),
            h('span', { class: 'ora' }, L.oraBreve(m.ricevuta_il))),
          h('div', { class: 'oggetto' }, m.oggetto || '(senza oggetto)'),
          h('div', { class: 'riassunto-breve' }, m.riassunto || m.anteprima || ''),
          h('div', { class: 'riga-segni-elenco' },
            h('span', { class: 'segni' }, segni(m)),
            m.pec ? h('span', { class: 'tipo-pec', title: 'PEC arrivata da ' + (m.pec.mittente_busta || '') }, 'PEC') : null,
            S.filtro.tipo !== 'gruppo' && m.gruppo ? h('span', { class: 'gruppo-etichetta ' + colore }, m.gruppo) : null)));
      li.dataset.id = String(m.id);
      return li;
    });
    if (S.elenco.altre) {
      righe.push(h('li', { class: 'altre' }, h('button', { type: 'button', class: 'pulsante secondario', onclick: caricaAltre }, 'Mostra le mail più vecchie')));
    }
    svuota(S.dom.elenco, righe);
    const attiva = S.dom.elenco.querySelector('.mail.attiva');
    if (attiva && attiva.scrollIntoView) attiva.scrollIntoView({ block: 'nearest' });
  }

  /** Cambia la selezione; se cambia quello che mostra il riquadro di destra, prima si salva la risposta aperta. */
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

  /** La mail resta in questo elenco dopo che è cambiata? (es. spostata in un altro gruppo: no). */
  function restaNelFiltro(m) {
    if (!m) return false;
    switch (S.filtro.tipo) {
      case 'gruppo': return m.gruppo_id === Number(S.filtro.id);
      case 'da-controllare': return m.smistamento === 'da_controllare';
      case 'da-rispondere': return !!m.da_rispondere && !m.risposta_data;
      case 'urgenti': return m.urgenza === 'alta';
      case 'truffe': return !!m.sospetta;
      default: return true;
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

  async function sposta(ids, gruppoId) {
    const gruppo = S.gruppi.find((g) => g.id === gruppoId);
    if (!gruppo) return;
    const domanda = { titolo: 'Spostare ' + ids.length + ' mail in ' + gruppo.nome + '?', testo: 'Con più mail insieme Sol non impara i mittenti.', si: 'Sposta ' + ids.length + ' mail' };
    if (!(await preparaSmistamento(ids, domanda))) return;
    // Le regole si imparano solo spostando UNA mail: con più mail insieme si sbaglia troppo facilmente (§ revisione 9).
    const impara = S.ricorda && ids.length === 1;
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
    const prova = S.salute && S.salute.modalita_prova ? ' (modalità prova: nella casella vera resta dov\'era)' : '';
    const parziale = L.esitoMultiplo(riuscite.length, ids.length - riuscite.length, 'spostate in ' + gruppo.nome, motivo);
    if (parziale) avviso(parziale, 'errore');
    else avviso((riuscite.length === 1 ? 'Spostata' : riuscite.length + ' mail spostate') + ' in ' + gruppo.nome + prova + '.', 'ok');
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
    // La riga che esce dalla vista scivola via (docs/aspetto.md, §5); intanto nessun'altra azione parte.
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
    const restanti = new Set(S.elenco.mail.map((m) => m.id));
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
    if (S.aperta && ids.includes(S.aperta.id)) aggiornaTestata();
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
    avviso(valore ? 'Segnalata come possibile truffa: Sol non le prepara risposte da sola.' : 'Tolta l\'etichetta "Possibile truffa": la mail torna normale.', 'ok');
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
      case 'esci-campo': b.blur(); if (S.dom.elenco) S.dom.elenco.focus(); break;
      case 'scorri': muovi(azione.passo, false); break;
      case 'estendi': muovi(azione.passo, true); break;
      case 'sposta': sposta(ids, S.gruppi[azione.indice].id); break;
      case 'va-bene': vaBene(ids); break;
      case 'bozza': apriOPreparaBozza(); break;
      case 'letta': cambiaLetta(ids); break;
      case 'rispondere': cambiaRispondere(ids); break;
      case 'cerca': { const c = document.getElementById('cerca'); if (c) { c.focus(); c.select(); } break; }
      case 'seleziona-tutto':
        cambiaSelezione({ selezione: new Set(ordine()), ancora: S.ancora, attiva: null });
        break;
      case 'annulla':
        if (S.selezione.size > 1) {
          const una = S.attiva != null && S.selezione.has(S.attiva) ? S.attiva : null;
          cambiaSelezione({ selezione: una != null ? new Set([una]) : new Set(), ancora: una, attiva: una });
        }
        break;
      case 'aiuto': mostraAiuto(); break;
      default: break;
    }
  }

  function mostraAiuto() {
    const righe = [
      ['Frecce su e giù (o J e K)', 'passa alla mail sopra o sotto'],
      ['Maiusc + frecce, Ctrl + clic, Maiusc + clic', 'seleziona più mail'],
      ['1 … 9', 'sposta le selezionate nel gruppo con quel numero'],
      ['Invio', 'va bene così: accetta il gruppo proposto e passa alla prossima'],
      ['R', 'apri la bozza di risposta (o falla preparare)'],
      ['L', 'segna letta o da leggere'],
      ['D', 'togli o rimetti "da rispondere"'],
      ['/ oppure Ctrl + F', 'cerca'],
      ['Ctrl + A (con l\'elenco selezionato)', 'seleziona tutto l\'elenco'],
      ['Esc', 'esci dal campo o togli la selezione multipla'],
      ['F5', 'ricarica'],
    ];
    const chiudi = () => { velo.remove(); S.velo = null; };
    const velo = h('div', { class: 'velo', onclick: (e) => { if (e.target === velo) chiudi(); } },
      h('div', { class: 'scheda finestrella', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Scorciatoie' },
        h('h2', null, 'Scorciatoie da tastiera'),
        h('table', { class: 'tabella-tasti' }, h('tbody', null, righe.map(([t, d]) => h('tr', null, h('td', null, h('kbd', null, t)), h('td', null, d))))),
        h('p', { class: 'aiuto' }, 'Ogni tasto fa la sua azione una volta per pressione: tenerlo premuto non la ripete. Con più di 5 mail Sol chiede conferma.'),
        h('button', { type: 'button', class: 'pulsante primario', onclick: chiudi }, 'Ho capito')));
    S.velo = { chiudi };
    document.body.append(velo);
    velo.querySelector('button').focus();
  }

  // --- Posta: mail aperta ------------------------------------------------------------------------

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
    renderLettura();
    if (!r.dati.letta && !r.memoria) {
      // Aprire una mail la segna letta, anche nella casella vera: è un gesto dell'utente (§6).
      const l = await Api.post('/server/api/mail/' + id + '/letta', { letta: true });
      if (l.ok) {
        aggiornaInElenco({ id, letta: true });
        renderElenco();
        aggiornaContatori();
      }
    }
  }

  function persona(p) {
    if (!p) return '';
    if (p.nome && p.indirizzo) return p.nome + ' <' + p.indirizzo + '>';
    return p.nome || p.indirizzo || '';
  }

  function elencoPersone(lista) {
    return (lista || []).map((x) => (typeof x === 'string' ? x : persona(x))).filter(Boolean).join(', ');
  }

  function renderLettura() {
    if (!S.dom.lettura || !S.dom.lettura.isConnected) return;
    S.dom.barraRisposta = null;
    if (S.selezione.size > 1) {
      chiudiEditorPosta();
      return renderSelezioneMultipla();
    }
    if (!S.aperta) {
      chiudiEditorPosta();
      svuota(S.dom.lettura, h('div', { class: 'lettura-scorre' }, h('div', { class: 'vuoto-lettura' },
        h('p', { class: 'grande' }, 'Scegli una mail dall\'elenco.'),
        h('p', null, 'Con le frecce scorri, con i numeri da 1 a 9 la sposti in un gruppo, con Invio dici "va bene così".'),
        h('button', { type: 'button', class: 'pulsante testo', onclick: mostraAiuto }, 'Tutte le scorciatoie (?)'))));
      return;
    }
    if (S.aperta.caricando) {
      svuota(S.dom.lettura, h('div', { class: 'lettura-scorre' }, h('p', { class: 'vuoto-lettura' }, 'Apro la mail…')));
      return;
    }
    if (S.aperta.errore) {
      svuota(S.dom.lettura, h('div', { class: 'lettura-scorre' }, h('div', { class: 'vuoto-lettura' }, messaggioInLinea(S.aperta.errore))));
      return;
    }
    const m = S.aperta.mail;
    S.dom.testata = h('article', { class: 'mail-aperta' });
    S.dom.bozza = h('section', { class: 'sezione-bozza', 'aria-label': 'Risposta' });
    // La barra fissa in basso alla colonna: [Rifai] [Invia →] e le sue varianti (docs/aspetto.md, §3).
    S.dom.barraRisposta = h('div', { class: 'barra-risposta azioni-bozza', role: 'toolbar', 'aria-label': 'Risposta' });
    svuota(S.dom.lettura, h('div', { class: 'lettura-scorre' }, S.dom.testata, S.dom.bozza), S.dom.barraRisposta);
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

  /** "Sposta in": i gruppi colorati con cerchio, nome e descrizione corta (si apre dal gruppo o da "Cambia"). */
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
          h('span', { class: 'avatar piccolo ' + classeColore(x), 'aria-hidden': 'true' }, i < 9 ? String(i + 1) : ''),
          h('span', { class: 'testi' },
            h('span', { class: 'nome-gruppo' }, x.nome + (x.id === mm.gruppo_id ? ' (adesso è qui)' : '')),
            x.descrizione ? h('span', { class: 'descrizione-gruppo' }, x.descrizione) : null))));
    };
    const apri = () => { riempi(); apriFinestrella(elenco, chip); };
    const origine = { regola: 'messa qui da una regola', decisore: 'messa qui da Sol', utente: 'messa qui da te' }[m.origine_smistamento];
    chip = h('button', {
      type: 'button', class: 'chip-gruppo ' + classeColore(g), 'aria-haspopup': 'menu', 'aria-expanded': 'false',
      title: (origine ? origine[0].toUpperCase() + origine.slice(1) + '. ' : '') + 'Clic per spostarla in un altro gruppo.',
      onclick: (e) => { e.stopPropagation(); apri(); },
    }, (m.gruppo || (g && g.nome) || 'Senza gruppo'), icona('giu'));
    S.dom.spostaInElenco = elenco;
    return { ancora: h('span', { class: 'ancora-menu' }, chip, elenco), chip, elenco, apri };
  }

  /** Il menu ⋮ con le azioni rare (docs/aspetto.md, §3). */
  function costruisciMenuAzioni(m) {
    let pulsante = null;
    const voce = (testo, azione, classe) => h('button', { type: 'button', role: 'menuitem', class: 'voce-menu' + (classe ? ' ' + classe : ''),
      onclick: () => { chiudiFinestrelleMail(); azione(); } }, testo);
    const elenco = h('div', { class: 'menu-azioni', role: 'menu', 'aria-label': 'Altre azioni', hidden: true,
      onkeydown: (e) => tastiMenu(e, elenco, pulsante) });
    const riempi = () => {
      const mm = mailAdesso(m);
      const ed = S.editor && !S.editor.distrutto && S.editor.mailId === mm.id ? S.editor : null;
      svuota(elenco,
        voce(mm.letta ? 'Segna come da leggere' : 'Segna come letta', () => cambiaLetta([mm.id])),
        voce(mm.da_rispondere ? 'Non serve rispondere' : 'Va risposta', () => cambiaRispondere([mm.id])),
        mm.sospetta ? null : voce('Segnala come possibile truffa', () => cambiaSospetta(mm.id, true), 'discreto'),
        ed && ed.dati && ['pronta', 'errore'].includes(ed.dati.stato) && (ed.dati.testo || '').trim() ? voce('Scarta la risposta', async () => {
          const si = await chiediConferma({ titolo: 'Scartare la risposta?', testo: 'Sol la toglie; se serve, potrai fargliene preparare un\'altra.', si: 'Sì, scarta', pericolo: true });
          if (si) ed.scarta();
        }, 'pericolo') : null);
    };
    pulsante = h('button', {
      type: 'button', class: 'pulsante testo pulsante-menu', 'aria-label': 'Altre azioni', title: 'Altre azioni',
      'aria-haspopup': 'menu', 'aria-expanded': 'false',
      onclick: (e) => { e.stopPropagation(); riempi(); apriFinestrella(elenco, pulsante); },
    }, icona('punti'));
    S.dom.menuAzioni = elenco;
    return { ancora: h('span', { class: 'ancora-menu' }, pulsante, elenco), pulsante, elenco };
  }

  /** La parte alta della mail aperta: il riquadro della risposta e la barra in basso non si toccano. */
  function aggiornaTestata() {
    if (!S.dom.testata || !S.dom.testata.isConnected || !S.aperta || !S.aperta.mail) return;
    const m = S.aperta.mail;
    const g = gruppoDi(m);
    const colore = classeColore(g);
    const spostaIn = costruisciSpostaIn(m);
    const menu = costruisciMenuAzioni(m);
    // Su una mail già segnalata come possibile truffa l'avviso in cima basta: "non sicura se è una truffa"
    // sotto lo contraddirebbe.
    const incerti = Array.isArray(m.incerti) ? m.incerti.filter((x) => !(x === 'truffa' && m.sospetta)).map((x) => ({ urgenza: 'urgenza', da_rispondere: 'se va risposta', gruppo: 'gruppo', truffa: 'se è una possibile truffa' }[x] || null)).filter(Boolean) : [];
    const allegati = (m.allegati || []).length
      ? h('div', { class: 'blocco-allegati' }, h('p', { class: 'etichetta-allegati' }, 'Allegati'),
        h('ul', { class: 'allegati', 'aria-label': 'Allegati' }, m.allegati.map((a) => h('li', null, icona('allegato'), a.nome + (a.byte ? ' (' + dimensione(a.byte) + ')' : '')))))
      : null;
    svuota(S.dom.testata,
      S.aperta.memoria ? h('p', { class: 'in-linea avvertenza' }, 'Copia salvata il ' + L.dataLunga(S.aperta.memoria) + ': il server ora non risponde.') : null,
      h('header', { class: 'testa-mail' },
        h('span', { class: 'avatar grande ' + colore, 'aria-hidden': 'true' }, L.iniziali(m.mittente && m.mittente.nome, m.mittente && m.mittente.indirizzo)),
        h('div', { class: 'chi' },
          h('div', { class: 'nome' }, nomeMittente(m)),
          m.mittente && m.mittente.nome && m.mittente.indirizzo ? h('div', { class: 'indirizzo' }, m.mittente.indirizzo) : null,
          h('div', { class: 'quando' },
            h('span', null, L.oraIntestazione(m.ricevuta_il)),
            h('span', { 'aria-hidden': 'true' }, '·'),
            spostaIn.ancora,
            m.smistamento === 'in_attesa' ? h('span', { class: 'segno tenue' }, 'non ancora smistata') : null,
            m.urgenza === 'alta' ? SEGNALI.urgente() : null,
            m.risposta_data ? h('span', { class: 'segno ok' }, icona('spunta'), 'risposta data') : null)),
        menu.ancora),
      // Mail da controllare: una riga compatta sotto l'intestazione; le altre non hanno niente sul gruppo.
      m.smistamento === 'da_controllare' ? h('div', { class: 'riga-controllo' },
        h('span', { class: 'domanda' }, icona('interrogativo'), 'È nel gruppo giusto?'),
        h('button', { type: 'button', class: 'pulsante secondario piccolo', title: 'Va bene così (Invio)', onclick: () => vaBene([m.id]) }, 'Sì'),
        h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: (e) => { e.stopPropagation(); spostaIn.apri(); } }, 'Cambia'),
        m.sospetta ? h('p', { class: 'aiuto' }, '"Sì" (o Invio) accetta solo il gruppo: la mail resta segnalata come possibile truffa finché non premi "Non è una truffa".') : null) : null,
      h('h1', { class: 'oggetto-grande' }, m.oggetto || '(senza oggetto)'),
      // §16.3: l'avviso truffa, sopra "In breve", col pulsante esplicito per toglierlo.
      m.sospetta ? h('div', { class: 'riquadro truffa', role: 'alert' },
        h('p', { class: 'testo-truffa' }, AVVISO_TRUFFA),
        h('div', { class: 'azioni' },
          h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: () => cambiaSospetta(m.id, false) }, 'Non è una truffa'))) : null,
      m.riassunto ? h('p', { class: 'riassunto-mail ' + colore }, h('span', { class: 'etichetta-riassunto' }, 'In breve'), m.riassunto) : null,
      // Chi scrive è già nell'intestazione: qui solo quello che serve in più (busta PEC, destinatari, casella).
      h('dl', { class: 'intestazioni' },
        // Una PEC ha due mittenti: chi ha scritto e la casella certificata che l'ha spedita (§15.8).
        m.pec ? [h('dt', null, 'Busta PEC'), h('dd', null, (m.pec.mittente_busta || '') + (m.pec.gestore ? ' (gestore ' + m.pec.gestore + ')' : ''))] : null,
        (m.destinatari || []).length ? [h('dt', null, 'A'), h('dd', null, elencoPersone(m.destinatari))] : null,
        (m.cc || []).length ? [h('dt', null, 'Cc'), h('dd', null, elencoPersone(m.cc))] : null,
        m.casella ? [h('dt', null, 'Arrivata in'), h('dd', null, m.casella + ((!m.applicata_in_casella && m.smistamento !== 'in_attesa' && S.salute && S.salute.modalita_prova) ? ' (prova: nella casella vera è rimasta dov\'era)' : ''))] : null),
      incerti.length ? h('p', { class: 'tenue piccolo' }, 'Sol non è del tutto sicura di: ' + incerti.join(', ') + '.') : null,
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
      // Partita la risposta, l'intestazione dice "risposta data" (il riquadro della risposta non si tocca).
      if (S.aperta && S.aperta.id === ed.mailId && !(S.dom.testata && S.dom.testata.contains(document.activeElement))) aggiornaTestata();
      aggiornaContatori();
    }
  }

  function renderSelezioneMultipla() {
    const ids = [...S.selezione];
    svuota(S.dom.lettura, h('div', { class: 'lettura-scorre' }, h('div', { class: 'selezione-multipla' },
      h('p', { class: 'grande' }, ids.length + ' mail selezionate'),
      h('p', null, 'Trascinale su un gruppo a sinistra, premi il numero del gruppo, oppure scegli qui. Spostando più mail insieme Sol non impara i mittenti' + (ids.length > MAX_SENZA_CONFERMA ? ', e prima chiede conferma.' : '.')),
      h('div', { class: 'griglia-gruppi' }, S.gruppi.map((g, i) => h('button', { type: 'button', class: 'pulsante secondario ' + classeColore(g), onclick: () => sposta(ids, g.id) },
        h('span', { class: 'pallino', 'aria-hidden': 'true' }), i < 9 ? h('kbd', null, String(i + 1)) : null, ' ' + g.nome))),
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante primario', onclick: () => vaBene(ids) }, 'Vanno bene così (Invio)'),
        h('button', { type: 'button', class: 'pulsante secondario', onclick: () => cambiaLetta(ids) }, 'Segna lette o da leggere (L)')))));
  }

  function dimensione(byte) {
    if (byte < 1024) return byte + ' byte';
    if (byte < 1024 * 1024) return Math.round(byte / 1024) + ' kB';
    return (byte / 1024 / 1024).toFixed(1).replace('.', ',') + ' MB';
  }

  /** R: apre la bozza, oppure la fa preparare se non c'è. Mai mentre si carica, mai sopra una bozza esistente (§ revisione 6). */
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

  // --- Il riquadro di una bozza: ognuno lavora SOLO sulla sua bozza -------------------------------

  function campiDa(d) {
    return { a: (d.a || []).join(', '), cc: (d.cc || []).join(', '), oggetto: d.oggetto || '', testo: d.testo || '' };
  }

  function ugualiCampi(x, y) {
    return x.a === y.a && x.cc === y.cc && x.oggetto === y.oggetto && x.testo === y.testo;
  }

  /**
   * Un riquadro di bozza. Tutto quello che fa (salvare, inviare, rifare, scartare) lo fa sulla SUA bozza:
   * le chiusure portano `ed`, niente stato condiviso tra riquadri, id HTML unici (prefisso).
   * o: { contenitore, prefisso, mailId, inviata, prepara(istruzioni), suCambio(ed, evento) }
   */
  function creaEditor(o) {
    const ed = {
      id: null, mailId: o.mailId || null, inviata: o.inviata || null,
      dati: null, campi: null, sporca: false,
      catena: Promise.resolve(true), timerSalva: null, salvando: false,
      caricando: false, preparando: false, invio: false,
      messaggio: null, tipoMessaggio: 'errore', campoErrato: null, messaggioDaSalvataggio: false,
      rifaiAperto: false, chiediDaZero: false, nonSalvata: null,
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
      if (d.stato === 'in_preparazione') ed.poll = setTimeout(ricontrolla, T.bozza);
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
     * bozza ritoccata a mano si rifà da zero solo dopo un sì esplicito.
     */
    ed.rifai = async (istruzioni, daZero) => {
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
        render();
        mostraErrore(r.errore);
        return;
      }
      imposta(r.dati);
    };

    function modifica(campo, el) {
      if (ed.invio || !ed.campi) {
        // Durante la conferma d'invio i campi sono bloccati: niente cambia rispetto a quello che si sta confermando.
        el.value = ed.campi ? ed.campi[campo] : '';
        return;
      }
      ed.campi[campo] = el.value;
      ed.sporca = true;
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
      avviso('Bozza scartata.', 'ok');
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

    /** La scritta del pulsante d'invio: nella barra in basso "Invia →", nei solleciti "Invia…". */
    function etichettaInvio() {
      if (ed.invio) return 'In attesa della conferma…';
      const errore = ed.dati && ed.dati.stato === 'errore';
      if (ed.barra) return errore ? 'Riprova a inviare' : 'Invia';
      return errore ? 'Riprova a inviare…' : 'Invia…';
    }

    /** Blocca o sblocca i campi senza ridisegnare (il fuoco e il punto in cui si scriveva restano). */
    function aggiornaBlocco() {
      const b = ed.invio;
      for (const k of ['a', 'cc', 'oggetto', 'testo']) if (ed.el[k]) ed.el[k].readOnly = b;
      for (const k of ['invia', 'rifai']) if (ed.el[k]) ed.el[k].disabled = b;
      if (ed.el.etichettaInvia) ed.el.etichettaInvia.textContent = etichettaInvio();
    }

    function disegnaBanner() {
      if (!ed.el.banner) return;
      if (ed.chiediDaZero) {
        svuota(ed.el.banner, h('div', { class: 'banner-bozza' },
          messaggioInLinea('Hai modificato questa bozza a mano. Rifarla da zero cancella le tue modifiche.', 'avvertenza'),
          h('div', { class: 'azioni' },
            h('button', { type: 'button', class: 'pulsante pericolo piccolo', onclick: () => ed.rifai(null, true) }, 'Sì, rifalla da zero'),
            h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: () => { ed.chiediDaZero = false; disegnaBanner(); } }, 'No, tienila'))));
        return;
      }
      if (ed.nonSalvata) {
        svuota(ed.el.banner, h('div', { class: 'banner-bozza' },
          messaggioInLinea('La risposta non è salvata' + (ed.messaggio ? ': ' + ed.messaggio : '.') + ' Per non perderla Sol resta qui.', 'avvertenza'),
          h('div', { class: 'azioni' },
            h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: () => { nascondiNonSalvata(); ed.focus(); } }, 'Resta qui'),
            h('button', { type: 'button', class: 'pulsante pericolo piccolo', onclick: scartaModifiche }, 'Scarta le modifiche'))));
        return;
      }
      svuota(ed.el.banner);
    }

    function campoIstruzioni(segnaposto, conAnnulla, vai) {
      const testo = h('textarea', { id: idCampo('istruzioni'), class: 'istruzioni', rows: '2', placeholder: segnaposto, 'aria-label': 'Istruzioni per la bozza',
        onkeydown: (e) => { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); invia(); } } });
      const invia = () => {
        const t = testo.value.trim();
        if (!t) { testo.focus(); return; }
        vai(t);
      };
      return h('div', { class: 'blocco-istruzioni' },
        testo,
        h('div', { class: 'azioni' },
          h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: invia }, 'Prepara con queste istruzioni'),
          conAnnulla ? h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => { ed.rifaiAperto = false; render(); } }, 'Annulla') : null,
          h('span', { class: 'aiuto' }, 'Puoi anche dettare: premi Win+H')));
    }

    function render() {
      if (ed.distrutto || !ed.contenitore) return;
      ed.el = {};
      ed.el.banner = h('div', { class: 'spazio-banner' });
      svuota(ed.contenitore, ed.el.banner, contenuto());
      if (ed.barra) svuota(ed.barra, azioniBarra());
      disegnaBanner();
    }

    function titolo() {
      return ed.inviata || (ed.dati && ed.dati.tipo === 'sollecito') ? 'Sollecito' : 'Risposta';
    }

    /** Nella mail aperta: si propone di preparare la risposta solo se va risposta e non è una possibile truffa. */
    function proponiRisposta() {
      const m = mailDi();
      return !!m && !!m.da_rispondere && !m.risposta_data && !m.sospetta;
    }

    /** I pulsanti della barra in basso (docs/aspetto.md, §3), secondo lo stato della risposta. */
    function azioniBarra() {
      if (ed.caricando) return [];
      const preparazione = () => [h('span', { class: 'attesa', role: 'status' }, h('span', { class: 'indicatore', 'aria-hidden': 'true' }), 'Sol sta scrivendo la risposta…')];
      // Un pulsante solo sta a destra, dove poi comparirà "Invia".
      const aDestra = (pulsante) => [h('span', { class: 'spazio' }), pulsante];
      if (ed.preparando) return preparazione();
      if (!ed.dati) {
        if (ed.id) return [];
        if (proponiRisposta()) return aDestra(h('button', { type: 'button', class: 'pulsante primario', title: 'Prepara la risposta (R)', onclick: () => ed.prepara(null) }, icona('rispondi'), 'Prepara la risposta'));
        // Mail che non chiede risposta: la si può preparare lo stesso, senza metterla in vista.
        // Una possibile truffa senza risposta chiesta: nessuna barra (il pulsante "Non è una truffa" è nell'avviso).
        const m = mailDi();
        return m && !m.sospetta ? aDestra(h('button', { type: 'button', class: 'pulsante testo', title: 'Prepara una risposta (R)', onclick: () => ed.prepara(null) }, icona('rispondi'), 'Rispondi')) : [];
      }
      const d = ed.dati;
      if (d.stato === 'in_preparazione') return preparazione();
      if (d.stato === 'inviata') return [];
      if (d.stato === 'scartata') return aDestra(h('button', { type: 'button', class: 'pulsante secondario', onclick: () => ed.prepara(null) }, 'Prepara una nuova risposta'));
      if (d.stato === 'errore' && !(d.testo || '').trim()) return aDestra(h('button', { type: 'button', class: 'pulsante primario', onclick: () => ed.rifai(null) }, 'Riprova a preparare'));
      ed.el.etichettaInvia = h('span', null, etichettaInvio());
      ed.el.invia = h('button', { type: 'button', class: 'pulsante primario', disabled: ed.invio ? true : null, onclick: () => ed.invia() }, ed.el.etichettaInvia, icona('freccia'));
      ed.el.rifai = h('button', { type: 'button', class: 'pulsante secondario', title: 'Rifai con istruzioni', disabled: ed.invio ? true : null, onclick: () => {
        ed.rifaiAperto = true;
        render();
        const t = document.getElementById(idCampo('istruzioni'));
        if (t) t.focus();
      } }, 'Rifai');
      return [ed.el.rifai, h('span', { class: 'spazio' }), ed.el.invia];
    }

    function contenuto() {
      const nellaMail = !!ed.barra;
      if (ed.caricando) return h('p', { class: 'attesa' }, 'Carico la bozza…');
      if (ed.preparando) {
        return nellaMail ? null : h('div', { class: 'pannello-bozza in-preparazione' }, h('h2', null, titolo()), h('p', { class: 'attesa' }, 'Chiedo a Sol di preparare la bozza…'));
      }
      if (!ed.dati) {
        if (ed.id) {
          return h('div', null, messaggioInLinea(ed.messaggio || 'La bozza non si è caricata.'),
            h('button', { type: 'button', class: 'pulsante secondario', onclick: () => ed.carica(ed.id) }, 'Riprova a caricarla'));
        }
        if (ed.inviata) {
          return h('div', null, messaggioInLinea(ed.messaggio),
            h('button', { type: 'button', class: 'pulsante secondario', onclick: () => ed.prepara(null) }, 'Prepara il sollecito'));
        }
        if (nellaMail) {
          // Il pulsante "Prepara la risposta" è nella barra in basso; qui solo le istruzioni, se serve rispondere.
          return h('div', null, messaggioInLinea(ed.messaggio),
            proponiRisposta() ? campoIstruzioni('Oppure di\' a Sol cosa rispondere, per esempio: digli che giovedì alle 10 va bene', false, (t) => ed.prepara(t)) : null);
        }
        return h('div', { class: 'bozza-assente' },
          messaggioInLinea(ed.messaggio),
          h('button', { type: 'button', class: 'pulsante primario', onclick: () => ed.prepara(null) }, icona('rispondi'), 'Prepara una risposta (R)'),
          campoIstruzioni('Oppure di\' a Sol cosa rispondere, per esempio: digli che giovedì alle 10 va bene', false, (t) => ed.prepara(t)));
      }
      const d = ed.dati;
      if (d.stato === 'in_preparazione') {
        return nellaMail ? null : h('div', { class: 'pannello-bozza in-preparazione' },
          h('h2', null, titolo()),
          h('p', { class: 'attesa' }, 'Sol sta scrivendo la bozza… di solito ci vuole meno di un minuto.'));
      }
      if (d.stato === 'inviata') {
        // Dopo l'invio la risposta si chiude con un segno di spunta (docs/aspetto.md, §5).
        return h('div', { class: 'pannello-bozza inviata' },
          h('h2', null, h('span', { class: 'segno-spunta', 'aria-hidden': 'true' }, icona('spunta')), titolo() + ' inviata'),
          messaggioInLinea(ed.messaggio, ed.tipoMessaggio),
          h('p', { class: 'tenue' }, (d.inviata_il ? 'Partita ' + L.dataLunga(d.inviata_il) : 'Partita') + ' verso ' + (d.a || []).join(', ') + '.'),
          h('div', { class: 'corpo-mail piccolo' }, d.testo || ''));
      }
      if (d.stato === 'scartata') {
        return h('div', { class: 'pannello-bozza scartata' },
          h('p', { class: 'tenue' }, 'Bozza scartata.'),
          nellaMail ? null : h('button', { type: 'button', class: 'pulsante secondario', onclick: () => ed.prepara(null) }, ed.inviata ? 'Prepara di nuovo il sollecito' : 'Prepara una nuova risposta'),
          ed.inviata ? null : campoIstruzioni('Di\' a Sol cosa rispondere', false, (t) => ed.prepara(t)));
      }
      if (d.stato === 'errore' && !(d.testo || '').trim()) {
        // È fallita la scrittura della bozza, non l'invio: si può solo riprovare a prepararla.
        return h('div', { class: 'pannello-bozza in-preparazione' },
          h('h2', null, titolo()),
          messaggioInLinea(ed.messaggio || d.errore || 'Sol non è riuscito a scrivere la bozza.'),
          nellaMail ? null : h('button', { type: 'button', class: 'pulsante secondario', onclick: () => ed.rifai(null) }, 'Riprova a preparare'));
      }

      // pronta o errore d'invio: si scrive direttamente qui, e si (re)invia
      const c = ed.campi;
      const campo = (chiave, etichetta) => {
        const input = h('input', {
          id: idCampo(chiave), type: 'text', value: c[chiave], class: ed.campoErrato === chiave ? 'campo-errato' : null,
          spellcheck: chiave === 'oggetto' ? 'true' : 'false', readonly: ed.invio ? true : null,
          oninput: (e) => modifica(chiave, e.target),
        });
        ed.el[chiave] = input;
        return h('div', { class: 'campo-bozza' }, h('label', { for: input.id }, etichetta), input);
      };
      ed.el.salvataggio = h('span', { id: idCampo('salvataggio'), class: 'salvataggio' }, ed.sporca ? 'Modifiche da salvare' : '');
      ed.el.messaggio = h('div', { class: 'messaggio-bozza' }, messaggioInLinea(ed.messaggio, ed.tipoMessaggio));
      const testo = h('textarea', {
        id: idCampo('testo'), class: 'testo-bozza' + (ed.campoErrato === 'testo' ? ' campo-errato' : ''), 'aria-label': 'Testo della risposta',
        spellcheck: 'true', value: c.testo, readonly: ed.invio ? true : null, oninput: (e) => modifica('testo', e.target),
      });
      ed.el.testo = testo;
      const m = mailDi();
      const giaRisposto = !ed.inviata && m && m.risposta_data;
      const sospetta = !ed.inviata && m && m.sospetta;
      const intestazione = nellaMail
        ? (d.stato === 'errore' ? 'Risposta (l\'invio non è riuscito)' : 'Risposta pronta') + (d.modificata ? ' · ritoccata da te' : '')
        : titolo() + (d.modificata ? ' (modificata da te)' : '');
      let azioni = null;
      if (!nellaMail) {
        ed.el.etichettaInvia = h('span', null, etichettaInvio());
        ed.el.invia = h('button', { type: 'button', class: 'pulsante primario', disabled: ed.invio ? true : null, onclick: () => ed.invia() }, icona('invia'), ed.el.etichettaInvia);
        ed.el.rifai = ed.rifaiAperto ? null : h('button', { type: 'button', class: 'pulsante secondario', disabled: ed.invio ? true : null, onclick: () => {
          ed.rifaiAperto = true;
          render();
          const t = document.getElementById(idCampo('istruzioni'));
          if (t) t.focus();
        } }, 'Rifai con istruzioni');
        azioni = h('div', { class: 'azioni-bozza' },
          ed.el.invia,
          ed.el.rifai,
          h('span', { class: 'spazio' }),
          conConferma('Scarta', 'Scartare la bozza?', 'Sì, scarta', () => ed.scarta()));
      }
      return h('div', { class: 'pannello-bozza' + (nellaMail ? ' diretto' : '') + (d.stato === 'errore' ? ' con-errore' : '') },
        h('div', { class: 'testa-bozza' },
          h('h2', null, nellaMail ? icona('matita') : null, intestazione),
          ed.el.salvataggio),
        ed.inviata ? h('p', { class: 'tenue' }, 'Per: ' + (ed.inviata.a || []).join(', ') + ' – ' + (ed.inviata.oggetto || '')) : null,
        sospetta ? messaggioInLinea('Stai rispondendo a una mail segnalata come possibile truffa: non mandare soldi, coordinate bancarie, documenti o password senza aver prima telefonato al mittente a un numero che conosci già.', 'truffa') : null,
        giaRisposto ? messaggioInLinea('Hai già risposto a questa mail (forse da un altro programma). Prima di inviare questa, controlla che serva davvero.', 'avvertenza') : null,
        ed.el.messaggio,
        campo('a', 'A'),
        campo('cc', 'Cc'),
        campo('oggetto', 'Oggetto'),
        testo,
        ed.rifaiAperto ? campoIstruzioni('Cosa deve cambiare? Per esempio: più breve, e proponi lunedì mattina', true, (t) => ed.rifai(t)) : null,
        azioni,
        h('p', { class: 'aiuto' }, 'Prima di spedire Sol mostra sempre destinatari, oggetto e inizio del testo e chiede conferma.'));
    }
    ed.render = render;

    return ed;
  }

  // --- Riassunti ------------------------------------------------------------------------------------

  function testoConRiferimenti(testo) {
    return L.dividiRiferimenti(testo).map((p) => (p.mailId != null
      ? h('a', { href: '#mail/' + p.mailId, class: 'riferimento', title: 'Apri la mail citata' }, icona('posta'), 'apri')
      : p.testo));
  }

  function vistaRiassunti() {
    const oggi = h('div', { class: 'riassunto-testo' }, h('p', { class: 'attesa' }, 'Carico il riassunto di oggi…'));
    const oggiInfo = h('p', { class: 'tenue' });
    const risultato = h('div', { class: 'risultato-riassunto' });
    const recenti = h('ul', { class: 'elenco-semplice' });
    const richiesta = h('textarea', {
      id: 'richiesta-riassunto', rows: '3', placeholder: 'Per esempio: cosa ha scritto Bianchi questa settimana, oppure le ultime mail sul capannone di Bassano',
      onkeydown: (e) => { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); chiedi(); } },
    });
    const pulsante = h('button', { type: 'button', class: 'pulsante primario', onclick: () => chiedi() }, 'Chiedi il riassunto');

    async function caricaOggi() {
      const r = await Api.get('/server/api/riassunti/oggi');
      if (!oggi.isConnected) return;
      if (!r.ok) { svuota(oggi, messaggioInLinea(r.errore)); return; }
      const d = r.dati || {};
      svuota(oggi, d.testo ? h('p', null, testoConRiferimenti(d.testo)) : h('p', { class: 'tenue' }, 'Il riassunto di oggi non è ancora pronto.'));
      oggiInfo.textContent = d.aggiornato_il ? 'Aggiornato ' + L.dataLunga(d.aggiornato_il) + (r.memoria ? ' (copia salvata: il server ora non risponde)' : '') : '';
    }

    async function caricaRecenti() {
      const r = await Api.get('/server/api/riassunti');
      if (!recenti.isConnected || !r.ok || !Array.isArray(r.dati)) return;
      svuota(recenti, r.dati.length === 0 ? h('li', { class: 'tenue' }, 'Nessuna richiesta finora.') : r.dati.map((x) => h('li', null,
        h('button', { type: 'button', class: 'pulsante testo', onclick: () => segui(x.id) }, x.richiesta || 'Riassunto'),
        h('span', { class: 'tenue' }, ' · ' + L.oraBreve(x.creato_il)))));
    }

    async function chiedi() {
      const t = richiesta.value.trim();
      if (!t) { richiesta.focus(); return; }
      if (pulsante.disabled) return;
      pulsante.disabled = true;
      svuota(risultato, h('p', { class: 'attesa' }, 'Sol cerca le mail che servono…'));
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
      svuota(risultato,
        h('h3', null, d.richiesta || 'Riassunto'),
        d.stato === 'errore' ? messaggioInLinea(d.errore || 'Il riassunto non è riuscito. Riprova tra poco.') : null,
        testo ? h('div', { class: 'riassunto-testo' }, h('p', null, testoConRiferimenti(testo))) : null,
        d.stato === 'in_coda' ? h('p', { class: 'attesa' }, 'In coda: Sol comincia tra poco…') : null,
        d.stato === 'in_corso' ? h('p', { class: 'attesa' }, 'Sol sta scrivendo…') : null);
      if (d.stato === 'in_coda' || d.stato === 'in_corso') setTimeout(() => segui(id), T.riassunto);
      else caricaRecenti();
    }

    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('h1', null, 'Riassunti'),
      h('section', { class: 'scheda' }, h('h2', null, 'Il riassunto di oggi'), oggi, oggiInfo),
      h('section', { class: 'scheda' },
        h('h2', null, 'Chiedi un riassunto'),
        h('label', { for: 'richiesta-riassunto' }, 'Cosa vuoi sapere?'),
        richiesta,
        h('div', { class: 'azioni' }, pulsante, h('span', { class: 'aiuto' }, 'Puoi anche dettare: premi Win+H. Per inviare: Ctrl+Invio.')),
        risultato),
      h('section', { class: 'scheda' }, h('h2', null, 'Richieste recenti'), recenti)));
    caricaOggi();
    caricaRecenti();
  }

  // --- Niente resta indietro ---------------------------------------------------------------------

  function vistaIndietro() {
    const zona = h('div', null, h('p', { class: 'attesa' }, 'Carico…'));
    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('h1', null, 'Niente resta indietro'),
      h('p', { class: 'spiega' }, 'Le mail a cui manca una risposta: quelle che aspettano te e quelle per cui aspetti tu.'),
      zona));
    caricaIndietro(zona);
  }

  async function caricaIndietro(zona) {
    const r = await Api.get('/server/api/indietro');
    if (!zona.isConnected) return;
    if (!r.ok) { svuota(zona, messaggioInLinea(r.errore)); return; }
    const d = r.dati || {};
    const aspettanoTe = d.aspettano_te || [];
    const aspettiTu = d.aspetti_tu || [];

    const listaTe = aspettanoTe.length === 0
      ? h('p', { class: 'tenue' }, 'Nessuna: sei in pari.')
      : h('ul', { class: 'elenco-schede' }, aspettanoTe.map(({ mail: m, giorni }) => {
        const riga = h('li', { class: 'scheda-riga' },
          h('div', { class: 'principale' },
            h('p', { class: 'forte' }, nomeMittente(m), h('span', { class: 'tenue' }, ' · da ' + L.giorni(giorni))),
            h('p', null, m.oggetto || '(senza oggetto)'),
            m.riassunto ? h('p', { class: 'tenue' }, m.riassunto) : null),
          h('div', { class: 'azioni' },
            h('a', { class: 'pulsante primario piccolo', href: '#mail/' + m.id }, m.bozza && m.bozza.stato === 'pronta' && !m.risposta_data ? 'Apri (bozza pronta)' : 'Apri e rispondi'),
            h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: async () => {
              const x = await Api.post('/server/api/mail/' + m.id + '/rispondere', { da_rispondere: false });
              if (x.ok) { riga.remove(); avviso('Tolta dalla lista.', 'ok'); } else avviso(x.errore, 'errore');
            } }, 'Non serve rispondere')));
        return riga;
      }));

    const listaTu = aspettiTu.length === 0
      ? h('p', { class: 'tenue' }, 'Nessuna: tutte le tue richieste hanno avuto risposta.')
      : h('ul', { class: 'elenco-schede' }, aspettiTu.map((voce) => {
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
        const riga = h('li', { class: 'scheda-riga colonna' },
          h('div', { class: 'riga' },
            h('div', { class: 'principale' },
              h('p', { class: 'forte' }, 'A ' + (inv.a || []).join(', '), h('span', { class: 'tenue' }, ' · inviata ' + L.giorni(voce.giorni) + ' fa, nessuna risposta')),
              h('p', null, inv.oggetto || '(senza oggetto)'),
              inv.anteprima ? h('p', { class: 'tenue' }, inv.anteprima) : null),
            h('div', { class: 'azioni' },
              h('button', { type: 'button', class: 'pulsante primario piccolo', onclick: apri }, voce.bozza_sollecito ? 'Apri il sollecito' : 'Prepara sollecito'),
              h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: async () => {
                const ed = S.solleciti.get(inv.id);
                if (ed && ed.invio) { avviso('Aspetta: il sollecito sta aspettando la conferma d\'invio.', 'info'); return; }
                const x = await Api.post('/server/api/indietro/' + inv.id + '/ignora');
                if (!x.ok) { avviso(x.errore, 'errore'); return; }
                if (ed) { ed.distruggi(); S.solleciti.delete(inv.id); }
                riga.remove();
                avviso('Tolta dalla lista.', 'ok');
              } }, 'Ignora'))),
          spazioBozza);
        return riga;
      }));

    svuota(zona,
      h('section', { class: 'scheda' }, h('h2', null, 'Aspettano una tua risposta', contatore(aspettanoTe.length)), listaTe),
      h('section', { class: 'scheda' }, h('h2', null, 'Aspetti una risposta', contatore(aspettiTu.length)), listaTu));
  }

  // --- Scadenze -----------------------------------------------------------------------------------

  function vistaScadenze() {
    const zona = h('div', null, h('p', { class: 'attesa' }, 'Carico…'));
    const descr = h('input', { type: 'text', id: 'nuova-descr', placeholder: 'Per esempio: offerta capannone a Rossi', maxlength: '200' });
    const quando = h('input', { type: 'date', id: 'nuova-data' });
    const esito = h('div');
    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('h1', null, 'Scadenze'),
      h('p', { class: 'spiega' }, 'Le date trovate da Sol nelle mail, più quelle che aggiungi tu.'),
      zona,
      h('section', { class: 'scheda' },
        h('h2', null, 'Aggiungi una scadenza'),
        h('div', { class: 'riga-campi' },
          h('div', { class: 'campo largo' }, h('label', { for: 'nuova-descr' }, 'Cosa'), descr),
          h('div', { class: 'campo' }, h('label', { for: 'nuova-data' }, 'Quando'), quando),
          h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
            if (!descr.value.trim() || !quando.value) { svuota(esito, messaggioInLinea('Scrivi cosa scade e scegli il giorno.')); return; }
            const r = await Api.post('/server/api/scadenze', { descrizione: descr.value.trim(), quando: quando.value });
            if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); return; }
            descr.value = ''; quando.value = ''; svuota(esito);
            avviso('Scadenza aggiunta.', 'ok');
            caricaScadenze(zona);
          } }, 'Aggiungi')),
        esito)));
    caricaScadenze(zona);
  }

  async function caricaScadenze(zona) {
    const r = await Api.get('/server/api/scadenze?stato=aperta');
    if (!zona.isConnected) return;
    if (!r.ok) { svuota(zona, messaggioInLinea(r.errore)); return; }
    const lista = (Array.isArray(r.dati) ? r.dati : []).slice().sort((a, b) => String(a.quando).localeCompare(String(b.quando)) || String(a.ora || '').localeCompare(String(b.ora || '')));
    if (lista.length === 0) { svuota(zona, h('section', { class: 'scheda' }, h('p', { class: 'tenue' }, 'Nessuna scadenza aperta.'))); return; }
    const cambia = async (s, dati, frase) => {
      const x = await Api.patch('/server/api/scadenze/' + s.id, dati);
      if (!x.ok) { avviso(x.errore, 'errore'); return; }
      if (frase) avviso(frase, 'ok');
      caricaScadenze(zona);
    };
    svuota(zona, h('section', { class: 'scheda' }, h('ul', { class: 'elenco-schede' }, lista.map((s) => {
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
      return h('li', { class: 'scheda-riga' },
        h('div', { class: 'principale' },
          h('p', { class: 'forte' }, L.giornoScadenza(s.quando) + (s.ora ? ', ore ' + s.ora : '')),
          h('p', null, s.descrizione || '')),
        h('div', { class: 'azioni' },
          s.mail_id ? h('a', { class: 'pulsante testo piccolo', href: '#mail/' + s.mail_id }, 'Apri la mail') : null,
          data,
          h('button', { type: 'button', class: 'pulsante primario piccolo', onclick: () => cambia(s, { stato: 'fatta' }, 'Segnata come fatta.') }, 'Fatta'),
          h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: () => cambia(s, { stato: 'scartata' }, 'Scadenza tolta.') }, 'Togli')));
    }))));
  }

  // --- Notifiche recenti ----------------------------------------------------------------------------

  /** Le notifiche già arrivate: ognuna ricorda la sua mail, anche se è stata cliccata tardi dal Centro notifiche. */
  async function vistaNotifiche() {
    const zona = h('div', null, h('p', { class: 'attesa' }, 'Carico…'));
    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('h1', null, 'Notifiche recenti'),
      h('p', { class: 'spiega' }, 'Le ultime notifiche di Sol. Ognuna apre la sua mail o la sua sezione.'),
      zona));
    const r = await Api.get('/locale/notifiche');
    if (!zona.isConnected) return;
    const lista = r.ok && r.dati && Array.isArray(r.dati.notifiche) ? r.dati.notifiche : [];
    if (!lista.length) { svuota(zona, h('section', { class: 'scheda' }, h('p', { class: 'tenue' }, 'Nessuna notifica da quando Sol è acceso.'))); return; }
    svuota(zona, h('section', { class: 'scheda' }, h('ul', { class: 'elenco-schede' }, lista.map((n) => h('li', { class: 'scheda-riga' },
      h('div', { class: 'principale' },
        h('p', { class: 'forte' }, n.titolo || '', h('span', { class: 'tenue' }, ' · ' + L.oraBreve(n.quando))),
        h('p', null, n.testo || '')),
      h('div', { class: 'azioni' },
        L.destinazioneDaFrammento(n.destinazione) ? h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: () => vaiAlFrammento(n.destinazione) }, 'Apri') : null))))));
  }

  // --- Impostazioni -------------------------------------------------------------------------------

  function vistaImpostazioni() {
    const sezioni = {
      caselle: h('section', { class: 'scheda', id: 'imp-caselle' }),
      gruppi: h('section', { class: 'scheda', id: 'imp-gruppi' }),
      regole: h('section', { class: 'scheda', id: 'imp-regole' }),
      sol: h('section', { class: 'scheda', id: 'imp-sol' }),
      computer: h('section', { class: 'scheda', id: 'imp-computer' }),
      dispositivi: h('section', { class: 'scheda', id: 'imp-dispositivi' }),
      versione: h('section', { class: 'scheda', id: 'imp-versione' }),
    };
    svuota(S.dom.area, h('div', { class: 'pagina' },
      h('h1', null, 'Impostazioni'),
      h('nav', { class: 'indice' },
        [['imp-caselle', 'Caselle'], ['imp-gruppi', 'Gruppi'], ['imp-regole', 'Regole imparate'], ['imp-sol', 'Come lavora Sol'], ['imp-computer', 'Questo computer'], ['imp-dispositivi', 'Dispositivi'], ['imp-versione', 'Versione']]
          .map(([id, t]) => h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => { const el = document.getElementById(id); if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth' }); } }, t))),
      Object.values(sezioni)));
    impCaselle(sezioni.caselle);
    impGruppi(sezioni.gruppi);
    impRegole(sezioni.regole);
    impSol(sezioni.sol);
    impComputer(sezioni.computer);
    impDispositivi(sezioni.dispositivi);
    impVersione(sezioni.versione);
  }

  async function mostraVersione() {
    await vaiVista('impostazioni');
    const s = document.getElementById('imp-versione');
    if (s && s.scrollIntoView) s.scrollIntoView();
  }

  const NOMI_TIPO = { outlook: 'Outlook', gmail: 'Gmail', finta: 'Casella di prova' };
  const NOMI_STATO = { collegata: 'Collegata', in_collegamento: 'In collegamento', errore: 'Non funziona' };

  /** Su un collegamento http (di casa, non protetto) le password non si mandano mai (§15.13). */
  function bloccoPassword() {
    if (!S.motore || !L.senzaProtezione(S.motore.server)) return null;
    return messaggioInLinea('Questo computer è collegato al server con un indirizzo di casa non protetto (http): da lì Sol non manda password. '
      + 'Collega il computer con l\'indirizzo https di Tailscale, per esempio ' + ESEMPIO_SERVER + ' (Impostazioni > Questo computer > Scollega, poi di nuovo), e riprova.', 'avvertenza');
  }

  async function impCaselle(sez) {
    svuota(sez, h('h2', null, 'Caselle di posta'), h('p', { class: 'attesa' }, 'Carico…'));
    const r = await Api.get('/server/api/caselle');
    if (!sez.isConnected) return;
    const caselle = r.ok && Array.isArray(r.dati) ? r.dati : [];
    const zonaAggiungi = h('div', { class: 'zona-aggiungi' });
    const elenco = caselle.length === 0
      ? h('p', { class: 'tenue' }, 'Nessuna casella collegata. Aggiungine una qui sotto.')
      : h('ul', { class: 'elenco-schede' }, caselle.map((c) => {
        const zonaCasella = h('div', { class: 'zona-casella' });
        return h('li', { class: 'scheda-riga colonna' },
          h('div', { class: 'riga' },
            h('div', { class: 'principale' },
              h('p', null, h('span', { class: 'forte' }, c.nome || c.indirizzo || 'Casella'), h('span', { class: 'tenue' }, ' · ' + (NOMI_TIPO[c.tipo] || c.tipo))),
              h('p', { class: 'tenue' }, c.indirizzo || ''),
              h('p', null, h('span', { class: 'segno ' + (c.stato === 'collegata' ? 'ok' : c.stato === 'errore' ? 'urgente' : 'tenue') }, NOMI_STATO[c.stato] || c.stato),
                c.ultimo_controllo ? h('span', { class: 'tenue' }, ' · ultimo controllo ' + L.oraBreve(c.ultimo_controllo)) : null,
                c.attiva_dal ? h('span', { class: 'tenue' }, ' · Sol guarda le mail arrivate dal ' + L.dataLunga(c.attiva_dal)) : null),
              c.errore ? messaggioInLinea(c.errore) : null),
            h('div', { class: 'azioni' },
              // §15.3: una casella in errore si ricollega senza perdere mail, bozze, scadenze e regole.
              c.stato === 'errore' ? h('button', { type: 'button', class: 'pulsante primario piccolo', onclick: () => ricollega(c, zonaCasella, sez) }, 'Ricollega') : null,
              h('button', { type: 'button', class: 'pulsante secondario piccolo', onclick: async () => {
                const x = await Api.post('/server/api/caselle/' + c.id + '/controlla');
                avviso(x.ok ? 'Controllo avviato: le mail nuove arrivano tra poco.' : x.errore, x.ok ? 'ok' : 'errore');
              } }, 'Controlla adesso'),
              h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: async () => {
                const nuovo = await chiediTesto(zonaCasella, 'Nuovo nome per ' + (c.nome || c.indirizzo), c.nome || '');
                if (nuovo == null) return;
                const x = await Api.patch('/server/api/caselle/' + c.id, { nome: nuovo });
                if (!x.ok) avviso(x.errore, 'errore');
                impCaselle(sez);
              } }, 'Rinomina'),
              conConferma('Scollega', 'Sol dimenticherà le mail di questa casella (nella casella vera non cambia niente). Scollegare?', 'Sì, scollega', async () => {
                const x = await Api.elimina('/server/api/caselle/' + c.id);
                if (!x.ok) { avviso(x.errore, 'errore'); return; }
                avviso('Casella scollegata.', 'ok');
                impCaselle(sez);
              }))),
          zonaCasella);
      }));
    svuota(sez,
      h('h2', null, 'Caselle di posta'),
      r.ok ? null : messaggioInLinea(r.errore),
      elenco,
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante secondario', onclick: () => moduloGmail(zonaAggiungi, sez) }, 'Aggiungi Gmail'),
        h('button', { type: 'button', class: 'pulsante secondario', onclick: () => moduloOutlook(zonaAggiungi, sez) }, 'Aggiungi Outlook'),
        h('button', { type: 'button', class: 'pulsante testo', onclick: () => moduloProva(zonaAggiungi, sez) }, 'Aggiungi una casella di prova')),
      zonaAggiungi);
  }

  /** Un campo di testo sul posto, al posto di una finestra a comparsa. Restituisce il testo o null. */
  function chiediTesto(dove, domanda, valore, lunghezza) {
    return new Promise((fine) => {
      const campo = h('input', { type: 'text', value: valore, 'aria-label': domanda, maxlength: String(lunghezza || 80) });
      const chiudi = (esito) => { blocco.remove(); fine(esito); };
      const blocco = h('div', { class: 'chiedi-testo' }, h('label', null, domanda), campo,
        h('div', { class: 'azioni' },
          h('button', { type: 'button', class: 'pulsante primario piccolo', onclick: () => chiudi(campo.value.trim() || null) }, 'Salva'),
          h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => chiudi(null) }, 'Annulla')));
      campo.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') chiudi(campo.value.trim() || null);
        if (e.key === 'Escape') chiudi(null);
      });
      dove.append(blocco);
      campo.focus();
      campo.select();
    });
  }

  function passiPasswordApp() {
    return [
      h('p', null, 'Google non lascia entrare Sol con la password normale: serve una "password per le app", cioè una password a parte, solo per Sol, che puoi togliere quando vuoi.'),
      h('ol', { class: 'passi' },
        h('li', null, 'Apri la pagina di Google ', h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: () => Api.post('/locale/apri', { url: 'https://myaccount.google.com/apppasswords' }) }, '"Password per le app"'), ' ed entra con l\'account della casella.'),
        h('li', null, 'Scrivi un nome, per esempio "Sol", e premi Crea.'),
        h('li', null, 'Google mostra 16 lettere: copiale nel campo qui sotto.')),
      h('p', { class: 'aiuto' }, 'Se Google dice che l\'opzione non è disponibile, prima va accesa la "verifica in due passaggi" dell\'account.'),
    ];
  }

  function moduloGmail(zona, sez) {
    const blocco = bloccoPassword();
    if (blocco) {
      svuota(zona, h('div', { class: 'modulo-casella' }, h('h3', null, 'Aggiungi una casella Gmail'), blocco,
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo', onclick: () => svuota(zona) }, 'Chiudi'))));
      return;
    }
    const indirizzo = h('input', { type: 'email', id: 'gm-indirizzo', placeholder: 'nome@gmail.com', autocomplete: 'off', spellcheck: 'false', 'data-campo': 'indirizzo' });
    const nome = h('input', { type: 'text', id: 'gm-nome', placeholder: 'Per esempio: Personale', maxlength: '40', 'data-campo': 'nome' });
    const password = h('input', { type: 'password', id: 'gm-password', placeholder: '16 lettere, anche con gli spazi', autocomplete: 'new-password', spellcheck: 'false', 'data-campo': 'password_app' });
    const esito = h('div');
    const modulo = h('div', { class: 'modulo-casella' });
    const pulsante = h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
      if (!indirizzo.value.trim() || !password.value.trim()) { svuota(esito, messaggioInLinea('Servono l\'indirizzo Gmail e la password per le app.')); return; }
      if (pulsante.disabled) return;
      pulsante.disabled = true;
      svuota(esito, h('p', { class: 'attesa' }, 'Sol prova a collegarsi a Gmail…'));
      const r = await Api.post('/server/api/caselle', { tipo: 'gmail', indirizzo: indirizzo.value.trim(), nome: nome.value.trim() || indirizzo.value.trim(), password_app: password.value.trim() });
      pulsante.disabled = false;
      if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); evidenziaCampo(modulo, r.dati && r.dati.campo); return; }
      password.value = '';
      avviso('Gmail collegata.', 'ok');
      svuota(zona);
      impCaselle(sez);
    } }, 'Collega Gmail');
    svuota(zona, svuota(modulo,
      h('h3', null, 'Aggiungi una casella Gmail'),
      passiPasswordApp(),
      h('label', { for: 'gm-indirizzo' }, 'Indirizzo Gmail'), indirizzo,
      h('label', { for: 'gm-nome' }, 'Nome della casella in Sol'), nome,
      h('label', { for: 'gm-password' }, 'Password per le app'), password,
      esito,
      h('div', { class: 'azioni' }, pulsante, h('button', { type: 'button', class: 'pulsante testo', onclick: () => svuota(zona) }, 'Annulla'))));
    indirizzo.focus();
  }

  /** §15.3: ricollegare una casella in errore (Gmail: password per le app nuova; Outlook: codice Microsoft). */
  async function ricollega(c, zona, sez) {
    if (c.tipo === 'gmail') {
      const blocco = bloccoPassword();
      if (blocco) {
        svuota(zona, h('div', { class: 'modulo-casella' }, h('h3', null, 'Ricollega ' + (c.nome || c.indirizzo)), blocco,
          h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo', onclick: () => svuota(zona) }, 'Chiudi'))));
        return;
      }
      const password = h('input', { type: 'password', id: 'ric-' + c.id, placeholder: '16 lettere, anche con gli spazi', autocomplete: 'new-password', spellcheck: 'false', 'data-campo': 'password_app' });
      const esito = h('div');
      const modulo = h('div', { class: 'modulo-casella' });
      const pulsante = h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
        if (!password.value.trim()) { password.focus(); return; }
        if (pulsante.disabled) return;
        pulsante.disabled = true;
        svuota(esito, h('p', { class: 'attesa' }, 'Sol riprova a collegarsi a Gmail…'));
        const r = await Api.post('/server/api/caselle/' + c.id + '/ricollega', { password_app: password.value.trim() });
        pulsante.disabled = false;
        if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); evidenziaCampo(modulo, r.dati && r.dati.campo); return; }
        password.value = '';
        avviso('Casella ricollegata: le mail arrivate nel frattempo entrano lo stesso.', 'ok');
        impCaselle(sez);
      } }, 'Ricollega');
      svuota(zona, svuota(modulo,
        h('h3', null, 'Ricollega ' + (c.nome || c.indirizzo)),
        h('p', null, 'Sol tiene mail, bozze, scadenze e regole di questa casella: serve solo una password per le app nuova.'),
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
      attesaOutlook(zona, sez, r.dati, c.id);
      return;
    }
    avviso('Casella ricollegata.', 'ok');
    impCaselle(sez);
  }

  function evidenziaCampo(radice, campo) {
    for (const el of radice.querySelectorAll('[data-campo]')) el.classList.toggle('campo-errato', !!campo && el.getAttribute('data-campo') === campo);
  }

  function moduloOutlook(zona, sez) {
    const nome = h('input', { type: 'text', id: 'ol-nome', value: 'Studio', maxlength: '40' });
    const esito = h('div');
    const pulsante = h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
      if (pulsante.disabled) return;
      pulsante.disabled = true;
      svuota(esito, h('p', { class: 'attesa' }, 'Chiedo il codice a Microsoft…'));
      const r = await Api.post('/server/api/caselle', { tipo: 'outlook', nome: nome.value.trim() || 'Outlook' });
      pulsante.disabled = false;
      if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); return; }
      attesaOutlook(zona, sez, r.dati, r.dati && r.dati.casella && r.dati.casella.id);
    } }, 'Continua');
    svuota(zona, h('div', { class: 'modulo-casella' },
      h('h3', null, 'Aggiungi una casella Outlook o Microsoft 365'),
      h('p', null, 'Sol ti darà un codice da scrivere in una pagina di Microsoft. Lì entri con l\'account della casella e dai il permesso a Sol di leggere e spedire la posta.'),
      h('label', { for: 'ol-nome' }, 'Nome della casella in Sol'), nome,
      esito,
      h('div', { class: 'azioni' }, pulsante, h('button', { type: 'button', class: 'pulsante testo', onclick: () => svuota(zona) }, 'Annulla'))));
    nome.focus();
  }

  function attesaOutlook(zona, sez, dati, casellaId) {
    const stato = h('p', { class: 'attesa' }, 'Sol aspetta che tu finisca nella pagina di Microsoft…');
    svuota(zona, h('div', { class: 'modulo-casella' },
      h('h3', null, 'Ultimo passo nella pagina di Microsoft'),
      h('ol', { class: 'passi' },
        h('li', null, 'Premi il pulsante qui sotto: si apre la pagina di Microsoft nel browser.'),
        h('li', null, 'Scrivi questo codice:'),
        h('li', { class: 'senza-numero' }, h('span', { class: 'codice-grande' }, dati.codice_utente || '')),
        h('li', null, 'Entra con l\'account della casella e accetta. Poi torna qui: Sol se ne accorge da solo.')),
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
          const x = await Api.post('/locale/apri', { url: dati.url_verifica });
          if (!x.ok) avviso(x.errore + ' Indirizzo: ' + (dati.url_verifica || ''), 'errore');
        } }, 'Apri la pagina di Microsoft'),
        h('button', { type: 'button', class: 'pulsante testo', onclick: () => { svuota(zona); impCaselle(sez); } }, 'Chiudi')),
      dati.scade_il ? h('p', { class: 'aiuto' }, 'Il codice vale fino alle ' + L.oraBreve(dati.scade_il) + '.') : null,
      stato));
    const scade = dati.scade_il ? new Date(dati.scade_il).getTime() : Date.now() + 15 * 60000;
    const controlla = async () => {
      if (!stato.isConnected || !casellaId) return;
      if (Date.now() > scade) { svuota(stato, messaggioInLinea('Il codice è scaduto. Chiudi e riprova.')); return; }
      const r = await Api.get('/server/api/caselle/' + casellaId);
      if (!stato.isConnected) return;
      if (r.ok && r.dati && r.dati.stato === 'collegata') {
        avviso('Outlook collegata.', 'ok');
        svuota(zona);
        impCaselle(sez);
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

  function moduloProva(zona, sez) {
    const esito = h('div');
    svuota(zona, h('div', { class: 'modulo-casella' },
      h('h3', null, 'Casella di prova'),
      h('p', null, 'Una casella finta con mail inventate: serve per provare Sol o mostrarlo senza toccare la posta vera.'),
      esito,
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
          const r = await Api.post('/server/api/caselle', { tipo: 'finta', nome: 'Prova' });
          if (!r.ok) { svuota(esito, messaggioInLinea(r.errore)); return; }
          avviso('Casella di prova aggiunta.', 'ok');
          svuota(zona);
          impCaselle(sez);
        } }, 'Aggiungi la casella di prova'),
        h('button', { type: 'button', class: 'pulsante testo', onclick: () => svuota(zona) }, 'Annulla'))));
  }

  async function impGruppi(sez) {
    await leggiGruppi();
    if (!sez.isConnected) return;
    const pieni = S.gruppi.length >= MAX_GRUPPI;
    const nuovo = h('input', { type: 'text', id: 'nuovo-gruppo', placeholder: 'Per esempio: Condomini', maxlength: '40', disabled: pieni ? true : null, 'data-campo': 'nome' });
    // §15.14: la descrizione dice al decisore che mail ci vanno; se manca il server usa il nome.
    const descrizione = h('input', { type: 'text', id: 'nuovo-gruppo-descrizione', placeholder: 'Per esempio: richieste e preventivi degli amministratori di condominio', maxlength: '200', disabled: pieni ? true : null, 'data-campo': 'descrizione' });
    const esito = h('div');
    const ricarica = async () => { await aggiornaContatori(); impGruppi(sez); };
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
    // §17: il colore si sceglie tra i 12 del contratto (il server lo salva per nome; le tinte le decide la pagina).
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
      h('h2', null, 'Gruppi'),
      h('p', { class: 'spiega' }, 'Il numero davanti è il tasto per spostare lì le mail selezionate. Le frecce cambiano l\'ordine. Si possono avere da 2 a ' + MAX_GRUPPI + ' gruppi.'),
      h('p', { class: 'aiuto' }, 'La descrizione dice a Sol che mail vanno in quel gruppo: più è chiara, meglio Sol smista. Il colore si vede nell\'elenco delle mail, qui e sul telefono.'),
      h('ul', { class: 'elenco-schede' }, S.gruppi.map((g, i) => { const col = sceltaColore(g); return h('li', { class: 'scheda-riga con-colore' },
        h('div', { class: 'principale' },
          h('p', null, i < 9 ? h('kbd', null, String(i + 1)) : null, ' ',
            h('span', { class: 'pallino-gruppo col-' + col.attuale, 'aria-hidden': 'true' }),
            h('span', { class: 'forte' }, g.nome),
            h('span', { class: 'tenue' }, ' · ' + NOMI_COLORI[col.attuale].toLowerCase() + ' · ' + L.quante(g.totale || 0, 'mail', 'mail') + (g.cartella ? ' · nella casella: ' + g.cartella : ''))),
          h('p', { class: g.descrizione ? null : 'tenue' }, g.descrizione ? 'Ci vanno: ' + g.descrizione : 'Nessuna descrizione: Sol si basa solo sul nome.'),
          col.riquadro),
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
          g.nome === 'Altro' ? h('span', { class: 'tenue piccolo' }, 'non si toglie') : conConferma('Togli', 'Le sue mail passano ad Altro. Togliere il gruppo?', 'Sì, togli', async () => {
            const r = await Api.elimina('/server/api/gruppi/' + g.id);
            if (!r.ok) avviso(r.errore, 'errore');
            ricarica();
          }))); })),
      h('h3', null, 'Gruppo nuovo'),
      h('div', { class: 'riga-campi' },
        h('div', { class: 'campo' }, h('label', { for: 'nuovo-gruppo' }, 'Nome'), nuovo),
        h('div', { class: 'campo largo' }, h('label', { for: 'nuovo-gruppo-descrizione' }, 'Che mail ci vanno? (una frase)'), descrizione),
        h('button', { type: 'button', class: 'pulsante secondario', disabled: pieni ? true : null, onclick: aggiungi }, 'Aggiungi gruppo')),
      pieni ? h('p', { class: 'aiuto' }, 'Puoi avere al massimo ' + MAX_GRUPPI + ' gruppi: togline uno prima di aggiungerne un altro.') : null,
      esito);
  }

  async function impRegole(sez) {
    svuota(sez, h('h2', null, 'Regole imparate'), h('p', { class: 'attesa' }, 'Carico…'));
    const r = await Api.get('/server/api/regole');
    if (!sez.isConnected) return;
    const regole = r.ok && Array.isArray(r.dati) ? r.dati : [];
    const nomeGruppo = (id) => (S.gruppi.find((g) => g.id === id) || {}).nome || 'un gruppo tolto';
    svuota(sez,
      h('h2', null, 'Regole imparate'),
      h('p', { class: 'spiega' }, 'Quando sposti UNA mail con "Ricordati questo mittente" acceso, Sol si segna la regola. Le regole valgono prima di ogni altra scelta.'),
      r.ok ? null : messaggioInLinea(r.errore),
      regole.length === 0 ? h('p', { class: 'tenue' }, 'Nessuna regola finora.') : h('ul', { class: 'elenco-schede' }, regole.map((x) => h('li', { class: 'scheda-riga' },
        h('div', { class: 'principale' },
          h('p', null, (x.tipo === 'dominio' ? 'Tutte le mail da ' : 'Le mail di '), h('span', { class: 'forte' }, x.valore), ' vanno in ', h('span', { class: 'forte' }, nomeGruppo(x.gruppo_id))),
          h('p', { class: 'tenue' }, (x.origine === 'imparata' ? 'Imparata da una tua correzione' : 'Aggiunta a mano') + (x.creata_il ? ', ' + L.dataLunga(x.creata_il) : ''))),
        h('div', { class: 'azioni' }, h('button', { type: 'button', class: 'pulsante testo piccolo', onclick: async () => {
          const y = await Api.elimina('/server/api/regole/' + x.id);
          if (!y.ok) { avviso(y.errore, 'errore'); return; }
          avviso('Regola tolta.', 'ok');
          impRegole(sez);
        } }, 'Togli'))))));
  }

  async function impSol(sez) {
    svuota(sez, h('h2', null, 'Come lavora Sol'), h('p', { class: 'attesa' }, 'Carico…'));
    const r = await Api.get('/server/api/impostazioni');
    if (!sez.isConnected) return;
    if (!r.ok) { svuota(sez, h('h2', null, 'Come lavora Sol'), messaggioInLinea(r.errore)); return; }
    const imp = r.dati || {};
    const prova = h('input', { type: 'checkbox', id: 'imp-prova', checked: !!imp.modalita_prova, 'data-campo': 'modalita_prova' });
    const soglia = h('input', { type: 'range', id: 'imp-soglia', min: '50', max: '99', step: '1', value: String(Math.round((imp.soglia_sicurezza ?? 0.85) * 100)), 'data-campo': 'soglia_sicurezza' });
    const sogliaTesto = h('span', { class: 'valore' }, soglia.value + '%');
    soglia.addEventListener('input', () => { sogliaTesto.textContent = soglia.value + '%'; });
    // §16.2: tra 0,2 e 0,9, base 0,5.
    const soglia0 = Math.round((imp.soglia_truffa ?? 0.5) * 100);
    const truffa = h('input', { type: 'range', id: 'imp-truffa', min: '20', max: '90', step: '5', value: String(soglia0), 'data-campo': 'soglia_truffa' });
    const truffaTesto = h('span', { class: 'valore' }, truffa.value + '%');
    truffa.addEventListener('input', () => { truffaTesto.textContent = truffa.value + '%'; });
    const gRisposta = h('input', { type: 'number', id: 'imp-gr', min: '1', max: '30', value: String(imp.giorni_risposta ?? 2), 'data-campo': 'giorni_risposta' });
    const gSollecito = h('input', { type: 'number', id: 'imp-gs', min: '1', max: '60', value: String(imp.giorni_sollecito ?? 7), 'data-campo': 'giorni_sollecito' });
    const firma = h('textarea', { id: 'imp-firma', rows: '3', value: imp.firma || '', 'data-campo': 'firma' });
    const tono = h('input', { type: 'text', id: 'imp-tono', value: imp.tono || '', placeholder: 'Per esempio: cortese e diretto', maxlength: '80', 'data-campo': 'tono' });
    const esito = h('div');
    const salva = h('button', { type: 'button', class: 'pulsante primario', onclick: async () => {
      const dati = {};
      if (prova.checked !== !!imp.modalita_prova) dati.modalita_prova = prova.checked;
      const s = Number(soglia.value) / 100;
      if (Math.abs(s - (imp.soglia_sicurezza ?? 0.85)) > 0.001) dati.soglia_sicurezza = s;
      if (Number(truffa.value) !== soglia0) dati.soglia_truffa = Number(truffa.value) / 100;
      if (Number(gRisposta.value) !== imp.giorni_risposta) dati.giorni_risposta = Number(gRisposta.value);
      if (Number(gSollecito.value) !== imp.giorni_sollecito) dati.giorni_sollecito = Number(gSollecito.value);
      if (firma.value !== (imp.firma || '')) dati.firma = firma.value;
      if (tono.value !== (imp.tono || '')) dati.tono = tono.value;
      if (Object.keys(dati).length === 0) { svuota(esito, messaggioInLinea('Non hai cambiato niente.', 'info')); return; }
      if (salva.disabled) return;
      salva.disabled = true;
      const x = await Api.patch('/server/api/impostazioni', dati);
      salva.disabled = false;
      if (!x.ok) { svuota(esito, messaggioInLinea(x.errore)); evidenziaCampo(sez, x.dati && x.dati.campo); return; }
      avviso('Impostazioni salvate.', 'ok');
      await leggiSalute();
      renderBarra();
      if (imp.modalita_prova && dati.modalita_prova === false) await chiediArretrato();
      impSol(sez);
    } }, 'Salva');
    svuota(sez,
      h('h2', null, 'Come lavora Sol'),
      h('div', { class: 'riquadro ' + (imp.modalita_prova ? 'prova' : 'tenue') },
        h('label', { class: 'spunta' }, prova, h('strong', null, ' Modalità prova')),
        h('p', null, 'Accesa: Sol decide i gruppi ma non sposta niente nelle caselle vere, mostra solo cosa farebbe. Spegnila quando ti fidi delle sue scelte: da lì Sol sposterà davvero le mail nuove nelle cartelle (Outlook) o etichette (Gmail). Per quelle già smistate prima, ti chiede. Sol non cancella mai nessuna mail.')),
      h('label', { for: 'imp-soglia' }, 'Quanto deve essere sicura Sol per smistare da sola: ', sogliaTesto),
      soglia,
      h('p', { class: 'aiuto' }, 'Sotto questa soglia la mail finisce in "Da controllare" e decidi tu.'),
      h('label', { for: 'imp-truffa' }, 'Quanto deve sembrare una truffa perché Sol la segnali: ', truffaTesto),
      truffa,
      h('p', { class: 'aiuto' }, 'Più basso: Sol mette l\'etichetta "Possibile truffa" su più mail, anche su qualcuna normale. Più alto: solo sui casi più evidenti. Meglio un\'etichetta in più che una truffa non vista.'),
      h('div', { class: 'riga-campi' },
        h('div', { class: 'campo' }, h('label', { for: 'imp-gr' }, 'Ricordami le mail senza risposta dopo (giorni)'), gRisposta),
        h('div', { class: 'campo' }, h('label', { for: 'imp-gs' }, 'Proponi un sollecito dopo (giorni)'), gSollecito)),
      h('label', { for: 'imp-firma' }, 'Firma in fondo alle risposte'), firma,
      h('label', { for: 'imp-tono' }, 'Tono delle risposte'), tono,
      esito,
      h('div', { class: 'azioni' }, salva));
  }

  /** §15.2: spenta la modalità prova, le mail già smistate si spostano nella casella solo con un sì esplicito. */
  async function chiediArretrato() {
    const r = await Api.get('/server/api/arretrato');
    const n = r.ok && r.dati ? Number(r.dati.da_applicare) || 0 : 0;
    if (n <= 0) return;
    const si = await chiediConferma({
      titolo: 'Spostare anche le mail già smistate?',
      testo: 'Ci sono ' + n + ' mail già smistate rimaste in Posta in arrivo. Vuoi spostarle anche nella casella? Sol tocca solo quelle che sono ancora in Posta in arrivo.',
      si: 'Sì, spostale',
      no: 'No, lasciale dove sono',
    });
    if (!si) { avviso('Le mail già smistate restano dove sono. Sol sposterà solo le prossime.', 'info'); return; }
    const x = await Api.post('/server/api/arretrato/applica', { conferma: true });
    if (!x.ok) { avviso(x.errore, 'errore'); return; }
    const quante = x.dati && x.dati.da_applicare != null ? x.dati.da_applicare : n;
    avviso('Sol sposta ' + quante + ' mail nelle caselle: ci vuole qualche minuto.', 'ok');
  }

  async function impComputer(sez) {
    await leggiStato();
    if (!sez.isConnected) return;
    const m = S.motore || {};
    const avvio = h('input', { type: 'checkbox', id: 'imp-avvio', checked: !!m.avvio_al_login, disabled: m.avvio_disponibile ? null : true, onchange: async (e) => {
      const r = await Api.post('/locale/avvio-login', { attivo: e.target.checked });
      if (!r.ok) { avviso(r.errore, 'errore'); e.target.checked = !e.target.checked; return; }
      avviso(e.target.checked ? 'Sol si aprirà all\'accensione del computer.' : 'Sol non si aprirà più da solo.', 'ok');
    } });
    svuota(sez,
      h('h2', null, 'Questo computer'),
      h('dl', { class: 'intestazioni' },
        h('dt', null, 'Nome'), h('dd', null, m.nome_dispositivo || ''),
        h('dt', null, 'Server'), h('dd', null, (m.server || '') + (L.senzaProtezione(m.server) ? ' (collegamento di casa non protetto)' : '')),
        h('dt', null, 'Ultimo contatto'), h('dd', null, m.ultimo_contatto ? L.dataLunga(m.ultimo_contatto) : 'non ancora')),
      h('label', { class: 'spunta' }, avvio, ' Apri Sol quando accendo il computer'),
      m.avvio_disponibile ? null : h('p', { class: 'aiuto' }, 'Si può scegliere solo nel programma installato dallo zip.'),
      h('p', { class: 'aiuto' }, 'Chiudendo la finestra Sol resta acceso vicino all\'orologio e manda le notifiche. Per chiuderlo del tutto: tasto destro sull\'icona, "Chiudi Sol".'),
      h('div', { class: 'azioni' }, conConferma('Scollega questo computer', 'Il computer non vedrà più la posta finché non lo ricolleghi con un codice nuovo. Scollegare?', 'Sì, scollega', scollegaQuesto)));
  }

  /** Scollega questo computer: revoca sul server e pulizia dei dati qui. Se il server non risponde, lo dice. */
  async function scollegaQuesto() {
    if (!(await puoLasciare())) return;
    const r = await Api.post('/locale/scollega');
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    await leggiStato();
    mostraAbbinamento(r.dati && r.dati.avviso ? r.dati.avviso : 'Computer scollegato.');
  }

  async function impDispositivi(sez) {
    svuota(sez, h('h2', null, 'Dispositivi collegati'), h('p', { class: 'attesa' }, 'Carico…'));
    const r = await Api.get('/server/api/dispositivi');
    if (!sez.isConnected) return;
    const lista = r.ok && Array.isArray(r.dati) ? r.dati : [];
    const nostro = S.motore && S.motore.dispositivo_id;
    svuota(sez,
      h('h2', null, 'Dispositivi collegati'),
      h('p', { class: 'spiega' }, 'Telefoni e computer che vedono la posta di Sol. Se ne perdi uno, scollegalo da qui.'),
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
        h('div', { class: 'azioni' }, h('span', null, 'È uscita la versione ' + a.disponibile + '.'),
          h('button', { type: 'button', class: 'pulsante primario', onclick: scaricaAggiornamento }, a.errore_scaricamento ? 'Riprova a scaricarla' : 'Scaricala')),
        passiAggiornamento(),
      ];
    } else {
      corpo = h('p', { class: 'tenue' }, a.attivo ? 'Sol controlla da solo ogni 12 ore se c\'è una versione nuova e te lo dice con una notifica.' : 'Il controllo delle versioni nuove vale per il programma installato dallo zip.');
    }
    svuota(sez,
      h('h2', null, 'Versione'),
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

  async function scaricaAggiornamento() {
    const r = await Api.post('/locale/scarica-aggiornamento');
    if (!r.ok) { avviso(r.errore, 'errore'); return; }
    avviso('Sol scarica la versione nuova e controlla il file: quando ha finito, te lo dice.', 'info');
    const s = document.getElementById('imp-versione');
    if (s) impVersione(s);
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
