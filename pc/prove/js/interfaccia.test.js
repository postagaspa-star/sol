// I test dell'interfaccia vera (pc/ui) in jsdom, con un server finto. Riproducono i difetti trovati dalla
// revisione (numeri 1-19) e le novità del contratto v1.1: ognuno falliva con la pagina di prima.
// Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };

async function conMailEBozza(extraMail, extraBozza) {
  const s = B.server();
  s.mails = [B.mail(812, Object.assign({ letta: true, oggetto: 'Offerta capannone Bassano' }, extraMail || {})), B.mail(811, { letta: true, oggetto: 'Altra mail', mittente: { nome: 'Luca Bianchi', indirizzo: 'bianchi@esempio.it' } })];
  s.bozze[55] = B.bozza(55, Object.assign({ mail_id: 812, oggetto: 'Re: Offerta capannone Bassano', versione: 3 }, extraBozza || {}));
  return s;
}

async function apriPrima(p) {
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('#bozza-testo'));
}

function solleciti() {
  const s = B.server();
  s.indietro = {
    aspettano_te: [],
    aspetti_tu: [
      { inviata: { id: 77, casella_id: 1, a: ['neri@esempio.it'], oggetto: 'Offerta compressore', inviata_il: '2026-10-02T10:00:00+02:00', anteprima: '' }, giorni: 8, bozza_sollecito: null },
      { inviata: { id: 78, casella_id: 1, a: ['bianchi@esempio.it'], oggetto: 'Preventivo impianto', inviata_il: '2026-10-01T10:00:00+02:00', anteprima: '' }, giorni: 9, bozza_sollecito: null },
    ],
  };
  s.testoSollecito = { 77: 'Gentile Neri, le ricordo la nostra offerta per il compressore.', 78: 'Gentile Bianchi, le ricordo il preventivo per l\'impianto.' };
  return s;
}

async function apriDueSolleciti(p) {
  await p.clicTesto('Niente resta indietro');
  await p.aspetta(() => p.tutti('button').filter((b) => b.textContent === 'Prepara sollecito').length === 2);
  const prepara = p.tutti('button').filter((b) => b.textContent === 'Prepara sollecito');
  prepara[0].click();
  await p.aspetta(() => p.q('textarea.testo-bozza', p.tutti('.spazio-bozza')[0]));
  prepara[1].click();
  await p.aspetta(() => p.q('textarea.testo-bozza', p.tutti('.spazio-bozza')[1]));
  return p.tutti('.spazio-bozza');
}

// --- 1. Due solleciti aperti -----------------------------------------------------------------

test('1. due solleciti aperti: modifiche, Invia e Scarta di uno restano sulla sua bozza', opz, async () => {
  const s = solleciti();
  const chieste = [];
  s.inviaLocale = async (corpo) => { chieste.push(corpo.bozza_id); return { annullato: true }; };
  const p = await B.pagina(s);
  const spazi = await apriDueSolleciti(p);
  const id = p.tutti('[id]').map((el) => el.id);
  assert.equal(new Set(id).size, id.length, 'niente id HTML doppi');

  const testoNeri = p.q('textarea.testo-bozza', spazi[0]);
  assert.match(testoNeri.value, /Neri/);
  p.scrivi(testoNeri, testoNeri.value + ' Per lei confermo lo sconto del 15%.');
  assert.ok(await p.aspetta(() => /sconto/.test(s.bozze[101].testo || '')), 'il ritocco va sulla bozza di Neri');
  assert.doesNotMatch(s.bozze[102].testo, /sconto/);

  p.q('.azioni-bozza button.primario', spazi[0]).click();
  await p.aspetta(() => chieste.length === 1);
  assert.deepEqual(chieste, [101], 'Invia di Neri manda in conferma la bozza di Neri');

  p.q('.azioni-bozza button.primario', spazi[1]).click();
  await p.aspetta(() => chieste.length === 2);
  assert.deepEqual(chieste, [101, 102]);
  assert.doesNotMatch(s.bozze[102].testo, /sconto/, 'la bozza di Bianchi resta di Bianchi');

  await p.clicTesto('Scarta', spazi[0]);
  await p.clicTesto('Sì, scarta', spazi[0]);
  await p.aspetta(() => s.bozze[101].stato === 'scartata');
  assert.equal(s.bozze[101].stato, 'scartata');
  assert.equal(s.bozze[102].stato, 'pronta', 'Scarta di Neri non tocca Bianchi');
  p.chiudi();
});

test('1. invio dal secondo sollecito dopo aver ritoccato il primo: parte il testo giusto', opz, async () => {
  const s = solleciti();
  let partito = null;
  s.inviaLocale = async (corpo) => {
    const b = s.bozze[corpo.bozza_id];
    partito = { a: b.a.slice(), testo: b.testo };
    b.stato = 'inviata';
    return { inviata: true, bozza: B.copia(b) };
  };
  const p = await B.pagina(s);
  const spazi = await apriDueSolleciti(p);
  const tNeri = p.q('textarea.testo-bozza', spazi[0]);
  p.scrivi(tNeri, tNeri.value + ' Sconto riservato del 15%.');
  await p.aspetta(() => /Sconto/.test(s.bozze[101].testo || ''));
  p.q('.azioni-bozza button.primario', spazi[1]).click();
  await p.aspetta(() => partito);
  assert.deepEqual(partito.a, ['bianchi@esempio.it']);
  assert.match(partito.testo, /Bianchi/);
  assert.doesNotMatch(partito.testo, /Sconto/);
  p.chiudi();
});

// --- 2. La conferma mostra quello che parte ---------------------------------------------------

