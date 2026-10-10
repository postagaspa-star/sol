// I test della logica della pagina (pc/ui/logica.js). Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const L = require(path.join(__dirname, '..', '..', 'ui', 'logica.js'));

const tasto = (key, extra) => Object.assign({ key, ctrl: false, shift: false, alt: false, meta: false, inCampo: false }, extra || {});
const conSelezione = { haSelezione: true, numeroGruppi: 6 };

test('frecce e J/K scorrono, con Maiusc allargano la selezione', () => {
  assert.deepEqual(L.azioneTasto(tasto('ArrowDown'), conSelezione), { tipo: 'scorri', passo: 1 });
  assert.deepEqual(L.azioneTasto(tasto('k'), conSelezione), { tipo: 'scorri', passo: -1 });
  assert.deepEqual(L.azioneTasto(tasto('ArrowUp', { shift: true }), conSelezione), { tipo: 'estendi', passo: -1 });
});

test('i numeri 1-9 spostano nel gruppo con quel numero, solo se esiste e c\'è una selezione', () => {
  assert.deepEqual(L.azioneTasto(tasto('1'), conSelezione), { tipo: 'sposta', indice: 0, smista: true });
  assert.deepEqual(L.azioneTasto(tasto('6'), conSelezione), { tipo: 'sposta', indice: 5, smista: true });
  assert.equal(L.azioneTasto(tasto('7'), conSelezione), null);
  assert.equal(L.azioneTasto(tasto('1'), { haSelezione: false, numeroGruppi: 6 }), null);
  assert.equal(L.azioneTasto(tasto('0'), conSelezione), null);
});

test('Invio è "va bene così", R la bozza, L letta, D da rispondere', () => {
  assert.deepEqual(L.azioneTasto(tasto('Enter'), conSelezione), { tipo: 'va-bene', smista: true });
  assert.deepEqual(L.azioneTasto(tasto('r'), conSelezione), { tipo: 'bozza', smista: true });
  assert.deepEqual(L.azioneTasto(tasto('R'), conSelezione), { tipo: 'bozza', smista: true });
  assert.deepEqual(L.azioneTasto(tasto('l'), conSelezione), { tipo: 'letta', smista: true });
  assert.deepEqual(L.azioneTasto(tasto('d'), conSelezione), { tipo: 'rispondere', smista: true });
  assert.equal(L.azioneTasto(tasto('Enter'), { haSelezione: false }), null);
});

test('nei campi di testo le scorciatoie non scattano (tranne Esc)', () => {
  for (const k of ['1', 'r', 'Enter', 'ArrowDown', 'l', '/']) {
    assert.equal(L.azioneTasto(tasto(k, { inCampo: true }), conSelezione), null, k);
  }
  assert.deepEqual(L.azioneTasto(tasto('Escape', { inCampo: true }), conSelezione), { tipo: 'esci-campo' });
});

test('con Alt o Win niente scorciatoie; Ctrl+A solo sull\'elenco, Ctrl+F sempre', () => {
  assert.equal(L.azioneTasto(tasto('1', { alt: true }), conSelezione), null);
  assert.equal(L.azioneTasto(tasto('r', { meta: true }), conSelezione), null);
  assert.equal(L.azioneTasto(tasto('r', { ctrl: true }), conSelezione), null);
  assert.equal(L.azioneTasto(tasto('a', { ctrl: true }), conSelezione), null, 'fuori dall\'elenco Ctrl+A seleziona il testo');
  assert.deepEqual(L.azioneTasto(tasto('a', { ctrl: true, suElenco: true }), conSelezione), { tipo: 'seleziona-tutto' });
  assert.deepEqual(L.azioneTasto(tasto('f', { ctrl: true }), conSelezione), { tipo: 'cerca' });
  assert.deepEqual(L.azioneTasto(tasto('/'), conSelezione), { tipo: 'cerca' });
  assert.deepEqual(L.azioneTasto(tasto('?'), conSelezione), { tipo: 'aiuto' });
});

