// Banco di prova per l'interfaccia (pc/ui): carica index.html + logica.js + api.js + app.js in jsdom e
// risponde alle richieste con un server finto che imita il server di Sol (contratto v1.1: versione delle
// bozze, rifai, conteggi, arretrato, ricollega...). Dati solo finti (Rossi, Bianchi, @esempio.it).
//
// jsdom non è nel repository: si cerca con require('jsdom') (npm install jsdom in questa cartella), poi nella
// variabile d'ambiente SOL_JSDOM, poi nel file jsdom.percorso accanto a questo (non versionato). Senza jsdom
// i test dell'interfaccia si saltano e lo dicono.
'use strict';
const fs = require('fs');
const path = require('path');

// SOL_UI: un'altra cartella dell'interfaccia (per esempio quella di una versione vecchia, per vedere che i test la bocciano).
const UI = process.env.SOL_UI || path.join(__dirname, '..', '..', 'ui');
const dorme = (ms) => new Promise((r) => setTimeout(r, ms));
const copia = (x) => JSON.parse(JSON.stringify(x));
const INDIRIZZO = /^[^@\s,;<>]+@[^@\s,;<>]+\.[^@\s,;<>]+$/;

function caricaJsdom() {
  try { return require('jsdom'); } catch (e) { /* non installato qui */ }
  const candidati = [process.env.SOL_JSDOM];
  const file = path.join(__dirname, 'jsdom.percorso');
  if (fs.existsSync(file)) candidati.push(fs.readFileSync(file, 'utf8').trim());
  for (const c of candidati.filter(Boolean)) {
    try { return require(c); } catch (e) { /* percorso sbagliato */ }
  }
  return null;
}
const jsdom = caricaJsdom();
const SENZA_JSDOM = jsdom ? false : 'jsdom non trovato: npm install jsdom in pc/prove/js, oppure SOL_JSDOM=<cartella di jsdom>';

function mail(id, extra) {
  return Object.assign({
    id, casella_id: 1, casella: 'studio@esempio.it',
    mittente: { nome: 'Mario Rossi', indirizzo: 'rossi@esempio.it' },
    oggetto: 'Oggetto ' + id, anteprima: 'Anteprima ' + id, riassunto: 'Riassunto ' + id,
    ricevuta_il: new Date(Date.UTC(2026, 9, 10, 8, 0, 0) - (5000 - id) * 60000).toISOString().replace('.000Z', '+00:00'),
    letta: false, risposta_data: false, gruppo_id: 1, gruppo: 'Clienti', da_rispondere: true,
    urgenza: 'normale', sicurezza: 0.6, smistamento: 'smistata', origine_smistamento: 'decisore',
    applicata_in_casella: false, bozza: null, allegati: [], pec: null,
  }, extra || {});
}

function bozza(id, extra) {
  return Object.assign({
    id, mail_id: null, inviata_id: null, tipo: 'risposta', stato: 'pronta', a: ['rossi@esempio.it'], cc: [],
    oggetto: 'Re: Oggetto', testo: 'Buongiorno Mario, testo del modello.', istruzioni: null, modificata: false,
    errore: null, versione: 1,
  }, extra || {});
}

