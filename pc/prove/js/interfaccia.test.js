// I test dell'interfaccia vera (pc/ui, Sol 2.0) in jsdom, con un server finto. Riproducono i difetti trovati
// dalla revisione (numeri 1-19, ognuno falliva con la pagina di allora), le novità del contratto v1.1-v1.4 e
// il nuovo aspetto (docs/aspetto.md): Oggi, Posta a sezioni e raccolte, risposta con le pillole di tono,
// Smista coi tasti, tutorial. Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
// Una prova che fallisce non lascia la pagina aperta (i suoi timer terrebbero vivo il processo).
test.afterEach(() => B.chiudiTutte());

async function conMailEBozza(extraMail, extraBozza) {
  const s = B.server();
  s.mails = [B.mail(812, Object.assign({ letta: true, oggetto: 'Offerta capannone Bassano' }, extraMail || {})), B.mail(811, { letta: true, oggetto: 'Altra mail', mittente: { nome: 'Luca Bianchi', indirizzo: 'bianchi@esempio.it' } })];
  s.bozze[55] = B.bozza(55, Object.assign({ mail_id: 812, oggetto: 'Re: Offerta capannone Bassano', versione: 3 }, extraBozza || {}));
  return s;
}

/** Va alla Posta (dalla colonna di sinistra); `segmento` = 'tutte' per l'elenco per data. */
async function posta(p, segmento) {
  p.q('.voce-rail[data-vista="posta"]').click();
  await p.aspetta(() => p.q('.elenco-mail') && !p.q('.elenco-mail .vuoto'), 2000);
  if (segmento === 'tutte') {
    p.q('.segmenti [data-segmento="tutte"]').click();
    await p.aspetta(() => p.q('.elenco-mail ul.gruppo-righe') && p.q('.segmenti [data-segmento="tutte"]').getAttribute('aria-selected') === 'true');
    await B.dorme(30);
  }
}

async function apriPrima(p) {
  await posta(p);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('#bozza-testo'));
}

/** A, Cc e Oggetto della risposta si aprono dal destinatario ("a rossi@esempio.it"). */
async function apriCampi(p) {
  p.q('.risposta-sol .destinatario').click();
  await p.aspetta(() => p.q('#bozza-cc'));
}

/** Apre un'impostazione (una riga dell'elenco) e aspetta il suo foglio. */
async function impostazione(p, chiave, pronto) {
  p.q('.voce-tu').click();
  await p.aspetta(() => p.q('.riga-imp[data-imp="' + chiave + '"]'));
  p.q('.riga-imp[data-imp="' + chiave + '"]').click();
  await p.aspetta(() => p.q('.foglio') && (!pronto || pronto()));
  return p.q('.foglio');
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
  p.q('.voce-rail[data-vista="aspetti"]').click();
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
  s.inviaLocale = async (corpo) => {
    durante = { corpo, put: s.log.filter((x) => x.metodo === 'PUT').length };
    await B.dorme(250);
    return { annullato: true };
  };
  const p = await B.pagina(s);
  await apriPrima(p);
  await apriCampi(p);
  p.scrivi(p.q('#bozza-cc'), 'bianchi@esempio.it');
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => durante);
  const cc = p.q('#bozza-cc');
  assert.equal(cc.readOnly, true, 'Cc in sola lettura durante la conferma');
  assert.equal(p.q('#bozza-testo').readOnly, true);
  assert.ok(p.tutti('.pillola-tono').every((b) => b.disabled), 'anche le pillole di tono sono ferme');
  assert.match(p.q('.barra-risposta').textContent, /In attesa della conferma/);
  p.scrivi(cc, 'bianchi@esempio.it, verdi@esempio.it');
  await B.dorme(320);
  assert.equal(s.log.filter((x) => x.metodo === 'PUT').length, durante.put, 'nessun PUT mentre la finestra è aperta');
  assert.deepEqual(s.bozze[55].cc, ['bianchi@esempio.it']);
  assert.equal(durante.corpo.versione, s.bozze[55].versione, 'la pagina dice quale versione ha mostrato');
  assert.equal(p.q('#bozza-cc').readOnly, false, 'dopo la conferma si sblocca');
  p.chiudi();
});

test('2. se la bozza è cambiata (409) la pagina mostra la versione nuova da ricontrollare', opz, async () => {
  const s = await conMailEBozza();
  s.inviaLocale = async () => ({ errore: 'La risposta è cambiata dopo che l\'hai controllata: ricontrollala prima di inviarla.', codice: 'conflitto', bozza: Object.assign(B.copia(s.bozze[55]), { testo: 'Testo cambiato dal telefono.', versione: 9 }) });
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
  await posta(p);
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
  await posta(p);
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
  await apriCampi(p);
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
  const s = await conMailEBozza({}, { modificata: true, testo: 'Testo corretto a mano da Paolo.' });
  s.ritardo = (metodo, url) => (metodo === 'GET' && url === '/server/api/bozze/55' ? 300 : 2);
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('.sezione-bozza'));
  p.tasto('r', {}, p.d.body);
  await B.dorme(450);
  assert.equal(s.log.filter((x) => x.url.endsWith('/bozza') || x.url.endsWith('/rifai')).length, 0);
  assert.equal(s.bozze[55].stato, 'pronta');
  assert.equal(p.q('#bozza-testo').value, 'Testo corretto a mano da Paolo.');
  p.chiudi();
});

// --- 7. Computer non più riconosciuto -------------------------------------------------------------

test('7. /api/salute con autenticato false: striscia fissa "collega di nuovo"', opz, async () => {
  const s = B.server();
  s.salute = { ok: true, versione: '1.0.0', autenticato: false };
  const p = await B.pagina(s);
  assert.match(p.q('.stato-alto').textContent, /non riconosce più questo computer/);
  assert.ok(p.pulsante('Collega di nuovo'));
  p.chiudi();
});

test('7. un 401 da una richiesta qualsiasi mostra subito la striscia', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(812)];
  s.risposta = (metodo, url) => (url.startsWith('/server/api/mail/812') ? [401, { errore: 'Questo dispositivo non è abbinato o il suo accesso è stato revocato.', codice: 'non_autorizzato' }] : undefined);
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.righe()[0]);
  await p.aspetta(() => /non riconosce più/.test(p.q('.stato-alto').textContent));
  assert.match(p.q('.stato-alto').textContent, /non riconosce più questo computer/);
  p.chiudi();
});

test('7. "Scollega" su (questo computer) pulisce anche i dati qui (/locale/scollega)', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  const foglio = await impostazione(p, 'dispositivi', () => /questo computer/.test(p.q('.foglio').textContent));
  const riga = p.tutti('li', foglio).find((li) => /questo computer/.test(li.textContent));
  await p.clicTesto('Scollega', riga);
  await p.clicTesto('Sì, scollega', riga);
  await p.aspetta(() => s.log.some((x) => x.url === '/locale/scollega'));
  assert.ok(s.log.some((x) => x.url === '/locale/scollega'));
  assert.ok(!s.log.some((x) => x.metodo === 'DELETE' && x.url === '/server/api/dispositivi/7'));
  await p.aspetta(() => p.q('.abbinamento'));
  assert.ok(p.q('.abbinamento'), 'torna la pagina per collegarlo');
  p.chiudi();
});

// --- 8. La ricarica del minuto ---------------------------------------------------------------------