test('clic, Ctrl+clic e Maiusc+clic sulla lista', () => {
  const ordine = [10, 11, 12, 13, 14];
  let r = L.selezioneDopoClic(new Set(), ordine, 11, {}, null);
  assert.deepEqual([...r.selezione], [11]);
  assert.equal(r.ancora, 11);
  r = L.selezioneDopoClic(r.selezione, ordine, 13, { ctrl: true }, r.ancora);
  assert.deepEqual([...r.selezione].sort(), [11, 13]);
  r = L.selezioneDopoClic(r.selezione, ordine, 13, { ctrl: true }, r.ancora);
  assert.deepEqual([...r.selezione], [11]);
  assert.equal(r.attiva, null);
  r = L.selezioneDopoClic(new Set([11]), ordine, 14, { shift: true }, 11);
  assert.deepEqual([...r.selezione].sort(), [11, 12, 13, 14]);
  r = L.selezioneDopoClic(new Set([13]), ordine, 10, { shift: true }, 13);
  assert.deepEqual([...r.selezione].sort(), [10, 11, 12, 13]);
});

test('scorrere coi tasti', () => {
  const ordine = [1, 2, 3];
  assert.equal(L.selezioneDopoTasto(new Set(), ordine, null, 1, false, null).attiva, 1);
  assert.equal(L.selezioneDopoTasto(new Set([1]), ordine, 1, 1, false, 1).attiva, 2);
  assert.equal(L.selezioneDopoTasto(new Set([3]), ordine, 3, 1, false, 3).attiva, 3);
  assert.equal(L.selezioneDopoTasto(new Set([3]), ordine, 3, -Infinity, false, 3).attiva, 1);
  const est = L.selezioneDopoTasto(new Set([1]), ordine, 1, 1, true, 1);
  assert.deepEqual([...est.selezione].sort(), [1, 2]);
  assert.equal(L.selezioneDopoTasto(new Set(), [], null, 1, false, null).attiva, null);
});

test('dopo lo smistamento si apre la mail successiva rimasta', () => {
  assert.equal(L.prossimaDopo([1, 2, 3, 4], [2], [1, 3, 4]), 3);
  assert.equal(L.prossimaDopo([1, 2, 3, 4], [2, 3], [1, 4]), 4);
  assert.equal(L.prossimaDopo([1, 2, 3], [3], [1, 2]), 2);
  assert.equal(L.prossimaDopo([1], [1], []), null);
  // La mail resta in elenco (es. "Tutta la posta"): si passa comunque alla successiva.
  assert.equal(L.prossimaDopo([1, 2, 3], [2], [1, 2, 3]), 3);
});

test('si trascina tutta la selezione se la mail presa ne fa parte', () => {
  assert.deepEqual(L.idsTrascinati(5, new Set([4, 5, 6])).sort(), [4, 5, 6]);
  assert.deepEqual(L.idsTrascinati(9, new Set([4, 5])), [9]);
});

test('richieste dell\'elenco per ogni filtro', () => {
  assert.equal(L.queryPosta({ tipo: 'tutte' }, '', null, 50), '?limite=50');
  assert.equal(L.queryPosta({ tipo: 'gruppo', id: 3 }, '', null, 50), '?gruppo_id=3&limite=50');
  assert.equal(L.queryPosta({ tipo: 'da-rispondere' }, '', null, 50), '?da_rispondere=true&limite=50');
  assert.equal(L.queryPosta({ tipo: 'urgenti' }, '', null, 50), '?urgenza=alta&limite=50');
  assert.equal(L.queryPosta({ tipo: 'da-controllare' }, '', null, 50), '?smistamento=da_controllare&limite=50');
  assert.equal(L.queryPosta({ tipo: 'tutte' }, ' Rossi & Bianchi ', 812, 50), '?cerca=Rossi+%26+Bianchi&limite=50&prima_di=812');
});

test('frammenti delle notifiche', () => {
  assert.deepEqual(L.destinazioneDaFrammento('#mail/812'), { vista: 'posta', filtro: null, mailId: 812 });
  assert.deepEqual(L.destinazioneDaFrammento('urgenti'), { vista: 'posta', filtro: { tipo: 'urgenti' }, mailId: null });
  assert.deepEqual(L.destinazioneDaFrammento('#scadenze'), { vista: 'scadenze', filtro: null, mailId: null });
  assert.equal(L.destinazioneDaFrammento('#mail/abc'), null);
  assert.equal(L.destinazioneDaFrammento('#boh'), null);
});