test('2. durante la conferma i campi sono bloccati e nessun salvataggio parte; si manda la versione', opz, async () => {
  const s = await conMailEBozza();
  let durante = null;
  const inizio = { put: 0 };
  s.inviaLocale = async (corpo) => {
    durante = { corpo, put: s.log.filter((x) => x.metodo === 'PUT').length };
    await B.dorme(250);
    return { annullato: true };
  };
  const p = await B.pagina(s);
  await apriPrima(p);
  p.scrivi(p.q('#bozza-cc'), 'bianchi@esempio.it');
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => durante);
  const cc = p.q('#bozza-cc');
  assert.equal(cc.readOnly, true, 'Cc in sola lettura durante la conferma');
  assert.equal(p.q('#bozza-testo').readOnly, true);
  p.scrivi(cc, 'bianchi@esempio.it, verdi@esempio.it');
  await B.dorme(320);
  assert.equal(s.log.filter((x) => x.metodo === 'PUT').length, durante.put, 'nessun PUT mentre la finestra è aperta');
  assert.deepEqual(s.bozze[55].cc, ['bianchi@esempio.it']);
  assert.equal(durante.corpo.versione, s.bozze[55].versione, 'la pagina dice quale versione ha mostrato');
  assert.equal(p.q('#bozza-cc').readOnly, false, 'dopo la conferma si sblocca');
  assert.equal(inizio.put, 0);
  p.chiudi();
});

test('2. se la bozza è cambiata (409) la pagina mostra la versione nuova da ricontrollare', opz, async () => {
  const s = await conMailEBozza();
  s.inviaLocale = async () => ({ errore: 'La risposta è cambiata dopo che l\'hai controllata: ricontrollala prima di inviarla.', codice: 'conflitto', bozza: Object.assign(B.copia(s.bozze[55]), { testo: 'Testo cambiato dal telefono.', versione: 9 }) });
  s.risposta = (metodo, url) => (url === '/locale/invia' ? undefined : undefined);
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => p.q('#bozza-testo') && p.q('#bozza-testo').value === 'Testo cambiato dal telefono.');
  assert.equal(p.q('#bozza-testo').value, 'Testo cambiato dal telefono.');
  assert.match(p.q('.sezione-bozza').textContent, /ricontrollala/);
  p.chiudi();
});

// --- 3. Tasti tenuti premuti e un'azione alla volta -------------------------------------------

async function daControllare() {
  const s = B.server();
  s.mails = [901, 902, 903, 904, 905, 906].map((id) => B.mail(id, { smistamento: 'da_controllare', letta: false, mittente: { nome: 'Mittente ' + id, indirizzo: 'm' + id + '@esempio.it' } }));
  s.ritardo = (metodo) => (metodo === 'POST' ? 15 : 2);
  const p = await B.pagina(s);
  await p.clicTesto('Da controllare');
  await p.aspetta(() => p.righe().length === 6);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('.oggetto-grande'));
  return { s, p };
}

for (const [tasto, azione] of [['Enter', 'conferma'], ['3', 'sposta']]) {
  test('3. tasto ' + tasto + ' tenuto premuto: l\'azione parte una volta sola', opz, async () => {
    const { s, p } = await daControllare();
    s.log.length = 0;
    p.tasto(tasto);
    for (let i = 0; i < 9; i++) { await B.dorme(10); p.tasto(tasto, { repeat: true }); }
    await B.dorme(400);
    const toccate = [...new Set(s.log.filter((x) => x.metodo === 'POST' && x.url.endsWith('/' + azione)).map((x) => x.url.split('/')[4]))];
    assert.deepEqual(toccate, ['906'], 'solo la mail aperta');
    assert.ok(s.regole.length <= 1);
    p.chiudi();
  });
}

test('3. un secondo tasto mentre la prima azione è in corso non fa niente', opz, async () => {
  const { s, p } = await daControllare();
  s.ritardo = (metodo, url) => (url.endsWith('/sposta') ? 150 : 2);
  s.log.length = 0;
  p.tasto('3');
  await B.dorme(30);
  p.tasto('4');
  await B.dorme(500);
  assert.equal(s.log.filter((x) => x.url.endsWith('/sposta')).length, 1);
  p.chiudi();
});

// --- 4. Spostamento lento: la pagina non salta e i tasti restano testo ---------------------------

test('4. spostamento lento di 8 mail: la mail aperta e il campo in cui si scrive restano', opz, async () => {
  const s = B.server();
  for (let id = 1012; id > 1000; id--) s.mails.push(B.mail(id, { letta: true, mittente: { nome: 'M' + id, indirizzo: 'm' + id + '@esempio.it' } }));
  s.mails.push(B.mail(990, { letta: true, oggetto: 'Sopralluogo Bassano' }));
  s.bozze[55] = B.bozza(55, { mail_id: 990, oggetto: 'Re: Sopralluogo Bassano', testo: 'Buongiorno Mario,' });
  s.ritardo = (metodo, url) => (metodo === 'POST' && url.endsWith('/sposta') ? 40 : 2);
  const p = await B.pagina(s);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('.oggetto-grande'));
  p.clic(p.righe()[7], { shiftKey: true });
  await p.aspetta(() => p.q('.selezione-multipla'));
  p.tasto('5', {}, p.d.body);
  await p.aspetta(() => p.q('.velo'));
  assert.match(p.q('.velo').textContent, /Spostare 8 mail in Newsletter/);
  await p.clicTesto('Sposta 8 mail', p.q('.velo'));
  await B.dorme(60);
  p.clic(p.riga(990));
  await p.aspetta(() => p.q('#bozza-testo'));
  const testo = p.q('#bozza-testo');
  testo.focus();
  s.log.length = 0;
  for (const ch of ' ci vediamo lunedi 3 alle 10') { p.tasto(ch, {}, p.d.activeElement); await B.dorme(12); }
  await p.aspetta(() => s.mails.filter((m) => m.gruppo_id === 5).length === 8, 3000);
  await B.dorme(150);
  assert.equal(p.d.activeElement, testo, 'il fuoco resta nel campo della risposta');
  assert.ok(testo.isConnected);
  assert.equal(p.q('.oggetto-grande').textContent, 'Sopralluogo Bassano', 'la pagina non salta su un\'altra mail');
  const azioni = s.log.filter((x) => x.metodo !== 'GET' && !x.url.startsWith('/locale/') && !x.url.endsWith('/sposta'));
  assert.deepEqual(azioni.map((x) => x.url), [], 'nessuna scorciatoia mentre si scrive');
  assert.deepEqual(s.regole, [], 'con più mail insieme niente regole');
  p.chiudi();
});