test('8. la ricarica del minuto non toglie le mail caricate né la selezione', opz, async () => {
  const s = B.server();
  for (let id = 1000; id > 940; id--) s.mails.push(B.mail(id, { letta: true, mittente: { nome: 'M' + id, indirizzo: 'm' + id + '@esempio.it' } }));
  const p = await B.pagina(s);
  await posta(p, 'tutte');
  await p.clicTesto('Mostra le mail più vecchie');
  await p.aspetta(() => p.righe().length === 60);
  for (const i of [52, 54, 56]) { p.clic(p.righe()[i], { ctrlKey: true }); await B.dorme(30); }
  assert.equal(p.q('.info-selezione').textContent, '3 mail selezionate');
  s.mails.push(B.mail(1001, { letta: false, oggetto: 'Mail nuova' }));
  await p.intervalli[0]();
  await B.dorme(60);
  assert.equal(p.righe().length, 61, 'la nuova entra in cima, le vecchie restano');
  assert.equal(p.righe()[0].dataset.id, '1001');
  assert.equal(p.q('.info-selezione').textContent, '3 mail selezionate');
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
  await posta(p);
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

// --- 11. "Sposta in" e date ----------------------------------------------------------------------

test('11. "Sposta in" (la pillola del gruppo) sposta solo scegliendo un gruppo: aprirlo e scorrerlo non sposta', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s);
  await apriPrima(p);
  const chip = p.q('.testa-mail .chip-gruppo');
  assert.match(chip.textContent, /Clienti/);
  assert.ok(chip.classList.contains('col-blu'), 'il gruppo col suo colore');
  chip.click();
  await p.aspetta(() => !p.q('.sposta-in-elenco').hidden);
  const voci = p.tutti('.sposta-in-elenco .voce-gruppo');
  assert.deepEqual(voci.map((v) => v.dataset.gruppo), ['1', '2', '3', '4', '5', '6']);
  assert.match(voci[0].textContent, /Clienti \(adesso è qui\)/);
  assert.match(voci[0].textContent, /un cliente chiede o conferma un lavoro/, 'con la descrizione corta');
  assert.ok(voci[2].classList.contains('col-ambra'), 'ogni gruppo col suo colore');
  assert.equal(p.d.activeElement, voci[0]);
  p.tasto('ArrowDown', {}, p.d.activeElement);
  p.tasto('ArrowDown', {}, p.d.activeElement);
  await B.dorme(60);
  assert.equal(p.d.activeElement, voci[2], 'le frecce scorrono i gruppi');
  assert.equal(p.q('.mail.attiva').dataset.id, '812', 'e non le mail dell\'elenco');
  p.tasto('Escape', {}, p.d.activeElement);
  await B.dorme(40);
  assert.ok(p.q('.sposta-in-elenco').hidden, 'Esc chiude');
  assert.equal(s.log.filter((x) => x.url.endsWith('/sposta')).length, 0, 'aprire e scorrere non sposta niente');
  p.q('.testa-mail .chip-gruppo').click();
  await p.aspetta(() => !p.q('.sposta-in-elenco').hidden);
  p.tutti('.sposta-in-elenco .voce-gruppo')[1].click();
  await p.aspetta(() => s.log.some((x) => x.url.endsWith('/sposta')));
  assert.equal(s.log.find((x) => x.url.endsWith('/sposta')).corpo.gruppo_id, 2);
  p.chiudi();
});

test('11. la data di una scadenza si salva all\'uscita dal campo, non a ogni cifra', opz, async () => {
  const s = B.server();
  s.scadenze = [{ id: 4, mail_id: null, descrizione: 'Offerta a Rossi', quando: '2026-10-16', ora: null, stato: 'aperta' }];
  const p = await B.pagina(s);
  p.q('.voce-rail[data-vista="scadenze"]').click();
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
  await posta(p);
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
  await posta(p);
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
  s.risposta = (metodo, url) => (url === '/locale/invia' ? [422, { errore: 'Non è sicuro che la mail sia partita: guarda in Posta inviata prima di riprovare.', codice: 'incerto' }] : undefined);
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => /Posta inviata/.test(p.q('.sezione-bozza').textContent));
  assert.match(p.q('.sezione-bozza').textContent, /guarda in Posta inviata/);
  assert.equal(p.q('#bozza-testo').readOnly, false);
  p.chiudi();
});

test('13. già partita (gia_inviata): lo dice e la risposta risulta inviata, con la spunta', opz, async () => {
  const s = await conMailEBozza();
  s.inviaLocale = async () => ({ inviata: true, gia_inviata: true, bozza: Object.assign(B.copia(s.bozze[55]), { stato: 'inviata' }) });
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('.azioni-bozza button.primario').click();
  await p.aspetta(() => p.avvisi().length);
  assert.match(p.avvisi()[0], /Era già partita/);
  assert.match(p.q('.sezione-bozza').textContent, /Risposta inviata/);
  assert.ok(p.q('.risposta-inviata .cerchio-spunta'));
  assert.equal(p.q('.barra-risposta').children.length, 0, 'partita la risposta, la barra se ne va');
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

// --- 18. Rifare la risposta: pillole di tono e "Altro…" -------------------------------------------

test('18. "Altro…" prima salva le modifiche, poi chiede di rifarla con le parole scritte', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s, { tempi: { salva: 100000 } });
  await apriPrima(p);
  p.scrivi(p.q('#bozza-testo'), 'Buongiorno Mario, ho corretto io.');
  p.q('.pillola-tono[data-tono="altro"]').click();
  await p.aspetta(() => p.q('#bozza-istruzioni'));
  p.q('#bozza-istruzioni').value = 'più breve';
  await p.clicTesto('Rifalla');
  await p.aspetta(() => s.log.some((x) => x.url.endsWith('/rifai')));
  const i = s.log.findIndex((x) => x.metodo === 'PUT');
  const j = s.log.findIndex((x) => x.url.endsWith('/rifai'));
  assert.ok(i >= 0 && i < j, 'prima il salvataggio, poi il rifacimento');
  assert.deepEqual(s.log[j].corpo, { istruzioni: 'più breve' });
  await p.aspetta(() => /ho corretto io/.test((p.q('#bozza-testo') || {}).value || '') && !p.q('.in-riscrittura'));
  assert.match(p.q('#bozza-testo').value, /ho corretto io/, 'il server parte dalla bozza attuale');
  p.chiudi();
});

test('18. pillole di tono: "Più breve" chiede di rifarla, il testo resta visibile sbiadito, poi la pillola resta premuta', opz, async () => {
  const s = await conMailEBozza();
  s.prontaDopoMs = 250;
  const p = await B.pagina(s);
  await apriPrima(p);
  assert.deepEqual(p.tutti('.pillola-tono').map((b) => b.textContent), ['Più breve', 'Più formale', 'Chiedi più tempo', 'Altro…']);
  const prima = p.q('#bozza-testo').value;
  p.q('.pillola-tono[data-tono="breve"]').click();
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/bozze/55/rifai'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/bozze/55/rifai').corpo, { istruzioni: B.L.TONI[0].istruzioni });
  await p.aspetta(() => p.q('.risposta-sol.in-riscrittura'));
  assert.equal(p.q('#bozza-testo').value, prima, 'mentre Sol riscrive, il testo di prima resta lì');
  assert.equal(p.q('#bozza-testo').readOnly, true);
  assert.match(p.q('.barra-risposta').textContent, /Sol riscrive la risposta/);
  assert.ok(p.q('.barra-risposta .indicatore'));
  await p.aspetta(() => !p.q('.in-riscrittura') && p.q('#bozza-testo'), 3000);
  assert.match(p.q('#bozza-testo').value, /Rifatta: Rendi la risposta più breve/);
  assert.equal(p.q('.pillola-tono[data-tono="breve"]').getAttribute('aria-pressed'), 'true', 'la pillola scelta resta premuta');
  p.scrivi(p.q('#bozza-testo'), p.q('#bozza-testo').value + ' Grazie.');
  assert.equal(p.q('.pillola-tono[data-tono="breve"]').getAttribute('aria-pressed'), 'false', 'cambiando il testo a mano non è più "più breve"');
  p.chiudi();
});

test('18. bozza ritoccata a mano: rifarla da zero solo dopo un sì esplicito', opz, async () => {
  const s = await conMailEBozza({}, { stato: 'errore', testo: '', modificata: true });
  const p = await B.pagina(s);
  await posta(p);
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
  const foglio = await impostazione(p, 'promemoria', () => p.q('#imp-gr'));
  p.q('#imp-gr').value = '99';
  await p.clicTesto('Salva', foglio);
  await p.aspetta(() => /da 1 a 60/.test(foglio.textContent));
  assert.match(foglio.textContent, /I giorni per ricordare le mail senza risposta vanno da 1 a 60/);
  assert.ok(p.q('#imp-gr').classList.contains('campo-errato'));
  p.chiudi();
});

test('19. i numeri della Posta vengono da /api/conteggi (niente elenchi da 99 mail)', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(1, { urgenza: 'alta', letta: false }), B.mail(2, { smistamento: 'da_controllare' }), B.mail(3, { da_rispondere: true, risposta_data: true }), B.mail(4, { gruppo_id: 5 })];
  const p = await B.pagina(s);
  assert.equal(p.q('.voce-rail[data-vista="posta"] .pallino-numero').textContent, '3', 'da leggere nei gruppi importanti (la newsletter no)');
  await posta(p);
  assert.equal(p.q('.sezione-posta[data-gruppo="1"] .conto').textContent, '3', 'da leggere nel gruppo (conteggi.gruppi)');
  assert.ok(!s.log.some((x) => /limite=99/.test(x.url)));
  p.chiudi();
});