test('i riferimenti [#id] dei riassunti diventano cliccabili', () => {
  assert.deepEqual(L.dividiRiferimenti('Rossi chiede l\'offerta [#812] e Bianchi conferma [#815].'), [
    { testo: 'Rossi chiede l\'offerta ' },
    { mailId: 812 },
    { testo: ' e Bianchi conferma ' },
    { mailId: 815 },
    { testo: '.' },
  ]);
  assert.deepEqual(L.dividiRiferimenti('niente'), [{ testo: 'niente' }]);
  assert.deepEqual(L.dividiRiferimenti('[#abc] [#]'), [{ testo: '[#abc] [#]' }]);
  assert.deepEqual(L.dividiRiferimenti(''), []);
});

test('indirizzi dei campi A e Cc', () => {
  assert.deepEqual(L.elencoIndirizzi('rossi@esempio.it, Bianchi <bianchi@esempio.it>; neri@esempio.it\n'), ['rossi@esempio.it', 'bianchi@esempio.it', 'neri@esempio.it']);
  assert.deepEqual(L.elencoIndirizzi(' , ;'), []);
  assert.deepEqual(L.indirizziStrani('rossi@esempio.it, bianchi, verdi@'), ['bianchi', 'verdi@']);
});

test('ore e date in italiano', () => {
  const adesso = new Date(2026, 9, 10, 12, 0); // sabato 10 ottobre 2026
  assert.equal(L.oraBreve(new Date(2026, 9, 10, 9, 5).toISOString(), adesso), '09:05');
  assert.equal(L.oraBreve(new Date(2026, 9, 9, 18, 0).toISOString(), adesso), 'ieri');
  assert.equal(L.oraBreve(new Date(2026, 9, 6, 8, 0).toISOString(), adesso), 'mar 6');
  assert.equal(L.oraBreve(new Date(2026, 7, 3, 8, 0).toISOString(), adesso), '3 ago');
  assert.equal(L.oraBreve(new Date(2025, 11, 24, 8, 0).toISOString(), adesso), '24/12/25');
  assert.equal(L.oraBreve('non una data', adesso), '');
  assert.equal(L.giornoScadenza('2026-10-11', adesso), 'domani, domenica 11 ottobre');
  assert.equal(L.giornoScadenza('2026-10-10', adesso), 'oggi, sabato 10 ottobre');
  assert.equal(L.giornoScadenza('2026-10-01', adesso), 'giovedì 1 ottobre (passata)');
  assert.equal(L.dataLunga(new Date(2026, 9, 10, 9, 30).toISOString()), 'sabato 10 ottobre 2026, 09:30');
  assert.equal(L.giorni(1), '1 giorno');
  assert.equal(L.giorni(8), '8 giorni');
});

test('i gruppi seguono l\'ordine del server', () => {
  const g = L.gruppiOrdinati([{ id: 3, nome: 'Altro', ordine: 6 }, { id: 1, nome: 'Clienti', ordine: 1 }, { id: 2, nome: 'Fornitori', ordine: 2 }]);
  assert.deepEqual(g.map((x) => x.id), [1, 2, 3]);
});

test('un tasto tenuto premuto (ripetizione automatica) scorre ma non ripete le azioni', () => {
  for (const k of ['Enter', '1', '3', 'r', 'l', 'd', '/', '?']) {
    assert.equal(L.azioneTasto(tasto(k, { ripetuto: true }), conSelezione), null, k);
  }
  assert.deepEqual(L.azioneTasto(tasto('ArrowDown', { ripetuto: true }), conSelezione), { tipo: 'scorri', passo: 1 });
  assert.deepEqual(L.azioneTasto(tasto('ArrowUp', { ripetuto: true, shift: true }), conSelezione), { tipo: 'estendi', passo: -1 });
  assert.equal(L.azioneTasto(tasto('a', { ctrl: true, suElenco: true, ripetuto: true }), conSelezione), null);
});