// --- 5. Modifiche non salvate -----------------------------------------------------------------

test('5. salvataggio impossibile: non si cambia mail, lo si dice, e si può scartare', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s);
  await apriPrima(p);
  p.scrivi(p.q('#bozza-a'), 'rossi@esempio');
  p.scrivi(p.q('#bozza-testo'), 'Testo scritto a mano per dieci minuti.');
  await B.dorme(120);
  assert.equal(s.log.filter((x) => x.metodo === 'PUT').length, 0, 'un indirizzo sbagliato non parte nemmeno');
  assert.match(p.q('#bozza-salvataggio').textContent, /Non salvata/);
  assert.ok(p.q('#bozza-a').classList.contains('campo-errato'));
  p.clic(p.righe()[1]);
  await B.dorme(120);
  assert.equal(p.q('.oggetto-grande').textContent, 'Offerta capannone Bassano', 'si resta sulla mail');
  assert.equal(p.q('#bozza-testo').value, 'Testo scritto a mano per dieci minuti.');
  assert.ok(p.pulsante('Scarta le modifiche'), 'il riquadro chiede cosa fare');
  assert.ok(p.avvisi().some((a) => /non è salvata/.test(a)));
  await p.clicTesto('Scarta le modifiche');
  await p.aspetta(() => p.q('.oggetto-grande') && p.q('.oggetto-grande').textContent === 'Altra mail');
  assert.equal(p.q('.oggetto-grande').textContent, 'Altra mail', 'dopo aver scartato si va dove si voleva');
  p.chiudi();
});

test('5. server che rifiuta il salvataggio: si resta sulla mail', opz, async () => {
  const s = await conMailEBozza();
  s.risposta = (metodo) => (metodo === 'PUT' ? [502, { errore: 'Il server di casa in questo momento non risponde bene.', codice: 'errore' }] : undefined);
  const p = await B.pagina(s);
  await apriPrima(p);
  p.scrivi(p.q('#bozza-testo'), 'Testo nuovo.');
  p.clic(p.righe()[1]);
  await B.dorme(150);
  assert.equal(p.q('.oggetto-grande').textContent, 'Offerta capannone Bassano');
  assert.equal(p.q('#bozza-testo').value, 'Testo nuovo.');
  p.chiudi();
});

test('5. prima di chiudere la finestra la pagina salva e dice com\'è andata', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s, { tempi: { salva: 100000 } });
  await apriPrima(p);
  assert.equal(p.w.solSalvaPrimaDiChiudere(), 'pulito');
  p.scrivi(p.q('#bozza-testo'), 'Ultime modifiche.');
  assert.equal(p.w.solSalvaPrimaDiChiudere(), 'in_corso');
  await p.aspetta(() => p.w.solStatoSalvataggio() !== 'in_corso');
  assert.equal(p.w.solStatoSalvataggio(), 'pulito');
  assert.equal(s.bozze[55].testo, 'Ultime modifiche.');
  p.chiudi();
});

// --- 6. R mentre la bozza si carica --------------------------------------------------------------

test('6. R mentre la bozza si carica non la sostituisce', opz, async () => {
  const s = await conMailEBozza({}, { modificata: true, testo: 'Testo corretto a mano da Manuel.' });
  s.ritardo = (metodo, url) => (metodo === 'GET' && url === '/server/api/bozze/55' ? 300 : 2);
  const p = await B.pagina(s);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('.sezione-bozza'));
  p.tasto('r', {}, p.d.body);
  await B.dorme(450);
  assert.equal(s.log.filter((x) => x.url.endsWith('/bozza') || x.url.endsWith('/rifai')).length, 0);
  assert.equal(s.bozze[55].stato, 'pronta');
  assert.equal(p.q('#bozza-testo').value, 'Testo corretto a mano da Manuel.');
  p.chiudi();
});

// --- 7. Computer non più riconosciuto -------------------------------------------------------------

test('7. /api/salute con autenticato false: striscia fissa "collega di nuovo"', opz, async () => {
  const s = B.server();
  s.salute = { ok: true, versione: '1.0.0', autenticato: false };
  const p = await B.pagina(s);
  assert.match(p.q('.strisce').textContent, /non riconosce più questo computer/);
  assert.ok(p.pulsante('Collega di nuovo'));
  p.chiudi();
});

test('7. un 401 da una richiesta qualsiasi mostra subito la striscia', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(812)];
  s.risposta = (metodo, url) => (url.startsWith('/server/api/mail/812') ? [401, { errore: 'Questo dispositivo non è abbinato o il suo accesso è stato revocato.', codice: 'non_autorizzato' }] : undefined);
  const p = await B.pagina(s);
  p.clic(p.righe()[0]);
  await p.aspetta(() => /non riconosce più/.test(p.q('.strisce').textContent));
  assert.match(p.q('.strisce').textContent, /non riconosce più questo computer/);
  p.chiudi();
});