// --- Contratto v1.1 ------------------------------------------------------------------------------

test('15.1 con 16 gruppi non se ne aggiungono altri', opz, async () => {
  const s = B.server();
  for (let i = 7; i <= 16; i++) s.gruppi.push({ id: 10 + i, nome: 'Gruppo ' + i, ordine: i });
  const p = await B.pagina(s);
  const foglio = await impostazione(p, 'gruppi', () => p.q('#nuovo-gruppo'));
  assert.equal(p.q('#nuovo-gruppo').disabled, true);
  assert.match(foglio.textContent, /al massimo 16 gruppi/);
  p.chiudi();
});

test('15.2 spegnendo la modalità prova si chiede per le mail già smistate', opz, async () => {
  const s = B.server();
  s.arretrato = 37;
  const p = await B.pagina(s);
  await impostazione(p, 'prova', () => p.q('#imp-prova'));
  const prova = p.q('#imp-prova');
  prova.checked = false;
  prova.dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await p.aspetta(() => p.q('.velo'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH').corpo, { modalita_prova: false });
  assert.match(p.q('.velo').textContent, /Ci sono 37 mail già smistate rimaste in Posta in arrivo/);
  await p.clicTesto('Sì, spostale', p.q('.velo'));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/arretrato/applica'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/arretrato/applica').corpo, { conferma: true });
  p.chiudi();
});

test('15.3 casella in errore: "Ricollega" (Gmail con password nuova, Outlook col codice)', opz, async () => {
  const s = B.server();
  s.caselle = [
    { id: 1, tipo: 'gmail', indirizzo: 'personale.esempio@gmail.com', nome: 'Personale', stato: 'errore', errore: 'Google ha rifiutato la password per le app.', ultimo_controllo: null, attiva_dal: null },
    { id: 2, tipo: 'outlook', indirizzo: 'studio@esempio.it', nome: 'Studio', stato: 'errore', errore: 'Microsoft ha chiesto di accedere di nuovo.', ultimo_controllo: null, attiva_dal: null },
  ];
  const p = await B.pagina(s);
  const gmail = await impostazione(p, 'casella-1', () => p.pulsante('Ricollega', p.q('.foglio')));
  assert.match(gmail.textContent, /Google ha rifiutato la password per le app/);
  await p.clicTesto('Ricollega', gmail);
  p.q('input[type="password"]', gmail).value = 'abcd efgh ijkl mnop';
  await p.clicTesto('Ricollega', p.q('.modulo-casella', gmail));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/caselle/1/ricollega'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/caselle/1/ricollega').corpo, { password_app: 'abcd efgh ijkl mnop' });
  await p.aspetta(() => !p.q('.foglio'));
  p.q('.riga-imp[data-imp="casella-2"]').click();
  await p.aspetta(() => p.q('.foglio') && p.pulsante('Ricollega', p.q('.foglio')));
  await p.clicTesto('Ricollega', p.q('.foglio'));
  await p.aspetta(() => p.q('.codice-grande'));
  assert.equal(p.q('.codice-grande').textContent, 'ABCD-EFGH');
  p.chiudi();
});

test('15.8 PEC: si vedono entrambi i mittenti', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(815, { oggetto: 'Richiesta integrazione', gruppo_id: 4, mittente: { nome: 'Comune di Bassano', indirizzo: 'urbanistica@comune.esempio.it' }, pec: { mittente_busta: 'posta-certificata@pec.esempio.it', gestore: 'pec.esempio.it' } })];
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.righe()[0]);
  await p.aspetta(() => p.q('.dettagli-pec'));
  const chi = p.q('.testa-mail .chi').textContent;
  assert.match(chi, /Comune di Bassano/, 'chi ha scritto, nell\'intestazione');
  assert.match(chi, /urbanistica@comune\.esempio\.it/);
  assert.match(p.q('.dettagli-pec').textContent, /posta-certificata@pec\.esempio\.it/, 'e la casella certificata che l\'ha spedita');
  p.chiudi();
});

test('15.13 su http: "collegamento di casa non protetto" e niente password Gmail', opz, async () => {
  const s = B.server();
  s.stato.server = 'http://192.168.1.51:8200';
  s.stato.senza_protezione = true;
  const p = await B.pagina(s);
  assert.match(p.q('.stato-alto').textContent, /Collegamento di casa non protetto/);
  const foglio = await impostazione(p, 'gmail');
  assert.ok(!p.q('#gm-password'), 'nessun campo password su http');
  assert.match(foglio.textContent, /https di Tailscale/);
  p.chiudi();
});

test('15.13 risposta già data: niente segno "Risposta pronta"', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(812, { risposta_data: true })];
  s.bozze[55] = B.bozza(55, { mail_id: 812, modificata: true });
  const p = await B.pagina(s);
  await posta(p);
  assert.doesNotMatch(p.righe()[0].textContent, /Risposta pronta/i);
  assert.match(p.righe()[0].getAttribute('aria-label'), /risposta data/);
  p.chiudi();
});

test('15.14 un gruppo nuovo chiede che mail ci vanno; la descrizione si vede', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  const foglio = await impostazione(p, 'gruppi', () => p.q('#nuovo-gruppo-descrizione'));
  assert.match(foglio.textContent, /Ci vanno: un cliente chiede o conferma un lavoro/);
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
    { quando: '2026-10-10T09:00:00+02:00', titolo: 'Urgente da Luca Bianchi', testo: 'Altra mail', destinazione: 'mail/811' },
    { quando: '2026-10-10T08:58:00+02:00', titolo: 'Urgente da Mario Rossi', testo: 'Offerta', destinazione: 'mail/812' },
  ];
  const p = await B.pagina(s);
  p.q('.voce-tu').click();
  await p.aspetta(() => p.q('.riga-imp[data-imp="notifiche"]'));
  p.q('.riga-imp[data-imp="notifiche"]').click();
  await p.aspetta(() => p.tutti('.riga-aspetti').length === 2);
  await p.clicTesto('Apri', p.tutti('.riga-aspetti')[1]);
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

test('16.3 sulla riga la pillola rossa "Possibile truffa" è l\'unico segno', opz, async () => {
  const s = conTruffa();
  s.bozze[70] = B.bozza(70, { mail_id: 819 });
  const p = await B.pagina(s);
  await posta(p);
  const segni = p.tutti('.segno', p.riga(820));
  assert.deepEqual(segni.map((x) => x.textContent), ['Possibile truffa']);
  assert.ok(segni[0].classList.contains('truffa'));
  assert.ok(p.riga(820).classList.contains('urgente'), 'l\'urgenza si vede dall\'ora, non da un secondo segno');
  assert.match(p.riga(820).getAttribute('aria-label'), /^possibile truffa, urgente, non letta/, 'il lettore schermo la legge per prima');
  assert.deepEqual(p.tutti('.segno', p.riga(819)).map((x) => x.textContent), ['Risposta pronta']);
  p.chiudi();
});

test('16.3 mail aperta: avviso sotto l\'oggetto e sopra "In breve", col testo del contratto e "Non è una truffa"', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.riga(820));
  await p.aspetta(() => p.q('.riquadro.truffa'));
  const figli = Array.from(p.q('.mail-aperta').children);
  const posto = (sel) => figli.indexOf(p.q(sel));
  assert.equal(posto('.riquadro.truffa'), posto('.oggetto-grande') + 1, 'subito dopo l\'oggetto');
  assert.ok(posto('.riquadro.truffa') < posto('.in-breve'), 'sopra "In breve"');
  assert.ok(posto('.in-breve') < posto('.corpo-mail'), 'poi il testo');
  assert.equal(p.q('.testo-truffa').textContent, AVVISO_TRUFFA);
  await p.clicTesto('Non è una truffa');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/820/sospetta'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/mail/820/sospetta').corpo, { sospetta: false });
  await p.aspetta(() => !p.q('.riquadro.truffa'));
  assert.ok(!/Possibile truffa/.test(p.riga(820).textContent), 'la pillola sparisce');
  p.chiudi();
});