test('la ricarica silenziosa unisce: nuove in cima, vecchie caricate e selezionate restano', () => {
  const m = (id, ora) => ({ id, ricevuta_il: '2026-10-10T' + ora + ':00+02:00' });
  const vecchie = [m(10, '10:00'), m(9, '09:00'), m(8, '08:00'), m(7, '07:00'), m(6, '06:00')];
  // prima pagina fresca (limite 3): arriva una nuova (11), la 9 è uscita dal filtro
  const u = L.unisciElenco(vecchie, [m(11, '11:00'), m(10, '10:00'), m(8, '08:00')], true, false, new Set([6]));
  assert.deepEqual(u.mail.map((x) => x.id), [11, 10, 8, 7, 6], 'la 9 esce, le più vecchie della pagina restano');
  assert.equal(u.altre, false, 'le altre sono quelle di prima, oltre l\'ultima caricata');
  const tenuta = L.unisciElenco(vecchie, [m(11, '11:00'), m(10, '10:00'), m(8, '08:00')], true, true, new Set([9]));
  assert.deepEqual(tenuta.mail.map((x) => x.id), [11, 10, 9, 8, 7, 6], 'una selezionata uscita dal filtro resta finché è selezionata');
  const tutto = L.unisciElenco(vecchie, [m(10, '10:00')], false, true, new Set([7]));
  assert.deepEqual(tutto.mail.map((x) => x.id), [10, 7], 'senza altre pagine la fresca è tutto (più le selezionate)');
  assert.deepEqual(L.unisciElenco(vecchie, [], false, false, new Set()).mail, []);
  const aggiornata = L.unisciElenco([{ id: 1, ricevuta_il: 'x', letta: false }], [{ id: 1, ricevuta_il: 'x', letta: true }], false, false, new Set());
  assert.equal(aggiornata.mail[0].letta, true, 'i dati freschi vincono');
});

test('esito delle azioni su più mail in una frase sola', () => {
  assert.equal(L.esitoMultiplo(5, 0, 'spostate', null), null);
  assert.equal(L.esitoMultiplo(7, 3, 'spostate', 'La casella non risponde.'), '7 spostate, 3 no: La casella non risponde.');
  assert.equal(L.esitoMultiplo(0, 1, 'spostate', 'La casella non risponde.'), 'Non è riuscito: La casella non risponde.');
  assert.equal(L.esitoMultiplo(0, 4, 'spostate', null), 'Non è riuscito per nessuna delle 4 mail.');
});

test('http solo per la rete di casa (192.168, 10, 172.16-31)', () => {
  for (const ok of ['192.168.1.51', '10.0.0.7', '172.16.0.1', '172.31.255.254']) assert.equal(L.indirizzoDiCasa(ok), true, ok);
  for (const no of ['172.32.0.1', '172.15.0.1', '8.8.8.8', 'sol.esempio.ts.net', '192.168.1', '300.1.1.1', '']) assert.equal(L.indirizzoDiCasa(no), false, no);
  assert.equal(L.senzaProtezione('http://192.168.1.51:8200'), true);
  assert.equal(L.senzaProtezione('https://sol.esempio.ts.net'), false);
});

test('api.js: l\'abbinamento aspetta almeno 30 secondi, l\'invio non ha limite di tempo', () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const attese = [];
  const contesto = {
    window: {},
    AbortController: class { constructor() { this.signal = {}; } abort() {} },
    fetch: () => new Promise(() => {}),
    setTimeout: (fn, ms) => { attese.push(ms); return attese.length; },
    clearTimeout: () => {},
  };
  vm.createContext(contesto);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'api.js'), 'utf8'), contesto);
  const api = contesto.window.SolApi;
  api.post('/locale/abbina', {});
  assert.ok(attese.pop() >= 30000, 'abbinamento: almeno 30 secondi (il motore smette a 30)');
  api.post('/locale/scollega', {});
  assert.ok(attese.pop() >= 30000);
  const prima = attese.length;
  api.post('/locale/invia', { bozza_id: 1 });
  assert.equal(attese.length, prima, 'nessun limite mentre la finestra di conferma è aperta');
  api.get('/locale/stato');
  assert.equal(attese.pop(), 10000);
});

test('filtro "Possibili truffe" (§16)', () => {
  assert.equal(L.queryPosta({ tipo: 'truffe' }, '', null, 50), '?sospetta=true&limite=50');
  assert.deepEqual(L.destinazioneDaFrammento('#truffe'), { vista: 'posta', filtro: { tipo: 'truffe' }, mailId: null });
  assert.equal(L.FILTRI.truffe.titolo, 'Possibili truffe');
});