test('7. "Scollega" su (questo computer) pulisce anche i dati qui (/locale/scollega)', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => /questo computer/.test((p.q('#imp-dispositivi') || {}).textContent || ''));
  const sez = p.q('#imp-dispositivi');
  const riga = p.tutti('li', sez).find((li) => /questo computer/.test(li.textContent));
  await p.clicTesto('Scollega', riga);
  await p.clicTesto('Sì, scollega', riga);
  await p.aspetta(() => s.log.some((x) => x.url === '/locale/scollega'));
  assert.ok(s.log.some((x) => x.url === '/locale/scollega'));
  assert.ok(!s.log.some((x) => x.metodo === 'DELETE' && x.url === '/server/api/dispositivi/7'));
  p.chiudi();
});

// --- 8. La ricarica del minuto ---------------------------------------------------------------------

test('8. la ricarica del minuto non toglie le mail caricate né la selezione', opz, async () => {
  const s = B.server();
  for (let id = 1000; id > 940; id--) s.mails.push(B.mail(id, { letta: true, mittente: { nome: 'M' + id, indirizzo: 'm' + id + '@esempio.it' } }));
  const p = await B.pagina(s);
  await p.clicTesto('Mostra le mail più vecchie');
  await p.aspetta(() => p.righe().length === 60);
  for (const i of [52, 54, 56]) { p.clic(p.righe()[i], { ctrlKey: true }); await B.dorme(30); }
  assert.match(p.q('.info-selezione').textContent, /^3 mail selezionate/);
  s.mails.push(B.mail(1001, { letta: false, oggetto: 'Mail nuova' }));
  await p.intervalli[0]();
  await B.dorme(60);
  assert.equal(p.righe().length, 61, 'la nuova entra in cima, le vecchie restano');
  assert.equal(p.righe()[0].dataset.id, '1001');
  assert.match(p.q('.info-selezione').textContent, /^3 mail selezionate/);
  assert.equal(p.q('.selezione-multipla .grande').textContent, '3 mail selezionate');
  p.chiudi();
});

// --- 9. Ctrl+A, più di 5 mail, regole ----------------------------------------------------------------

test('9. Ctrl+A seleziona l\'elenco solo col fuoco sull\'elenco', opz, async () => {
  const s = await conMailEBozza();
  for (let id = 700; id < 705; id++) s.mails.push(B.mail(id, { smistamento: 'da_controllare' }));
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('#bozza-testo').blur();
  p.d.body.focus();
  p.tasto('a', { ctrlKey: true }, p.d.body);
  await B.dorme(40);
  assert.ok(p.q('.oggetto-grande'), 'fuori dall\'elenco Ctrl+A lascia stare la selezione');
  p.q('.elenco-mail').focus();
  p.tasto('a', { ctrlKey: true }, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('.selezione-multipla'));
  assert.equal(p.q('.selezione-multipla .grande').textContent, '7 mail selezionate');
  p.chiudi();
});

test('9. più di 5 mail: prima la conferma (Invio sceglie "Annulla"), e niente regole', opz, async () => {
  const s = B.server();
  for (let id = 700; id < 707; id++) s.mails.push(B.mail(id, { mittente: { nome: 'M' + id, indirizzo: 'm' + id + '@esempio.it' } }));
  const p = await B.pagina(s);
  p.q('.elenco-mail').focus();
  p.tasto('a', { ctrlKey: true }, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('.selezione-multipla'));
  p.tasto('2', {}, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('.velo'));
  p.d.activeElement.click(); // Invio sul pulsante col fuoco = "Annulla"
  await B.dorme(80);
  assert.equal(s.log.filter((x) => x.url.endsWith('/sposta')).length, 0);
  p.tasto('2', {}, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('.velo'));
  await p.clicTesto('Sposta 7 mail', p.q('.velo'));
  await p.aspetta(() => s.mails.every((m) => m.gruppo_id === 2), 3000);
  assert.ok(s.log.filter((x) => x.url.endsWith('/sposta')).every((x) => x.corpo.impara === false));
  assert.deepEqual(s.regole, []);
  p.chiudi();
});

// --- 10. Finestrelle e conferma in corso ---------------------------------------------------------

test('10. con una finestrella aperta Invio e i numeri non fanno niente', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('#bozza-testo').blur();
  p.tasto('?', {}, p.d.body);
  await p.aspetta(() => p.q('.velo'));
  s.log.length = 0;
  p.tasto('2', {}, p.d.body);
  p.tasto('Enter', {}, p.d.body);
  await B.dorme(80);
  assert.equal(s.log.filter((x) => x.metodo === 'POST').length, 0);
  p.chiudi();
});

test('10. mentre una risposta aspetta la conferma d\'invio i tasti di smistamento non fanno niente', opz, async () => {
  const s = await conMailEBozza();
  let rilascia;
  s.inviaLocale = () => new Promise((r) => { rilascia = () => r({ annullato: true }); });
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => rilascia);
  p.q('#bozza-testo').blur();
  s.log.length = 0;
  p.tasto('2', {}, p.d.body);
  p.tasto('Enter', {}, p.d.body);
  await B.dorme(80);
  assert.equal(s.log.filter((x) => x.metodo === 'POST').length, 0);
  rilascia();
  p.chiudi();
});

// --- 11. Tendina e date ----------------------------------------------------------------------------

test('11. "Sposta in…" sposta solo col pulsante, non al primo cambio della tendina', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s);
  await apriPrima(p);
  const tendina = p.q('.sposta-in select');
  tendina.value = '3';
  tendina.dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await B.dorme(60);
  assert.equal(s.log.filter((x) => x.url.endsWith('/sposta')).length, 0);
  await p.clicTesto('Sposta', p.q('.sposta-in'));
  await p.aspetta(() => s.log.some((x) => x.url.endsWith('/sposta')));
  assert.equal(s.log.find((x) => x.url.endsWith('/sposta')).corpo.gruppo_id, 3);
  p.chiudi();
});