test('16.3 sulle mail non segnalate: "Segnala come possibile truffa" nel menu delle altre azioni', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.riga(819));
  await p.aspetta(() => p.q('.mail-aperta .pulsante-menu'));
  assert.equal(p.pulsante('Segnala come possibile truffa'), null, 'non è in vista finché non si apre il menu');
  p.q('.mail-aperta .pulsante-menu').click();
  await p.aspetta(() => p.pulsante('Segnala come possibile truffa'));
  const pulsante = p.pulsante('Segnala come possibile truffa');
  assert.ok(pulsante.closest('.menu-azioni'));
  assert.ok(pulsante.classList.contains('discreto'));
  await p.clicTesto('Segnala come possibile truffa');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/819/sospetta'));
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/mail/819/sospetta').corpo, { sospetta: true });
  await p.aspetta(() => p.q('.riquadro.truffa'));
  assert.ok(p.q('.riquadro.truffa'));
  p.chiudi();
});

test('16.3 in Oggi la possibile truffa ha la sua scheda rossa con "Guarda"; #truffe filtra la Posta', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.voce-oggi.truffa'));
  const voce = p.q('.voce-oggi.truffa');
  assert.match(voce.textContent, /Possibile truffa/);
  assert.ok(p.q('.cerchio.truffa', voce));
  assert.equal(p.q('button', voce).textContent, 'Guarda');
  p.q('button', voce).click();
  await p.aspetta(() => p.q('.riquadro.truffa'));
  assert.equal(p.q('.oggetto-grande').textContent, 'Nuovo IBAN per i pagamenti');
  p.w.solApri('truffe');
  await p.aspetta(() => s.log.some((x) => /\/server\/api\/mail\?sospetta=true/.test(x.url)) && p.righe().length === 1);
  assert.equal(p.q('.filtro-attivo').textContent, 'Possibili truffe · togli filtro');
  p.chiudi();
});

test('16.3 Invio ("va bene così") su una mail sospetta accetta il gruppo ma NON toglie il sospetto', opz, async () => {
  const s = conTruffa();
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.riga(820));
  await p.aspetta(() => p.q('.riquadro.truffa'));
  assert.match(p.q('.riga-controllo').textContent, /resta segnalata/i);
  p.q('.elenco-mail').focus();
  p.tasto('Enter', {}, p.q('.elenco-mail'));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/820/conferma'));
  await B.dorme(120);
  assert.ok(!s.log.some((x) => x.url.endsWith('/sospetta')), 'nessuna richiesta che tolga il sospetto');
  const mail = s.mails.find((m) => m.id === 820);
  assert.equal(mail.smistamento, 'smistata', 'il gruppo è accettato');
  assert.equal(mail.sospetta, true, 'la mail resta segnalata');
  assert.match(p.riga(820).textContent, /Possibile truffa/, 'e la pillola resta');
  p.chiudi();
});

test('16.3 rispondendo a una mail sospetta la risposta avvisa in rosso', opz, async () => {
  const s = conTruffa();
  s.bozze[70] = B.bozza(70, { mail_id: 820, oggetto: 'Re: Nuovo IBAN per i pagamenti' });
  const p = await B.pagina(s);
  await posta(p);
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
  const foglio = await impostazione(p, 'truffe', () => p.q('#imp-truffa'));
  const soglia = p.q('#imp-truffa');
  assert.equal(soglia.min, '20');
  assert.equal(soglia.max, '90');
  assert.equal(soglia.value, '50');
  assert.match(foglio.textContent, /Meglio una segnalazione in più che una truffa non vista/);
  soglia.value = '30';
  soglia.dispatchEvent(new p.w.Event('input', { bubbles: true }));
  await p.clicTesto('Salva', foglio);
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH' && x.url === '/server/api/impostazioni'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH').corpo, { soglia_truffa: 0.3 });
  p.chiudi();
});

// --- Sol 2.0: Oggi (§3, GET /api/oggi) -----------------------------------------------------------

function giornata() {
  const s = B.server();
  s.impostazioni.nome = 'Paolo';
  s.mails = [
    B.mail(820, { oggetto: 'Nuovo IBAN', gruppo_id: 2, mittente: { nome: 'Ferramenta Neri', indirizzo: 'pagamenti@neri.esempio.it' }, sospetta: true }),
    B.mail(812, { oggetto: 'Offerta capannone Bassano', urgenza: 'alta', letta: true }),
    B.mail(815, { oggetto: 'Pratica 114', gruppo_id: 4, mittente: { nome: 'Comune di Bassano', indirizzo: 'urbanistica@comune.esempio.it' } }),
    B.mail(814, { oggetto: 'Preventivo compressore', gruppo_id: 2, smistamento: 'da_controllare', da_rispondere: false }),
  ];
  s.bozze[55] = B.bozza(55, { mail_id: 812 });
  s.indietro.aspetti_tu = [{ inviata: { id: 77, casella_id: 1, a: ['neri@esempio.it'], oggetto: 'Offerta compressore', inviata_il: '2026-10-02T10:00:00+02:00', anteprima: '' }, giorni: 8, bozza_sollecito: null }];
  s.scadenze = [{ id: 4, mail_id: 812, descrizione: 'Offerta a Rossi', quando: '2026-10-16', ora: null, stato: 'aperta' }];
  s.riassuntoOggi = { testo: 'Rossi aspetta l\'offerta per il capannone [#812]. Una mail chiede un IBAN nuovo [#820].', stato: 'pronto', aggiornato_il: '2026-10-11T09:00:00+02:00', mail_citate: [812, 820] };
  s.fatteOggi = 2;
  return s;
}

test('Oggi: saluto col nome, il punto con i riferimenti toccabili, la scadenza più vicina', opz, async () => {
  const s = giornata();
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.punto-oggi .testo-punto'));
  assert.match(p.q('.testa-oggi h1').textContent, /^(Buongiorno|Buon pomeriggio|Buonasera), Paolo$/);
  assert.match(p.q('.testa-oggi .sotto-titolo').textContent, /11 ottobre · 4 mail nuove$/);
  assert.equal(p.q('.voce-tu').textContent, 'P', 'l\'iniziale del nome in fondo alla colonna di sinistra');
  const riferimenti = p.tutti('.punto-oggi a.riferimento');
  assert.deepEqual(riferimenti.map((a) => [a.textContent, a.getAttribute('href')]), [['Rossi', '#mail/812'], ['Neri', '#mail/820']], 'il nome di chi ha scritto, non "[#812]"');
  assert.doesNotMatch(p.q('.punto-oggi').textContent, /\[#/);
  assert.match(p.q('.pillola-scadenza').textContent, /: offerta a Rossi$/);
  p.chiudi();
});

test('Oggi: "Da fare" in ordine, ognuna col suo pulsante; "N di M fatte" e la barra', opz, async () => {
  const s = giornata();
  const p = await B.pagina(s);
  await p.aspetta(() => p.tutti('.voce-oggi').length === 6);
  const voci = p.tutti('.voce-oggi');
  assert.deepEqual(voci.map((v) => v.dataset.tipo), ['truffa', 'risposta', 'smista', 'rispondi', 'sollecito', 'scadenza']);
  assert.deepEqual(voci.map((v) => p.q('button', v).textContent), ['Guarda', 'Rivedi', 'Inizia', 'Rispondi', 'Prepara', 'Fatto']);
  assert.ok(p.q('button', voci[1]).classList.contains('pieno'), 'solo la risposta pronta ha il pulsante pieno');
  assert.equal(p.tutti('.voce-oggi button.pieno').length, 1);
  assert.equal(p.q('.titolo-voce', voci[1]).textContent, 'Rispondi a Mario Rossi');
  assert.equal(p.q('.titolo-voce', voci[4]).textContent, 'neri@esempio.it non risponde da 8 giorni');
  assert.equal(p.q('.da-fare .conto').textContent, '2 di 8 fatte');
  assert.equal(p.q('.barra-avanzamento').getAttribute('aria-valuenow'), '25');
  p.q('button', voci[5]).click();
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH' && x.url === '/server/api/scadenze/4'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH').corpo, { stato: 'fatta' });
  await p.aspetta(() => p.q('.da-fare .conto').textContent === '3 di 8 fatte');
  p.q('button', p.q('.voce-oggi[data-tipo="risposta"]')).click();
  await p.aspetta(() => p.q('.oggetto-grande') && p.q('#bozza-testo'));
  assert.equal(p.q('.oggetto-grande').textContent, 'Offerta capannone Bassano', '"Rivedi" apre la mail con la risposta');
  p.chiudi();
});