function server() {
  const s = {
    stato: {
      abbinato: true, server: 'https://sol.esempio.ts.net', senza_protezione: false, nome_dispositivo: 'Computer dello studio', dispositivo_id: 7,
      non_riconosciuto: false, raggiungibile: true, irraggiungibile_dal: null, ultimo_contatto: null, urgenti_da_leggere: 0, versione: '1.0.0',
      avvio_al_login: true, avvio_disponibile: true, nome_suggerito: 'Computer STUDIO',
      aggiornamento: { attivo: true, disponibile: null, annunciata: null, scaricamento_in_corso: false, scaricato: null, errore_scaricamento: null },
    },
    salute: { ok: true, versione: '1.0.0', autenticato: true, decisore: { stato: 'pronto' }, scrittore: { stato: 'pronto' }, caselle: [], modalita_prova: true, coda: {} },
    gruppi: [
      { id: 1, nome: 'Clienti', ordine: 1, descrizione: 'un cliente chiede o conferma un lavoro' }, { id: 2, nome: 'Fornitori', ordine: 2 },
      { id: 3, nome: 'Amministrazione e fatture', ordine: 3 }, { id: 4, nome: 'PEC ed enti', ordine: 4 },
      { id: 5, nome: 'Newsletter', ordine: 5 }, { id: 6, nome: 'Altro', ordine: 6 },
    ],
    conteggi: true, // false = server di prima della v1.1 (404 su /api/conteggi)
    mails: [],
    bozze: {},
    regole: [],
    caselle: [],
    impostazioni: { modalita_prova: true, soglia_sicurezza: 0.85, giorni_risposta: 2, giorni_sollecito: 7, firma: 'Cordiali saluti', tono: 'cortese e diretto' },
    arretrato: 0,
    indietro: { aspettano_te: [], aspetti_tu: [] },
    testoSollecito: {},
    notifiche: [],
    prossimaBozza: 101,
    prontaDopoMs: 60,
    ritardo: () => 2,
    risposta: null, // (metodo, url, corpo) => [stato, oggetto] | undefined, per forzare una risposta
    inviaLocale: async () => ({ annullato: true }),
    log: [],
    t0: Date.now(),
  };
  s.gruppoNome = (id) => (s.gruppi.find((g) => g.id === id) || {}).nome || null;
  s.bozzaDiMail = (mailId) => {
    const b = Object.values(s.bozze).filter((x) => x.mail_id === mailId && x.tipo === 'risposta' && x.stato !== 'scartata').sort((a, b2) => a.id - b2.id).pop();
    return b ? { id: b.id, stato: s.matura(b).stato } : null;
  };
  s.formaMail = (m) => Object.assign(copia(m), { bozza: s.bozzaDiMail(m.id), gruppo: s.gruppoNome(m.gruppo_id) });
  s.matura = (b) => {
    if (b.stato === 'in_preparazione' && b._pronta && Date.now() >= b._pronta) {
      b.stato = 'pronta';
      b.testo = b._testo || 'Testo scritto dal modello.';
      b.versione = (b.versione || 0) + 1;
    }
    return b;
  };
  s.formaBozza = (b) => { const o = copia(s.matura(b)); delete o._pronta; delete o._testo; return o; };
  s.nuovaBozza = (campi) => {
    const id = s.prossimaBozza++;
    s.bozze[id] = Object.assign(bozza(id, { stato: 'in_preparazione', testo: '', versione: 1 }), campi, { _pronta: Date.now() + s.prontaDopoMs });
    return s.bozze[id];
  };
  s.conta = () => ({
    da_rispondere: s.mails.filter((m) => m.da_rispondere && !m.risposta_data).length,
    urgenti: s.mails.filter((m) => m.urgenza === 'alta' && (!m.letta || m.da_rispondere)).length,
    da_controllare: s.mails.filter((m) => m.smistamento === 'da_controllare').length,
    da_leggere: s.mails.filter((m) => !m.letta).length,
    bozze_pronte: Object.values(s.bozze).filter((b) => b.stato === 'pronta').length,
    gruppi: Object.fromEntries(s.gruppi.map((g) => [String(g.id), { da_leggere: s.mails.filter((m) => m.gruppo_id === g.id && !m.letta).length, totale: s.mails.filter((m) => m.gruppo_id === g.id).length }])),
  });

  async function rotta(metodo, url, corpo) {
    const forzata = s.risposta && s.risposta(metodo, url, corpo);
    if (forzata) return forzata;
    const [p, q] = url.split('?');
    const qs = new URLSearchParams(q || '');
    let m;
    if (p === '/locale/stato') return [200, copia(s.stato)];
    if (p === '/locale/aggiorna-adesso') return [200, { ok: true }];
    if (p === '/locale/invia') return [200, await s.inviaLocale(corpo)];
    if (p === '/locale/notifiche') return [200, { notifiche: copia(s.notifiche) }];
    if (p === '/locale/scollega') { s.stato.abbinato = false; return [200, { ok: true, revocato_sul_server: true }]; }
    if (p === '/locale/scarica-aggiornamento') return [200, { ok: true }];
    if (p === '/server/api/salute') return [200, copia(s.salute)];
    if (p === '/server/api/conteggi') return s.conteggi ? [200, s.conta()] : [404, { errore: 'Non trovato.', codice: 'non_trovato' }];
    if (p === '/server/api/gruppi' && metodo === 'GET') return [200, s.gruppi.map((g) => Object.assign({ cartella: 'Sol/' + g.nome, da_leggere: s.mails.filter((x) => x.gruppo_id === g.id && !x.letta).length, totale: s.mails.filter((x) => x.gruppo_id === g.id).length }, g))];
    if (p === '/server/api/gruppi' && metodo === 'POST') {
      if (s.gruppi.length >= 16) return [422, { errore: 'Puoi avere al massimo 16 gruppi: togline uno prima di aggiungerne un altro.', codice: 'non_valido' }];
      const g = { id: 100 + s.gruppi.length, nome: corpo.nome, ordine: s.gruppi.length + 1, descrizione: corpo.descrizione || corpo.nome };
      s.gruppi.push(g);
      return [201, g];
    }
    if ((m = /^\/server\/api\/gruppi\/(\d+)$/.exec(p)) && metodo === 'PATCH') {
      const g = s.gruppi.find((x) => x.id === Number(m[1]));
      Object.assign(g, corpo);
      return [200, g];
    }
    if (p === '/server/api/mail' && metodo === 'GET') {
      let l = s.mails.slice().sort((a, b) => (b.ricevuta_il.localeCompare(a.ricevuta_il)) || b.id - a.id);
      if (qs.get('smistamento')) l = l.filter((x) => x.smistamento === qs.get('smistamento'));
      if (qs.get('da_rispondere') === 'true') l = l.filter((x) => x.da_rispondere && !x.risposta_data);
      if (qs.get('urgenza')) l = l.filter((x) => x.urgenza === qs.get('urgenza'));
      if (qs.get('letta')) l = l.filter((x) => !!x.letta === (qs.get('letta') === 'true'));
      if (qs.get('gruppo_id')) l = l.filter((x) => x.gruppo_id === Number(qs.get('gruppo_id')));
      if (qs.get('prima_di')) { const i = l.findIndex((x) => x.id === Number(qs.get('prima_di'))); l = l.slice(i + 1); }
      const lim = Number(qs.get('limite') || 50);
      return [200, { mail: l.slice(0, lim).map(s.formaMail), altre: l.length > lim }];
    }
    if ((m = /^\/server\/api\/mail\/(\d+)$/.exec(p)) && metodo === 'GET') {
      const x = s.mails.find((y) => y.id === Number(m[1]));
      if (!x) return [404, { errore: 'Mail non trovata.', codice: 'non_trovato' }];
      return [200, Object.assign(s.formaMail(x), { destinatari: ['studio@esempio.it'], cc: [], corpo: 'Corpo della mail ' + x.id, probabilita: {} })];
    }
    if ((m = /^\/server\/api\/mail\/(\d+)\/(sposta|conferma|letta|rispondere|bozza)$/.exec(p)) && metodo === 'POST') {
      const x = s.mails.find((y) => y.id === Number(m[1]));
      if (!x) return [404, { errore: 'Mail non trovata.', codice: 'non_trovato' }];
      if (m[2] === 'sposta') {
        Object.assign(x, { gruppo_id: corpo.gruppo_id, smistamento: 'corretta', origine_smistamento: 'utente' });
        if (corpo.impara) s.regole.push({ tipo: 'mittente', valore: x.mittente.indirizzo, gruppo_id: corpo.gruppo_id });
      }
      if (m[2] === 'conferma' && x.smistamento === 'da_controllare') x.smistamento = 'smistata';
      if (m[2] === 'letta') x.letta = corpo.letta;
      if (m[2] === 'rispondere') x.da_rispondere = corpo.da_rispondere;
      if (m[2] === 'bozza') {
        for (const b of Object.values(s.bozze)) if (b.mail_id === x.id && b.tipo === 'risposta' && ['in_preparazione', 'pronta', 'errore'].includes(b.stato)) b.stato = 'scartata';
        const b = s.nuovaBozza({ mail_id: x.id, a: [x.mittente.indirizzo], oggetto: 'Re: ' + x.oggetto, istruzioni: corpo && corpo.istruzioni, _testo: 'Testo scritto dal modello.' });
        return [202, s.formaBozza(b)];
      }
      return [200, s.formaMail(x)];
    }
    if ((m = /^\/server\/api\/bozze\/(\d+)$/.exec(p))) {
      const b = s.bozze[Number(m[1])];
      if (!b) return [404, { errore: 'Bozza non trovata.', codice: 'non_trovato' }];
      if (metodo === 'GET') return [200, s.formaBozza(b)];
      if (metodo === 'PUT') {
        s.matura(b);
        if (b.stato === 'inviata') return [409, { errore: 'Questa bozza è già stata inviata.', codice: 'conflitto' }];
        if (b.stato === 'in_preparazione') return [409, { errore: 'La bozza è ancora in preparazione. Aspetta qualche secondo.', codice: 'conflitto' }];
        if (b.stato === 'scartata') return [409, { errore: 'Questa bozza è stata scartata.', codice: 'conflitto' }];
        for (const [campo, nome] of [['a', 'A'], ['cc', 'Cc']]) {
          for (const ind of corpo[campo] || []) if (!INDIRIZZO.test(ind.trim().toLowerCase())) return [422, { errore: 'L\'indirizzo «' + ind + '» in «' + nome + '» non è scritto bene.', codice: 'non_valido', campo }];
        }
        Object.assign(b, { a: corpo.a, cc: corpo.cc, oggetto: corpo.oggetto, testo: corpo.testo, modificata: true, stato: 'pronta', errore: null, versione: b.versione + 1 });
        return [200, s.formaBozza(b)];
      }
    }
    if ((m = /^\/server\/api\/bozze\/(\d+)\/scarta$/.exec(p))) { const b = s.bozze[Number(m[1])]; b.stato = 'scartata'; b.versione++; return [200, s.formaBozza(b)]; }
    if ((m = /^\/server\/api\/bozze\/(\d+)\/rifai$/.exec(p))) { // §15.5, §15.13
      const b = s.matura(s.bozze[Number(m[1])]);
      if (b.stato === 'inviata') return [409, { errore: 'Questa bozza è già stata inviata.', codice: 'conflitto' }];
      if (b.modificata && !corpo.istruzioni && !corpo.da_zero) return [409, { errore: 'La bozza è stata modificata a mano: per rifarla da zero serve una conferma.', codice: 'conflitto' }];
      Object.assign(b, { stato: 'in_preparazione', istruzioni: corpo.istruzioni, _pronta: Date.now() + s.prontaDopoMs, _testo: (corpo.da_zero ? '' : b.testo + ' ') + 'Rifatta' + (corpo.istruzioni ? ': ' + corpo.istruzioni : '.'), versione: b.versione + 1 });
      if (corpo.da_zero) b.modificata = false;
      return [202, s.formaBozza(b)];
    }
    if (p === '/server/api/indietro') return [200, copia(s.indietro)];
    if ((m = /^\/server\/api\/indietro\/(\d+)\/sollecito$/.exec(p))) {
      const inv = s.indietro.aspetti_tu.find((v) => v.inviata.id === Number(m[1])).inviata;
      const b = s.nuovaBozza({ inviata_id: inv.id, tipo: 'sollecito', a: inv.a.slice(), oggetto: 'Re: ' + inv.oggetto, _testo: s.testoSollecito[inv.id] || 'Le ricordo la mail di qualche giorno fa.' });
      return [202, s.formaBozza(b)];
    }
    if ((m = /^\/server\/api\/indietro\/(\d+)\/ignora$/.exec(p))) return [200, { ok: true }];
    if (p === '/server/api/caselle' && metodo === 'GET') return [200, copia(s.caselle)];
    if ((m = /^\/server\/api\/caselle\/(\d+)\/ricollega$/.exec(p))) {
      const c = s.caselle.find((x) => x.id === Number(m[1]));
      if (c.tipo === 'outlook') { c.stato = 'in_collegamento'; return [202, { casella: copia(c), codice_utente: 'ABCD-EFGH', url_verifica: 'https://microsoft.com/devicelogin', scade_il: new Date(Date.now() + 900000).toISOString() }]; }
      c.stato = 'collegata'; c.errore = null;
      return [200, copia(c)];
    }
    if ((m = /^\/server\/api\/caselle\/(\d+)$/.exec(p)) && metodo === 'GET') return [200, copia(s.caselle.find((x) => x.id === Number(m[1])))];
    if (p === '/server/api/impostazioni' && metodo === 'GET') return [200, copia(s.impostazioni)];
    if (p === '/server/api/impostazioni' && metodo === 'PATCH') {
      if (corpo.giorni_risposta != null && (corpo.giorni_risposta < 1 || corpo.giorni_risposta > 60)) return [422, { errore: 'I giorni per ricordare le mail senza risposta vanno da 1 a 60.', codice: 'non_valido', campo: 'giorni_risposta' }];
      Object.assign(s.impostazioni, corpo);
      s.salute.modalita_prova = s.impostazioni.modalita_prova;
      return [200, copia(s.impostazioni)];
    }
    if (p === '/server/api/arretrato' && metodo === 'GET') return [200, { da_applicare: s.arretrato }];
    if (p === '/server/api/arretrato/applica') {
      if (!corpo || corpo.conferma !== true) return [422, { errore: 'Per spostare le mail già smistate serve la conferma.', codice: 'non_valido' }];
      return [202, { da_applicare: s.arretrato }];
    }
    if (p === '/server/api/regole') return [200, copia(s.regole)];
    if (p === '/server/api/dispositivi') return [200, [{ id: 7, nome: 'Computer dello studio', tipo: 'pc', abbinato_il: '2026-10-01T09:00:00+02:00', ultimo_accesso: null }, { id: 8, nome: 'Telefono', tipo: 'android', abbinato_il: '2026-10-01T09:00:00+02:00', ultimo_accesso: null }]];
    if ((m = /^\/server\/api\/dispositivi\/(\d+)$/.exec(p)) && metodo === 'DELETE') return [200, { ok: true }];
    if (p === '/server/api/riassunti') return [200, []];
    if (p === '/server/api/scadenze') return [200, []];
    return [404, { errore: 'Non trovato.', codice: 'non_trovato' }];
  }

  s.fetch = async (url, opzioni) => {
    // Dopo la fine della prova la pagina è chiusa: le risposte non arrivano più (niente codice su una pagina chiusa).
    if (s.chiuso) return new Promise(() => {});
    const metodo = (opzioni && opzioni.method) || 'GET';
    const corpo = opzioni && opzioni.body ? JSON.parse(opzioni.body) : undefined;
    const voce = { t: Date.now() - s.t0, metodo, url, corpo, fine: null, stato: null };
    s.log.push(voce);
    await dorme(s.ritardo(metodo, url, corpo));
    if (s.chiuso) return new Promise(() => {});
    const [stato, dati] = await rotta(metodo, url, corpo);
    if (s.chiuso) return new Promise(() => {});
    voce.fine = Date.now() - s.t0;
    voce.stato = stato;
    return { status: stato, text: async () => JSON.stringify(dati), headers: { get: () => null } };
  };
  s.richieste = (filtro) => s.log.filter(filtro);
  return s;
}