test('11. la data di una scadenza si salva all\'uscita dal campo, non a ogni cifra', opz, async () => {
  const s = B.server();
  s.risposta = (metodo, url) => (url === '/server/api/scadenze?stato=aperta' ? [200, [{ id: 4, mail_id: null, descrizione: 'Offerta a Rossi', quando: '2026-10-16', ora: null, stato: 'aperta' }]] : (metodo === 'PATCH' ? [200, {}] : undefined));
  const p = await B.pagina(s);
  await p.clicTesto('Scadenze');
  await p.aspetta(() => p.q('input[aria-label="Nuova data"]'));
  const data = p.q('input[aria-label="Nuova data"]');
  for (const v of ['2026-10-01', '2026-10-17', '2026-10-18']) {
    data.value = v;
    data.dispatchEvent(new p.w.Event('input', { bubbles: true }));
    data.dispatchEvent(new p.w.Event('change', { bubbles: true }));
  }
  await B.dorme(60);
  assert.equal(s.log.filter((x) => x.metodo === 'PATCH').length, 0);
  data.dispatchEvent(new p.w.FocusEvent('blur'));
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH'));
  const patch = s.log.filter((x) => x.metodo === 'PATCH');
  assert.equal(patch.length, 1);
  assert.deepEqual(patch[0].corpo, { quando: '2026-10-18' });
  p.chiudi();
});

// --- 12. Esiti parziali --------------------------------------------------------------------------

test('12. azioni su più mail: "2 spostate, 2 no: motivo", un solo avviso', opz, async () => {
  const s = B.server();
  for (let id = 700; id < 704; id++) s.mails.push(B.mail(id));
  s.risposta = (metodo, url) => (/\/mail\/(700|702)\/sposta$/.test(url) ? [502, { errore: 'La casella Studio non risponde.', codice: 'casella_non_raggiungibile' }] : undefined);
  const p = await B.pagina(s);
  p.q('.elenco-mail').focus();
  p.tasto('a', { ctrlKey: true }, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('.selezione-multipla'));
  p.tasto('2', {}, p.q('.elenco-mail'));
  await p.aspetta(() => p.avvisi().length > 0, 3000);
  await B.dorme(60);
  assert.deepEqual(p.avvisi(), ['2 spostate in Fornitori, 2 no: La casella Studio non risponde.']);
  p.chiudi();
});

test('12. tasto D con errore del server: solo l\'errore, nessun messaggio di riuscita', opz, async () => {
  const s = await conMailEBozza();
  s.risposta = (metodo, url) => (url.endsWith('/rispondere') ? [502, { errore: 'La casella Studio non risponde.', codice: 'casella_non_raggiungibile' }] : undefined);
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('#bozza-testo').blur();
  p.tasto('d', {}, p.d.body);
  await p.aspetta(() => p.avvisi().length > 0);
  await B.dorme(40);
  assert.deepEqual(p.avvisi(), ['Non è riuscito: La casella Studio non risponde.']);
  p.chiudi();
});

// --- 13. Invio: testo vuoto, bozza fallita, esito incerto --------------------------------------------

test('13. testo vuoto: niente finestra di conferma', opz, async () => {
  const s = await conMailEBozza();
  let chiesta = false;
  s.inviaLocale = async () => { chiesta = true; return { annullato: true }; };
  const p = await B.pagina(s);
  await apriPrima(p);
  p.scrivi(p.q('#bozza-testo'), '   ');
  p.q('.azioni-bozza button.primario').click();
  await B.dorme(120);
  assert.equal(chiesta, false);
  assert.match(p.q('.sezione-bozza').textContent, /Il testo è vuoto/);
  p.chiudi();
});

test('13. bozza non riuscita: si può solo "Riprova a preparare" (rifai)', opz, async () => {
  const s = await conMailEBozza({}, { stato: 'errore', testo: '', errore: 'Lo scrittore non ha risposto.' });
  const p = await B.pagina(s);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.pulsante('Riprova a preparare'));
  assert.ok(!p.q('#bozza-testo'), 'niente modulo d\'invio per una bozza mai scritta');
  await p.clicTesto('Riprova a preparare');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/bozze/55/rifai'));
  assert.ok(s.log.some((x) => x.url === '/server/api/bozze/55/rifai'));
  p.chiudi();
});

test('13. invio incerto: il messaggio dice di guardare in Posta inviata', opz, async () => {
  const s = await conMailEBozza();
  s.inviaLocale = async () => ({ errore: 'Non è sicuro che la mail sia partita: guarda in Posta inviata prima di riprovare.', codice: 'incerto' });
  s.risposta = (metodo, url) => (url === '/locale/invia' ? [422, { errore: 'Non è sicuro che la mail sia partita: guarda in Posta inviata prima di riprovare.', codice: 'incerto' }] : undefined);
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => /Posta inviata/.test(p.q('.sezione-bozza').textContent));
  assert.match(p.q('.sezione-bozza').textContent, /guarda in Posta inviata/);
  assert.equal(p.q('#bozza-testo').readOnly, false);
  p.chiudi();
});

test('13. già partita (gia_inviata): lo dice e la bozza risulta inviata', opz, async () => {
  const s = await conMailEBozza();
  s.inviaLocale = async () => ({ inviata: true, gia_inviata: true, bozza: Object.assign(B.copia(s.bozze[55]), { stato: 'inviata' }) });
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => p.avvisi().length);
  assert.match(p.avvisi()[0], /Era già partita/);
  assert.match(p.q('.sezione-bozza').textContent, /inviata/);
  p.chiudi();
});

// --- 15. F5 -------------------------------------------------------------------------------------------

test('15. F5 tenuto premuto: un solo aggiornamento', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  p.tasto('F5', {}, p.d.body);
  for (let i = 0; i < 6; i++) p.tasto('F5', { repeat: true }, p.d.body);
  p.tasto('F5', {}, p.d.body);
  await B.dorme(120);
  assert.equal(s.log.filter((x) => x.url === '/locale/aggiorna-adesso').length, 1);
  p.chiudi();
});