test('Oggi: "Inizia" apre Smista; "Prepara" apre il sollecito in Aspetti risposta; i collegamenti in fondo', opz, async () => {
  const s = giornata();
  s.testoSollecito = { 77: 'Gentile Neri, le ricordo la nostra offerta.' };
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.voce-oggi[data-tipo="smista"]'));
  p.q('.voce-oggi[data-tipo="smista"] button').click();
  await p.aspetta(() => p.q('.scheda-grande h2'));
  assert.equal(p.q('.testa-smista .titolo').textContent, 'Smista');
  p.q('.voce-rail[data-vista="oggi"]').click();
  await p.aspetta(() => p.q('.voce-oggi[data-tipo="sollecito"]'));
  p.q('.voce-oggi[data-tipo="sollecito"] button').click();
  await p.aspetta(() => p.q('.spazio-bozza textarea.testo-bozza'));
  assert.match(p.q('.spazio-bozza textarea.testo-bozza').value, /le ricordo la nostra offerta/);
  p.q('.voce-rail[data-vista="oggi"]').click();
  await p.aspetta(() => p.q('.riga-collegamento[data-vista="scadenze"]'));
  p.q('.riga-collegamento[data-vista="scadenze"]').click();
  await p.aspetta(() => p.q('.giorno-scadenze'));
  assert.ok(p.q('.giorno-scadenze'));
  p.chiudi();
});

test('Oggi: tutto fatto = una riga calma; riassunto in preparazione = righe grigie', opz, async () => {
  const s = B.server();
  s.riassuntoOggi = { testo: null, stato: 'in_preparazione' };
  s.fatteOggi = 3;
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.tutto-fatto'));
  assert.match(p.q('.tutto-fatto').textContent, /Niente da fare\. Bella giornata\./);
  assert.equal(p.tutti('.voce-oggi').length, 0);
  assert.ok(p.q('.punto-oggi .scheletro'), 'il riassunto che si sta preparando: tre righe grigie');
  p.chiudi();
});

test('Oggi con un server di prima della v1.4 (niente /api/oggi): la pagina si mette insieme lo stesso', opz, async () => {
  const s = giornata();
  s.oggi = false;
  const p = await B.pagina(s);
  await p.aspetta(() => p.tutti('.voce-oggi').length >= 4);
  const tipi = p.tutti('.voce-oggi').map((v) => v.dataset.tipo);
  assert.deepEqual(tipi.slice(0, 3), ['truffa', 'risposta', 'smista']);
  assert.ok(s.log.some((x) => x.url === '/server/api/riassunti/oggi'));
  assert.match(p.q('.testa-oggi .sotto-titolo').textContent, /ottobre$/, 'senza il numero delle mail nuove');
  p.chiudi();
});

// --- Sol 2.0: Posta (§4) ------------------------------------------------------------------------

function postaPiena() {
  const s = B.server();
  s.mails = [
    B.mail(901, { gruppo_id: 1, mittente: { nome: 'Mario Rossi', indirizzo: 'rossi@esempio.it' } }),
    B.mail(902, { gruppo_id: 2, mittente: { nome: 'Ferramenta Neri', indirizzo: 'ordini@neri.esempio.it' }, letta: true }),
    B.mail(903, { gruppo_id: 5, oggetto: 'Le novità antincendio', mittente: { nome: 'Notizie Tecniche', indirizzo: 'news@esempio.it' }, letta: true }),
    B.mail(904, { gruppo_id: 5, oggetto: 'Webinar sulla nuova norma quadri', mittente: { nome: 'Notizie Tecniche', indirizzo: 'news@esempio.it' } }),
    B.mail(905, { gruppo_id: 1, smistamento: 'da_controllare', mittente: { nome: 'Laura Bianchi', indirizzo: 'bianchi@esempio.it' } }),
    B.mail(906, { gruppo_id: 3, oggetto: 'Fattura ottobre', mittente: { nome: 'Studio Verdi', indirizzo: 'verdi@esempio.it' } }),
  ];
  return s;
}

test('Posta "Importanti": sezioni per gruppo col pallino del colore, poi le raccolte in una riga sola', opz, async () => {
  const s = postaPiena();
  const p = await B.pagina(s);
  await posta(p);
  const sezioni = p.tutti('.sezione-posta[data-gruppo]');
  assert.deepEqual(sezioni.map((x) => x.dataset.gruppo), ['1', '2'], 'solo i gruppi che non sono raccolte, nell\'ordine');
  assert.ok(sezioni[0].classList.contains('col-blu') && p.q('.pallino', sezioni[0]));
  assert.deepEqual(p.tutti('li.mail', sezioni[0]).map((li) => li.dataset.id), ['905', '901']);
  assert.ok(p.q('.avatar', p.riga(901)) && p.riga(901).classList.contains('col-blu'), 'il cerchio col colore del gruppo');
  const raccolte = p.tutti('.riga-raccolta');
  assert.deepEqual(raccolte.map((r) => r.dataset.gruppo), ['3', '5']);
  assert.equal(p.q('.titolo-voce', raccolte[1]).textContent, 'Newsletter');
  assert.equal(p.q('.sotto-voce', raccolte[1]).textContent, '2 mail · Webinar sulla nuova norma quadri', 'come il prototipo: quante e l\'ultima');
  assert.ok(raccolte[1].classList.contains('da-leggere'), 'con mail da leggere il nome è in grassetto');
  assert.ok(!p.riga(904), 'le newsletter non sono mail per mail');
  assert.match(p.q('.scheda-incerte').textContent, /1 mail da sistemare/);
  raccolte[1].click();
  await p.aspetta(() => p.q('.testa-gruppo h1') && p.righe().length === 2);
  assert.equal(p.q('.testa-gruppo h1').textContent, 'Newsletter');
  assert.ok(s.log.some((x) => /\/server\/api\/mail\?gruppo_id=5&limite=50/.test(x.url)));
  await p.clicTesto('Segna tutte lette');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/904/letta'));
  assert.deepEqual(s.log.filter((x) => x.url.endsWith('/letta')).map((x) => x.url), ['/server/api/mail/904/letta'], 'solo quelle da leggere');
  p.q('.testa-gruppo .rotondo').click();
  await p.aspetta(() => p.q('.segmenti') && p.tutti('.sezione-posta[data-gruppo]').length === 2);
  assert.equal(p.q('.segmenti [data-segmento="importanti"]').getAttribute('aria-selected'), 'true');
  p.chiudi();
});

test('Posta "Tutte": tutte le mail per data, col pallino del gruppo accanto all\'ora', opz, async () => {
  const s = postaPiena();
  const p = await B.pagina(s);
  await posta(p, 'tutte');
  assert.deepEqual(p.righe().map((li) => li.dataset.id), ['906', '905', '904', '903', '902', '901']);
  assert.ok(p.q('.ora .pallino-gruppo', p.riga(904)));
  assert.ok(!p.q('.scheda-incerte'), 'la scheda "da sistemare" sta in Importanti');
  p.chiudi();
});

test('Posta: la scheda "N mail da sistemare" apre Smista; la ricerca si apre con / e si chiude con Esc', opz, async () => {
  const s = postaPiena();
  const p = await B.pagina(s);
  await posta(p);
  p.q('.elenco-mail').focus();
  p.tasto('/', {}, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('#cerca'));
  p.scrivi(p.q('#cerca'), 'Webinar');
  await p.aspetta(() => s.log.some((x) => /cerca=Webinar/.test(x.url)) && p.righe().length === 1);
  assert.equal(p.righe()[0].dataset.id, '904');
  p.tasto('Escape', {}, p.q('#cerca'));
  await p.aspetta(() => p.q('.segmenti') && !p.q('#cerca'));
  p.q('.scheda-incerte').click();
  await p.aspetta(() => p.q('.scheda-grande h2'));
  assert.equal(p.q('.contatore').textContent, '1 di 1');
  p.chiudi();
});

// --- Sol 2.0: la mail aperta e la barra in basso (§5) ------------------------------------------------