async function pagina(s, opzioni) {
  const o = opzioni || {};
  const html = fs.readFileSync(path.join(UI, 'index.html'), 'utf8').replace(/<script[^>]*><\/script>/g, '');
  const dom = new jsdom.JSDOM(html, { url: 'https://sol.locale/index.html' + (o.frammento ? '#' + o.frammento : ''), runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  w.Element.prototype.scrollIntoView = function () {};
  w.fetch = s.fetch;
  w.SolTempi = Object.assign({ minuto: 3600000, bozza: 25, salva: 40, outlook: 30, riassunto: 25 }, o.tempi || {});
  const intervalli = [];
  w.setInterval = (fn) => { intervalli.push(fn); return intervalli.length; };
  for (const f of ['logica.js', 'api.js', 'app.js']) w.eval(fs.readFileSync(path.join(UI, f), 'utf8'));
  await dorme(60);
  const d = w.document;
  const p = {
    w, d, s, intervalli,
    attendi: dorme,
    q: (sel, radice) => (radice || d).querySelector(sel),
    tutti: (sel, radice) => Array.from((radice || d).querySelectorAll(sel)),
    pulsante(testo, radice) {
      return p.tutti('button, a', radice).find((b) => b.textContent.trim() === testo || (b.querySelector('.etichetta') && b.querySelector('.etichetta').textContent === testo)) || null;
    },
    async clicTesto(testo, radice) {
      const el = p.pulsante(testo, radice);
      if (!el) throw new Error('non trovo il pulsante: ' + testo);
      el.click();
      await dorme(40);
      return el;
    },
    clic(el, extra) { el.dispatchEvent(new w.MouseEvent('click', Object.assign({ bubbles: true }, extra || {}))); },
    scrivi(el, valore) { el.value = valore; el.dispatchEvent(new w.Event('input', { bubbles: true })); },
    tasto(key, extra, bersaglio) {
      const e = new w.KeyboardEvent('keydown', Object.assign({ key, bubbles: true, cancelable: true }, extra || {}));
      (bersaglio || d.activeElement || d.body).dispatchEvent(e);
    },
    righe: () => p.tutti('li.mail'),
    riga: (id) => p.tutti('li.mail').find((li) => li.dataset.id === String(id)) || null,
    avvisi: () => p.tutti('#avvisi .avviso').map((a) => a.textContent.trim()),
    async aspetta(condizione, ms) {
      const fine = Date.now() + (ms || 3000);
      while (Date.now() < fine) {
        if (condizione()) return true;
        await dorme(15);
      }
      return condizione();
    },
    chiudi() { s.chiuso = true; w.close(); },
  };
  return p;
}

module.exports = { jsdom, SENZA_JSDOM, server, pagina, mail, bozza, dorme, copia };