// --- 18. Rifai -------------------------------------------------------------------------------------

test('18. "Rifai con istruzioni" prima salva le modifiche, poi chiede il rifacimento', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s, { tempi: { salva: 100000 } });
  await apriPrima(p);
  p.scrivi(p.q('#bozza-testo'), 'Buongiorno Mario, ho corretto io.');
  await p.clicTesto('Rifai con istruzioni');
  p.q('#bozza-istruzioni').value = 'più breve';
  await p.clicTesto('Prepara con queste istruzioni');
  await p.aspetta(() => s.log.some((x) => x.url.endsWith('/rifai')));
  const i = s.log.findIndex((x) => x.metodo === 'PUT');
  const j = s.log.findIndex((x) => x.url.endsWith('/rifai'));
  assert.ok(i >= 0 && i < j, 'prima il salvataggio, poi il rifacimento');
  assert.deepEqual(s.log[j].corpo, { istruzioni: 'più breve' });
  await p.aspetta(() => /ho corretto io/.test((p.q('#bozza-testo') || {}).value || ''));
  assert.match(p.q('#bozza-testo').value, /ho corretto io/, 'il server parte dalla bozza attuale');
  p.chiudi();
});

test('18. bozza ritoccata a mano: rifarla da zero solo dopo un sì esplicito', opz, async () => {
  const s = await conMailEBozza({}, { stato: 'errore', testo: '', modificata: true });
  const p = await B.pagina(s);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.pulsante('Riprova a preparare'));
  await p.clicTesto('Riprova a preparare');
  await B.dorme(60);
  assert.ok(!s.log.some((x) => x.url.endsWith('/rifai')), 'senza conferma non si rifà da zero');
  assert.ok(p.pulsante('Sì, rifalla da zero'));
  await p.clicTesto('Sì, rifalla da zero');
  await p.aspetta(() => s.log.some((x) => x.url.endsWith('/rifai')));
  assert.deepEqual(s.log.find((x) => x.url.endsWith('/rifai')).corpo, { istruzioni: null, da_zero: true });
  p.chiudi();
});

test('15.5 anche un sollecito si rifà con istruzioni (POST /api/bozze/{id}/rifai)', opz, async () => {
  const s = solleciti();
  const p = await B.pagina(s);
  const spazi = await apriDueSolleciti(p);
  await p.clicTesto('Rifai con istruzioni', spazi[1]);
  p.q('textarea.istruzioni', spazi[1]).value = 'più gentile';
  await p.clicTesto('Prepara con queste istruzioni', spazi[1]);
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/bozze/102/rifai'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/bozze/102/rifai').corpo, { istruzioni: 'più gentile' });
  p.chiudi();
});

// --- 19. Testi e contatori ---------------------------------------------------------------------------

test('19. un 422 con "campo" mostra la frase del server ed evidenzia il campo', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.q('#imp-gr'));
  p.q('#imp-gr').value = '99';
  await p.clicTesto('Salva', p.q('#imp-sol'));
  await p.aspetta(() => /da 1 a 60/.test(p.q('#imp-sol').textContent));
  assert.match(p.q('#imp-sol').textContent, /I giorni per ricordare le mail senza risposta vanno da 1 a 60/);
  assert.ok(p.q('#imp-gr').classList.contains('campo-errato'));
  p.chiudi();
});

test('19. i contatori dei filtri vengono da /api/conteggi (niente elenchi da 99 mail)', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(1, { urgenza: 'alta', letta: false }), B.mail(2, { smistamento: 'da_controllare' }), B.mail(3, { da_rispondere: true, risposta_data: true })];
  const p = await B.pagina(s);
  const voce = (t) => p.tutti('.voce').find((v) => v.querySelector('.etichetta').textContent === t);
  assert.equal(voce('Da rispondere').querySelector('.contatore').textContent, '2', 'la mail già risposta non conta');
  assert.equal(voce('Urgenti').querySelector('.contatore').textContent, '1');
  assert.equal(voce('Da controllare').querySelector('.contatore').textContent, '1');
  assert.ok(!s.log.some((x) => /limite=99/.test(x.url)));
  p.chiudi();
});

// --- Contratto v1.1 ------------------------------------------------------------------------------

test('15.1 con 16 gruppi non se ne aggiungono altri', opz, async () => {
  const s = B.server();
  for (let i = 7; i <= 16; i++) s.gruppi.push({ id: 10 + i, nome: 'Gruppo ' + i, ordine: i });
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.q('#nuovo-gruppo'));
  assert.equal(p.q('#nuovo-gruppo').disabled, true);
  assert.match(p.q('#imp-gruppi').textContent, /al massimo 16 gruppi/);
  p.chiudi();
});

test('15.2 spegnendo la modalità prova si chiede per le mail già smistate', opz, async () => {
  const s = B.server();
  s.arretrato = 37;
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.q('#imp-prova'));
  p.q('#imp-prova').checked = false;
  await p.clicTesto('Salva', p.q('#imp-sol'));
  await p.aspetta(() => p.q('.velo'));
  assert.match(p.q('.velo').textContent, /Ci sono 37 mail già smistate rimaste in Posta in arrivo/);
  await p.clicTesto('Sì, spostale', p.q('.velo'));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/arretrato/applica'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/arretrato/applica').corpo, { conferma: true });
  p.chiudi();
});