test('mail aperta come il prototipo: intestazione, oggetto, "In breve:", testo, allegati, risposta di Sol', opz, async () => {
  const s = await conMailEBozza({ allegati: [{ nome: 'pianta.pdf', byte: 482113 }] });
  const p = await B.pagina(s);
  await apriPrima(p);
  const figli = Array.from(p.q('.mail-aperta').children).map((x) => x.className.split(' ')[0]);
  assert.deepEqual(figli.filter((c) => ['testa-mail', 'oggetto-grande', 'in-breve', 'corpo-mail', 'allegati'].includes(c)), ['testa-mail', 'oggetto-grande', 'in-breve', 'corpo-mail', 'allegati']);
  assert.equal(p.q('.in-breve strong').textContent, 'In breve:');
  assert.match(p.q('.testa-mail .dove').textContent, /^rossi@esempio\.it · /);
  assert.match(p.q('.allegati').textContent, /pianta\.pdf · 471 kB/);
  assert.match(p.q('.risposta-sol .titolo-risposta').textContent, /Risposta di Sol/);
  assert.equal(p.q('.risposta-sol .destinatario').textContent, 'a rossi@esempio.it');
  assert.ok(!p.q('#bozza-a'), 'A, Cc e Oggetto chiusi finché non si tocca il destinatario');
  await apriCampi(p);
  assert.equal(p.q('#bozza-a').value, 'rossi@esempio.it');
  assert.equal(p.q('#bozza-oggetto').value, 'Re: Offerta capannone Bassano');
  assert.deepEqual(p.tutti('.barra-risposta button').map((b) => b.textContent), ['Invia risposta'], 'in basso una cosa sola: Invia risposta');
  assert.ok(p.q('.barra-risposta button').classList.contains('pieno'));
  p.chiudi();
});

test('barra in basso: "Prepara la risposta" se va risposta; niente barra se non chiede risposta (Rispondi è nel menu) o è sospetta', opz, async () => {
  const s = B.server();
  s.mails = [
    B.mail(950, { letta: true, da_rispondere: true }),
    B.mail(951, { letta: true, da_rispondere: false }),
    B.mail(952, { letta: true, sospetta: true, da_rispondere: true }),
  ];
  s.prontaDopoMs = 200;
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.riga(951));
  await p.aspetta(() => p.q('.oggetto-grande') && /951/.test(p.q('.oggetto-grande').textContent));
  await B.dorme(40);
  assert.equal(p.q('.barra-risposta').children.length, 0, 'mail che non chiede risposta: niente barra');
  p.q('.mail-aperta .pulsante-menu').click();
  await p.aspetta(() => p.pulsante('Rispondi', p.q('.menu-azioni')));
  p.clic(p.riga(952));
  await p.aspetta(() => p.q('.riquadro.truffa'));
  await B.dorme(40);
  assert.equal(p.q('.barra-risposta').children.length, 0, 'possibile truffa senza risposta chiesta: niente barra');
  p.clic(p.riga(950));
  await p.aspetta(() => p.pulsante('Prepara la risposta'));
  p.pulsante('Prepara la risposta').click();
  await p.aspetta(() => /Sol sta scrivendo la risposta/.test(p.q('.barra-risposta').textContent));
  assert.ok(p.q('.barra-risposta .indicatore'));
  assert.ok(p.q('.risposta-sol .scheletro'), 'nel riquadro le righe grigie');
  await p.aspetta(() => p.q('#bozza-testo'), 3000);
  assert.deepEqual(p.tutti('.barra-risposta button').map((b) => b.textContent), ['Invia risposta']);
  p.chiudi();
});

test('menu delle altre azioni: da leggere, non serve rispondere, segnala, rispondi a tutti, scarta (con conferma)', opz, async () => {
  const s = await conMailEBozza({ destinatari: ['studio@esempio.it'], cc: ['bianchi@esempio.it'] });
  const p = await B.pagina(s);
  await apriPrima(p);
  const menu = () => p.q('.menu-azioni');
  const apri = async () => { p.q('.mail-aperta .pulsante-menu').click(); await p.aspetta(() => !menu().hidden); };
  await apri();
  assert.deepEqual(p.tutti('button', menu()).map((b) => b.textContent), ['Segna come da leggere', 'Non serve rispondere', 'Rispondi a tutti', 'Segnala come possibile truffa', 'Scarta la risposta']);
  await p.clicTesto('Rispondi a tutti', menu());
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PUT'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PUT').corpo.cc, ['bianchi@esempio.it'], 'gli altri destinatari in Cc (non la nostra casella)');
  assert.equal(p.q('#bozza-cc').value, 'bianchi@esempio.it');
  await apri();
  await p.clicTesto('Scarta la risposta', menu());
  await p.aspetta(() => p.pulsante('Sì, scarta'));
  assert.ok(!s.log.some((x) => x.url.endsWith('/scarta')), 'prima chiede');
  await p.clicTesto('Sì, scarta');
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/bozze/55/scarta'));
  assert.ok(s.log.some((x) => x.url === '/server/api/bozze/55/scarta'));
  p.chiudi();
});

test('"È nel gruppo giusto?" solo sulle incerte, sotto l\'intestazione: [Sì] accetta, [Cambia] apre "Sposta in"', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(960, { letta: true, smistamento: 'da_controllare', gruppo_id: 2 }), B.mail(961, { letta: true })];
  const p = await B.pagina(s);
  await posta(p);
  p.clic(p.riga(961));
  await p.aspetta(() => p.q('.oggetto-grande') && /961/.test(p.q('.oggetto-grande').textContent));
  assert.equal(p.q('.riga-controllo'), null, 'le altre non hanno riquadri sul gruppo');
  p.clic(p.riga(960));
  await p.aspetta(() => p.q('.riga-controllo'));
  const riga = p.q('.riga-controllo');
  assert.match(riga.textContent, /È nel gruppo giusto\?/);
  await p.clicTesto('Cambia', riga);
  await p.aspetta(() => !p.q('.sposta-in-elenco').hidden);
  assert.ok(p.q('.sposta-in-elenco .voce-gruppo.attuale[data-gruppo="2"]'), 'il gruppo di adesso è segnato');
  p.tasto('Escape', {}, p.d.activeElement);
  await B.dorme(30);
  await p.clicTesto('Sì', p.q('.riga-controllo'));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/960/conferma'));
  assert.ok(!s.log.some((x) => x.url.endsWith('/sposta')));
  p.chiudi();
});

test('spostare una mail sola: "Spostata in Fornitori · Annulla", e Annulla la rimette dov\'era', opz, async () => {
  const s = await conMailEBozza();
  const p = await B.pagina(s);
  await apriPrima(p);
  p.q('#bozza-testo').blur();
  p.q('.elenco-mail').focus();
  p.tasto('2', {}, p.q('.elenco-mail'));
  await p.aspetta(() => p.q('.avviso-azione'));
  assert.match(p.avvisi()[0], /^Spostata in Fornitori/);
  assert.deepEqual(s.log.find((x) => x.url.endsWith('/sposta')).corpo, { gruppo_id: 2, impara: true });
  p.q('.avviso-azione').click();
  await p.aspetta(() => s.log.filter((x) => x.url.endsWith('/sposta')).length === 2);
  assert.deepEqual(s.log.filter((x) => x.url.endsWith('/sposta'))[1].corpo, { gruppo_id: 1, impara: true }, 'stessa regola, gruppo di prima (§17)');
  await p.aspetta(() => s.mails.find((m) => m.id === 812).gruppo_id === 1);
  p.chiudi();
});

test('trascinando una mail compaiono i gruppi: quello sotto si illumina del suo colore, al rilascio ci va', opz, async () => {
  const s = B.server();
  s.mails = [B.mail(970, { letta: true })];
  const p = await B.pagina(s);
  await posta(p);
  const dati = {};
  const evento = (tipo) => {
    const e = new p.w.Event(tipo, { bubbles: true, cancelable: true });
    Object.defineProperty(e, 'dataTransfer', { value: { types: ['text/x-sol-mail'], setData: (k, v) => { dati[k] = v; }, getData: (k) => dati[k] || '', dropEffect: 'none', effectAllowed: 'all' } });
    return e;
  };
  p.riga(970).dispatchEvent(evento('dragstart'));
  await p.aspetta(() => p.q('.zona-trascina'));
  const bersaglio = p.q('.zona-trascina .bersaglio[data-gruppo="2"]');
  assert.ok(bersaglio.classList.contains('col-verde'));
  bersaglio.dispatchEvent(evento('dragover'));
  assert.ok(bersaglio.classList.contains('sopra'), 'si illumina');
  bersaglio.dispatchEvent(evento('dragleave'));
  assert.ok(!bersaglio.classList.contains('sopra'));
  bersaglio.dispatchEvent(evento('dragover'));
  bersaglio.dispatchEvent(evento('drop'));
  await p.aspetta(() => s.log.some((x) => x.url === '/server/api/mail/970/sposta'));
  assert.equal(s.log.find((x) => x.url === '/server/api/mail/970/sposta').corpo.gruppo_id, 2);
  assert.equal(p.q('.zona-trascina'), null, 'finito il trascinamento, i gruppi se ne vanno');
  p.chiudi();
});