test('15.3 casella in errore: "Ricollega" (Gmail con password nuova, Outlook col codice)', opz, async () => {
  const s = B.server();
  s.caselle = [
    { id: 1, tipo: 'gmail', indirizzo: 'manuel.esempio@gmail.com', nome: 'Personale', stato: 'errore', errore: 'Google ha rifiutato la password per le app.', ultimo_controllo: null, attiva_dal: null },
    { id: 2, tipo: 'outlook', indirizzo: 'studio@esempio.it', nome: 'Studio', stato: 'errore', errore: 'Microsoft ha chiesto di accedere di nuovo.', ultimo_controllo: null, attiva_dal: null },
  ];
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.tutti('#imp-caselle li').length === 2);
  const [gmail, outlook] = p.tutti('#imp-caselle li.scheda-riga');
  await p.clicTesto('Ricollega', gmail);
  p.q('input[type="password"]', gmail).value = 'abcd efgh ijkl mnop';
  await p.clicTesto('Ricollega', p.q('.modulo-casella', gmail));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/caselle/1/ricollega'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/caselle/1/ricollega').corpo, { password_app: 'abcd efgh ijkl mnop' });
  await p.aspetta(() => p.tutti('#imp-caselle li.scheda-riga').length === 2 && p.pulsante('Ricollega', p.tutti('#imp-caselle li.scheda-riga')[1]));
  await p.clicTesto('Ricollega', p.tutti('#imp-caselle li.scheda-riga')[1]);
  await p.aspetta(() => p.q('.codice-grande'));
  assert.equal(p.q('.codice-grande').textContent, 'ABCD-EFGH');
  assert.ok(outlook);
  p.chiudi();
});

test('15.8 PEC: si vedono entrambi i mittenti', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(815, { oggetto: 'Richiesta integrazione', mittente: { nome: 'Comune di Bassano', indirizzo: 'urbanistica@comune.esempio.it' }, pec: { mittente_busta: 'posta-certificata@pec.esempio.it', gestore: 'pec.esempio.it' } })];
  const p = await B.pagina(s);
  assert.match(p.righe()[0].textContent, /PEC/);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('.intestazioni'));
  const testa = p.q('.intestazioni').textContent;
  assert.match(testa, /Comune di Bassano <urbanistica@comune\.esempio\.it>/);
  assert.match(testa, /Busta PEC.*posta-certificata@pec\.esempio\.it/);
  p.chiudi();
});

test('15.13 su http: riga "collegamento di casa non protetto" e niente password Gmail', opz, async () => {
  const s = B.server();
  s.stato.server = 'http://192.168.1.51:8200';
  s.stato.senza_protezione = true;
  const p = await B.pagina(s);
  assert.match(p.q('.barra').textContent, /Collegamento di casa non protetto/);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.pulsante('Aggiungi Gmail'));
  await p.clicTesto('Aggiungi Gmail');
  assert.ok(!p.q('#gm-password'), 'nessun campo password su http');
  assert.match(p.q('.zona-aggiungi').textContent, /https di Tailscale/);
  p.chiudi();
});

test('15.13 risposta già data: niente segno "Bozza pronta"', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(812, { risposta_data: true })];
  s.bozze[55] = B.bozza(55, { mail_id: 812, modificata: true });
  const p = await B.pagina(s);
  assert.doesNotMatch(p.righe()[0].textContent, /Bozza pronta/);
  assert.match(p.righe()[0].textContent, /Risposta data/);
  p.chiudi();
});

test('15.14 un gruppo nuovo chiede che mail ci vanno; la descrizione si vede', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.q('#nuovo-gruppo-descrizione'));
  assert.match(p.q('#imp-gruppi').textContent, /Ci vanno: un cliente chiede o conferma un lavoro/);
  assert.match(p.q('#imp-gruppi').textContent, /più è chiara, meglio Sol smista/);
  p.q('#nuovo-gruppo').value = 'Condomini';
  p.q('#nuovo-gruppo-descrizione').value = 'richieste degli amministratori di condominio';
  await p.clicTesto('Aggiungi gruppo');
  await p.aspetta(() => s.log.some((x) => x.metodo === 'POST' && x.url === '/server/api/gruppi'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'POST' && x.url === '/server/api/gruppi').corpo, { nome: 'Condomini', descrizione: 'richieste degli amministratori di condominio' });
  p.chiudi();
});

test('16. "Notifiche recenti": ogni notifica apre la sua mail', opz, async () => {
  const s = await conMailEBozza();
  s.notifiche = [
    { quando: '2026-10-10T09:00:00+02:00', titolo: 'Urgente da Laura Bianchi', testo: 'Altra mail', destinazione: 'mail/811' },
    { quando: '2026-10-10T08:58:00+02:00', titolo: 'Urgente da Mario Rossi', testo: 'Offerta', destinazione: 'mail/812' },
  ];
  const p = await B.pagina(s);
  await p.clicTesto('Notifiche recenti');
  await p.aspetta(() => p.tutti('.scheda-riga').length === 2);
  await p.clicTesto('Apri', p.tutti('.scheda-riga')[1]);
  await p.aspetta(() => p.q('.oggetto-grande'));
  assert.equal(p.q('.oggetto-grande').textContent, 'Offerta capannone Bassano');
  p.chiudi();
});

test('16. aperta da una notifica (#mail/812): si apre quella mail e l\'elenco c\'è', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s, { frammento: 'mail/812' });
  await p.aspetta(() => p.q('.oggetto-grande') && p.righe().length === 2);
  assert.equal(p.q('.oggetto-grande').textContent, 'Offerta capannone Bassano');
  assert.equal(p.righe().length, 2);
  p.w.solApri('mail/811');
  await p.aspetta(() => p.q('.oggetto-grande').textContent === 'Altra mail');
  assert.equal(p.q('.oggetto-grande').textContent, 'Altra mail');
  p.chiudi();
});

// --- §16 Possibile truffa -------------------------------------------------------------------------

const AVVISO_TRUFFA = 'Attenzione: questa mail potrebbe essere una truffa. Prima di pagare, cambiare coordinate bancarie o mandare documenti, telefona al mittente a un numero che conosci già, non a quello scritto nella mail.';