test('movimenti: la riga spostata scivola via, ma niente movimenti se Windows ne chiede meno', opz, async () => {
  for (const meno of [false, true]) {
    const s = B.server();
    s.mails = [B.mail(990, { letta: true }), B.mail(991, { letta: true })];
    const p = await B.pagina(s);
    p.w.matchMedia = (q) => ({ matches: meno && /prefers-reduced-motion: reduce/.test(q), media: q });
    await posta(p);
    let scivolata = false;
    new p.w.MutationObserver(() => { if (p.q('li.mail.uscita')) scivolata = true; })
      .observe(p.q('.elenco-mail'), { subtree: true, attributes: true, attributeFilter: ['class'] });
    p.clic(p.riga(990));
    await p.aspetta(() => p.q('.oggetto-grande') && /990/.test(p.q('.oggetto-grande').textContent));
    p.q('.elenco-mail').focus();
    p.tasto('5', {}, p.q('.elenco-mail')); // nella Newsletter (raccolta): esce da "Importanti"
    await p.aspetta(() => !p.riga(990));
    assert.equal(p.riga(990), null);
    assert.equal(scivolata, !meno, meno ? 'con meno movimenti la riga sparisce e basta' : 'la riga scivola via');
    p.chiudi();
  }
});

// --- Sol 2.0: Smista, una alla volta coi tasti (§6) ----------------------------------------------

function daSmistare() {
  const s = B.server();
  s.mails = [
    B.mail(933, { smistamento: 'da_controllare', gruppo_id: 2, sicurezza: 0.82, oggetto: 'Preventivo compressore', mittente: { nome: 'Ferramenta Neri', indirizzo: 'ordini@neri.esempio.it' } }),
    B.mail(932, { smistamento: 'da_controllare', gruppo_id: 3, sicurezza: 0.55, oggetto: 'Fattura ottobre', mittente: { nome: 'Luca Bianchi', indirizzo: 'bianchi@esempio.it' } }),
    B.mail(931, { smistamento: 'da_controllare', gruppo_id: 1, sicurezza: 0.5, oggetto: 'Riunione di cantiere', mittente: { nome: 'Studio Verdi', indirizzo: 'verdi@esempio.it' } }),
    B.mail(930, { smistamento: 'da_controllare', sospetta: true, oggetto: 'Nuovo IBAN' }),
  ];
  return s;
}

async function apriSmista(p) {
  p.w.solApri('smista');
  await p.aspetta(() => p.q('.scheda-grande h2'));
}

test('Smista: una scheda alla volta, "SOL PROPONE" col gruppo, le possibili truffe non ci passano', opz, async () => {
  const s = daSmistare();
  const p = await B.pagina(s);
  await apriSmista(p);
  assert.ok(s.log.some((x) => x.url === '/server/api/mail?smistamento=da_controllare&sospetta=false&limite=200'));
  assert.equal(p.q('.testa-smista .contatore').textContent, '1 di 3', 'la sospetta non è contata');
  const scheda = p.q('.scheda-grande');
  assert.equal(p.q('h2', scheda).textContent, 'Preventivo compressore');
  assert.match(p.q('.etichetta', scheda).textContent, /Sol propone/i);
  assert.ok(p.q('.pillola-gruppo', scheda).classList.contains('col-verde'));
  assert.equal(p.q('.pillola-gruppo', scheda).textContent, 'Fornitori');
  assert.equal(p.q('.sicurezza', scheda).textContent, 'abbastanza sicuro');
  assert.deepEqual(p.tutti('.azioni-smista button').map((b) => b.textContent.replace(/[←→]/g, '').trim()), ['Cambia', 'Va bene']);
  p.chiudi();
});

test('Smista coi tasti: → o Invio = Va bene, ← = Cambia e poi 1-9 = gruppo; tasto tenuto premuto = una volta sola', opz, async () => {
  const s = daSmistare();
  const p = await B.pagina(s);
  await apriSmista(p);
  p.tasto('ArrowRight', {}, p.d.body);
  for (let i = 0; i < 5; i++) p.tasto('ArrowRight', { repeat: true }, p.d.body);
  await p.aspetta(() => p.q('.testa-smista .contatore').textContent === '2 di 3');
  assert.deepEqual(s.log.filter((x) => x.metodo === 'POST').map((x) => x.url), ['/server/api/mail/933/conferma'], 'una volta sola');
  assert.equal(p.q('.scheda-grande h2').textContent, 'Fattura ottobre');
  assert.equal(p.q('.scheda-grande .sicurezza').textContent, 'poco sicuro');
  p.tasto('ArrowLeft', {}, p.d.body);
  await p.aspetta(() => p.q('.scelta-gruppo'));
  assert.deepEqual(p.tutti('.scelta-gruppo .pulsante-gruppo kbd').map((k) => k.textContent), ['1', '2', '3', '4', '5', '6']);
  p.tasto('2', {}, p.d.body);
  await p.aspetta(() => p.q('.testa-smista .contatore').textContent === '3 di 3');
  assert.deepEqual(s.log.find((x) => x.url === '/server/api/mail/932/sposta').corpo, { gruppo_id: 2, impara: true }, 'Sol si ricorda il mittente');
  p.tasto('Enter', {}, p.d.body);
  await p.aspetta(() => p.q('.fine-smista'));
  assert.match(p.q('.fine-smista').textContent, /Tutto sistemato/);
  assert.equal(p.q('.testa-smista .contatore').textContent, 'Fatto');
  assert.deepEqual(s.mails.filter((m) => m.smistamento === 'da_controllare').map((m) => m.id), [930], 'resta solo la possibile truffa');
  p.tasto('Escape', {}, p.d.body);
  await p.aspetta(() => p.q('.testa-oggi'));
  assert.ok(p.q('.testa-oggi'), 'Esc torna a Oggi');
  p.chiudi();
});

test('Smista: senza niente da smistare lo dice subito; un errore del server non fa andare avanti', opz, async () => {
  const s = daSmistare();
  s.risposta = (metodo, url) => (url.endsWith('/933/conferma') ? [502, { errore: 'La casella Studio non risponde.', codice: 'casella_non_raggiungibile' }] : undefined);
  const p = await B.pagina(s);
  await apriSmista(p);
  p.tasto('Enter', {}, p.d.body);
  await p.aspetta(() => p.avvisi().length);
  assert.deepEqual(p.avvisi(), ['La casella Studio non risponde.']);
  assert.equal(p.q('.testa-smista .contatore').textContent, '1 di 3', 'resta sulla stessa mail');
  p.chiudi();
  const vuoto = B.server();
  const p2 = await B.pagina(vuoto);
  p2.w.solApri('smista');
  await p2.aspetta(() => p2.q('.fine-smista'));
  assert.match(p2.q('.fine-smista').textContent, /Niente da smistare/);
  p2.chiudi();
});

// --- Sol 2.0: tutorial (§10) ----------------------------------------------------------------------