function conTruffa(extra) {
  const s = B.server();
  s.mails = [
    B.mail(820, Object.assign({ oggetto: 'Nuovo IBAN per i pagamenti', mittente: { nome: 'Ferramenta Neri', indirizzo: 'amministrazione@neri-pagamenti.esempio.it' }, sospetta: true, smistamento: 'da_controllare', urgenza: 'alta', letta: false }, extra || {})),
    B.mail(819, { oggetto: 'Sopralluogo', letta: true }),
  ];
  return s;
}

test('16.3 etichetta rossa "Possibile truffa" prima di tutte le altre', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  const riga = p.riga(820);
  const segni = p.tutti('.segno', riga);
  assert.equal(segni[0].textContent, 'Possibile truffa');
  assert.ok(segni[0].classList.contains('truffa'));
  assert.ok(segni.length > 1, 'le altre etichette vengono dopo');
  assert.ok(!/Possibile truffa/.test(p.riga(819).textContent));
  p.chiudi();
});

test('16.3 mail aperta: avviso in cima col testo del contratto e "Non è una truffa"', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  p.clic(p.riga(820));
  await p.aspetta(() => p.q('.riquadro.truffa'));
  const testata = p.q('.mail-aperta');
  assert.equal(testata.firstElementChild, p.q('.riquadro.truffa'), 'in cima, prima dell\'oggetto');
  assert.equal(p.q('.testo-truffa').textContent, AVVISO_TRUFFA);
  await p.clicTesto('Non è una truffa');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/820/sospetta'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/mail/820/sospetta').corpo, { sospetta: false });
  await p.aspetta(() => !p.q('.riquadro.truffa'));
  assert.ok(!/Possibile truffa/.test(p.riga(820).textContent), 'l\'etichetta sparisce');
  p.chiudi();
});

test('16.3 sulle mail non segnalate: "Segnala come possibile truffa", meno in vista', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  p.clic(p.riga(819));
  await p.aspetta(() => p.pulsante('Segnala come possibile truffa'));
  const pulsante = p.pulsante('Segnala come possibile truffa');
  assert.ok(pulsante.classList.contains('discreto'));
  assert.ok(!pulsante.classList.contains('primario'));
  await p.clicTesto('Segnala come possibile truffa');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/819/sospetta'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/mail/819/sospetta').corpo, { sospetta: true });
  await p.aspetta(() => p.q('.riquadro.truffa'));
  assert.ok(p.q('.riquadro.truffa'));
  p.chiudi();
});

test('16.3 filtro "Possibili truffe" col numero, solo se ce ne sono', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  const voce = () => p.tutti('.voce').find((v) => v.querySelector('.etichetta').textContent === 'Possibili truffe');
  assert.ok(voce(), 'c\'è con una mail sospetta');
  assert.equal(voce().querySelector('.contatore').textContent, '1');
  await p.clicTesto('Possibili truffe');
  await p.aspetta(() => s.log.some((x) => /\/server\/api\/mail\?sospetta=true/.test(x.url)));
  assert.ok(s.log.some((x) => /\/server\/api\/mail\?sospetta=true/.test(x.url)));
  assert.equal(p.righe().length, 1);
  p.chiudi();

  const senza = B.server();
  senza.mails = [B.mail(819)];
  const p2 = await B.pagina(senza);
  assert.ok(!p2.tutti('.voce').some((v) => v.querySelector('.etichetta').textContent === 'Possibili truffe'), 'senza sospette il filtro non c\'è');
  p2.chiudi();
});

test('16.3 Invio ("va bene così") su una mail sospetta accetta il gruppo ma NON toglie il sospetto', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  p.clic(p.riga(820));
  await p.aspetta(() => p.q('.riquadro.truffa'));
  assert.match(p.q('.riquadro.controllare').textContent, /resta segnalata/);
  p.q('.elenco-mail').focus();
  p.tasto('Enter', {}, p.q('.elenco-mail'));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/820/conferma'));
  await B.dorme(120);
  assert.ok(!s.log.some((x) => x.url.endsWith('/sospetta')), 'nessuna richiesta che tolga il sospetto');
  const mail = s.mails.find((m) => m.id === 820);
  assert.equal(mail.smistamento, 'smistata', 'il gruppo è accettato');
  assert.equal(mail.sospetta, true, 'la mail resta segnalata');
  assert.match(p.riga(820).textContent, /Possibile truffa/, 'e l\'etichetta resta');
  p.chiudi();
});

test('16.3 rispondendo a una mail sospetta il riquadro della bozza avvisa in rosso', opz, async () => {
  const s = conTruffa();
  s.bozze[70] = B.bozza(70, { mail_id: 820, oggetto: 'Re: Nuovo IBAN per i pagamenti' });
  const p = await B.pagina(s);
  p.clic(p.riga(820));
  await p.aspetta(() => p.q('#bozza-testo'));
  const avviso = p.q('.sezione-bozza .in-linea.truffa');
  assert.ok(avviso);
  assert.match(avviso.textContent, /Stai rispondendo a una mail segnalata come possibile truffa/);
  p.chiudi();
});

test('16.2 soglia della possibile truffa nelle impostazioni (tra 20% e 90%)', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  await p.clicTesto('Impostazioni');
  await p.aspetta(() => p.q('#imp-truffa'));
  const soglia = p.q('#imp-truffa');
  assert.equal(soglia.min, '20');
  assert.equal(soglia.max, '90');
  assert.equal(soglia.value, '50');
  assert.match(p.q('#imp-sol').textContent, /Meglio un'etichetta in più che una truffa non vista/);
  soglia.value = '30';
  soglia.dispatchEvent(new p.w.Event('input', { bubbles: true }));
  await p.clicTesto('Salva', p.q('#imp-sol'));
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH' && x.url === '/server/api/impostazioni'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH').corpo, { soglia_truffa: 0.3 });
  p.chiudi();
});