test('tutorial: parte da solo la prima volta, si prova su dati finti senza chiamare il server, poi non riparte', opz, async () => {
  const s = B.server();
  s.stato.tutorial_visto = false;
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.tutorial'));
  const prima = s.log.length;
  const frase = () => p.q('.tutorial-frase').textContent;
  assert.equal(frase(), 'Ogni mattina qui trovi il punto della giornata e cosa fare.');
  assert.equal(p.tutti('.pallini-avanzamento span').length, 6);
  await p.clicTesto('Rivedi', p.q('.tutorial'));
  assert.match(frase(), /Cambiale il tono con un clic/);
  const testoPrima = p.q('.tutorial .testo-finto').textContent;
  assert.equal(p.pulsante('Invia risposta', p.q('.tutorial')).disabled, true);
  await p.clicTesto('Più breve', p.q('.tutorial'));
  assert.notEqual(p.q('.tutorial .testo-finto').textContent, testoPrima, 'il testo cambia davvero');
  await p.clicTesto('Invia risposta', p.q('.tutorial'));
  assert.ok(p.q('.tutorial .cerchio-spunta'));
  await p.aspetta(() => /frecce/.test(frase()), 2000);
  p.tasto('ArrowDown', {}, p.d.body);
  await B.dorme(20);
  assert.match(frase(), /Premi un numero/);
  p.tasto('2', {}, p.d.body);
  await B.dorme(20);
  assert.match(p.q('.tutorial .toast-finto').textContent, /Spostata in Fornitori/);
  await p.aspetta(() => /Invio va bene/.test(frase()), 2000);
  p.tasto('Enter', {}, p.d.body);
  await p.aspetta(() => /telefona al mittente/.test(frase()), 2000);
  assert.ok(p.q('.tutorial .segno.truffa'));
  await p.clicTesto('Ho capito', p.q('.tutorial'));
  assert.equal(frase(), 'Sei pronto.');
  const durante = s.log.slice(prima);
  assert.deepEqual(durante.filter((x) => x.url.startsWith('/server/') || x.url === '/locale/invia'), [], 'nel tutorial niente server e niente invii');
  await p.clicTesto('Inizia', p.q('.tutorial'));
  await p.aspetta(() => !p.q('.tutorial') && s.stato.tutorial_visto === true);
  assert.ok(s.log.some((x) => x.url === '/locale/tutorial-visto'));
  p.chiudi();
  const p2 = await B.pagina(s);
  await B.dorme(80);
  assert.equal(p2.q('.tutorial'), null, 'la seconda volta non riparte');
  p2.chiudi();
});

test('tutorial: "Salta" lo chiude (e non riparte); si riapre da Impostazioni > "Come funziona Sol"', opz, async () => {
  const s = B.server();
  s.stato.tutorial_visto = false;
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.tutorial'));
  await p.clicTesto('Salta', p.q('.tutorial'));
  await p.aspetta(() => !p.q('.tutorial') && s.stato.tutorial_visto);
  p.q('.voce-tu').click();
  await p.aspetta(() => p.q('.riga-imp[data-imp="tutorial"]'));
  p.q('.riga-imp[data-imp="tutorial"]').click();
  await p.aspetta(() => p.q('.tutorial'));
  assert.equal(p.q('.tutorial-frase').textContent, 'Ogni mattina qui trovi il punto della giornata e cosa fare.');
  p.tasto('Escape', {}, p.d.body);
  await p.aspetta(() => !p.q('.tutorial'));
  p.chiudi();
});

// --- Sol 2.0: impostazioni, tema, abbinamento, niente frasi d'aiuto -----------------------------------

test('impostazioni: elenco a gruppi; il nome si salva e cambia l\'iniziale; il tema si sceglie', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  p.q('.voce-tu').click();
  await p.aspetta(() => p.tutti('.gruppo-imp').length);
  assert.deepEqual(p.tutti('.gruppo-imp > .etichetta').map((x) => x.textContent), ['Tu', 'Caselle di posta', 'Smistamento', 'Sicurezza', 'Promemoria', 'Questo computer', 'App']);
  await impostazione(p, 'nome', () => p.q('#imp-nome'));
  p.q('#imp-nome').value = 'Paolo';
  await p.clicTesto('Salva', p.q('.foglio'));
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH').corpo, { nome: 'Paolo' });
  await p.aspetta(() => p.q('.voce-tu').textContent === 'P');
  assert.match(p.q('.riga-imp[data-imp="nome"]').textContent, /Paolo/);
  await impostazione(p, 'tema', () => p.q('.foglio [data-tema="scuro"]'));
  p.q('.foglio [data-tema="scuro"]').click();
  await p.aspetta(() => p.d.documentElement.getAttribute('data-tema') === 'scuro');
  assert.deepEqual(s.log.find((x) => x.url === '/locale/tema').corpo, { tema: 'scuro' });
  p.q('.foglio [data-tema="auto"]').click();
  await p.aspetta(() => !p.d.documentElement.hasAttribute('data-tema'));
  p.chiudi();
});

test('impostazioni dei gruppi: colore tra i 12 (PATCH colore) e raccolta sì/no (PATCH raccolta)', opz, async () => {
  const s = B.server();
  const p = await B.pagina(s);
  const foglio = await impostazione(p, 'gruppi', () => p.tutti('.foglio .gruppo-impostato').length === 6);
  const riga = p.tutti('.gruppo-impostato', foglio)[0];
  assert.match(riga.textContent, /Clienti · blu/, 'il colore anche a parole');
  await p.clicTesto('Colore', riga);
  const colori = p.tutti('.colore-scelta', riga);
  assert.deepEqual(colori.map((c) => c.dataset.colore), B.COLORI);
  assert.equal(p.q('.colore-scelta[aria-pressed="true"]', riga).dataset.colore, 'blu');
  p.q('.colore-scelta[data-colore="petrolio"]', riga).click();
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH' && x.url === '/server/api/gruppi/1'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH' && x.url === '/server/api/gruppi/1').corpo, { colore: 'petrolio' });
  await p.aspetta(() => p.tutti('.foglio .gruppo-impostato')[0] && /petrolio/.test(p.tutti('.foglio .gruppo-impostato')[0].textContent));
  const raccolta = p.q('input[aria-label="Raccolta: Fornitori"]');
  assert.equal(raccolta.checked, false);
  raccolta.checked = true;
  raccolta.dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await p.aspetta(() => s.log.some((x) => x.metodo === 'PATCH' && x.url === '/server/api/gruppi/2'));
  assert.deepEqual(s.log.find((x) => x.metodo === 'PATCH' && x.url === '/server/api/gruppi/2').corpo, { raccolta: true });
  p.chiudi();
});

test('abbinamento: il codice in 8 caselle (anche incollato), poi il tutorial se non è mai stato visto', opz, async () => {
  const s = B.server();
  s.stato.abbinato = false;
  s.stato.tutorial_visto = false;
  s.risposta = (metodo, url) => {
    if (url === '/locale/abbina') { s.stato.abbinato = true; return [200, { ok: true }]; }
    return undefined;
  };
  const p = await B.pagina(s);
  await p.aspetta(() => p.q('.abbinamento'));
  assert.equal(p.q('.abbinamento h1').textContent, 'Collega Sol al server di casa');
  const cifre = p.tutti('.cifre input');
  assert.equal(cifre.length, 8);
  p.scrivi(p.q('#ab-server'), 'https://sol.esempio.ts.net');
  cifre[0].value = '1234 5678';
  cifre[0].dispatchEvent(new p.w.Event('input', { bubbles: true }));
  assert.deepEqual(cifre.map((c) => c.value).join(''), '12345678', 'incollato tutto nella prima, si riempiono tutte');
  p.q('.abbinamento').dispatchEvent(new p.w.Event('submit', { bubbles: true, cancelable: true }));
  await p.aspetta(() => s.log.some((x) => x.url === '/locale/abbina'));
  assert.equal(s.log.find((x) => x.url === '/locale/abbina').corpo.codice, '12345678');
  await p.aspetta(() => p.q('.tutorial'));
  assert.ok(p.q('.tutorial'), 'dopo l\'abbinamento parte il tutorial');
  p.chiudi();
});

test('niente frasi d\'aiuto fisse nelle pagine (§9): si impara col tutorial', opz, async () => {
  const s = giornata();
  s.bozze[56] = B.bozza(56, { mail_id: 815 });
  const p = await B.pagina(s);
  const vietate = /Puoi anche dettare|Oppure scorri|Prima di spedire Sol mostra|trascina qui le mail|Con le frecce scorri|Ricordati questo mittente|Sol per ora suggerisce/;
  const testi = [];
  await p.aspetta(() => p.q('.voce-oggi'));
  testi.push(p.d.body.textContent);
  await posta(p);
  p.clic(p.riga(815));
  await p.aspetta(() => p.q('#bozza-testo'));
  testi.push(p.d.body.textContent);
  for (const vista of ['riassunti', 'scadenze', 'aspetti']) {
    p.q('.voce-rail[data-vista="' + vista + '"]').click();
    await B.dorme(80);
    testi.push(p.d.body.textContent);
  }
  p.q('.voce-tu').click();
  await B.dorme(80);
  testi.push(p.d.body.textContent);
  for (const t of testi) assert.doesNotMatch(t, vietate);
  p.chiudi();
});
