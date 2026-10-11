// L'aspetto della pagina (pc/ui/stile.css) contro la guida docs/aspetto.md (Sol 2.0) e il prototipo approvato:
// fondo neutro e schede bianche, accento solo sulle azioni principali, colori dei gruppi solo in cerchi e
// pallini, rosso solo per le possibili truffe, tema scuro uguale in automatico e scelto, contrasti (testo
// 4,5:1, pallini 3:1), movimenti brevi e tolti con prefers-reduced-motion. Non serve jsdom: si legge il CSS.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const CSS = fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'stile.css'), 'utf8');
const L = require('../../ui/logica.js');

// Gli esempi della guida (§2): colore pieno / tenue / iniziali.
const GUIDA = {
  blu: ['#2F6FDB', '#EAF1FD', '#1E4FA8'],
  verde: ['#2B8A3E', '#EBF7EE', '#1F6A30'],
  ambra: ['#B7791F', '#FBF3E3', '#8A5A12'],
  viola: ['#7048E8', '#F1ECFE', '#5532C9'],
  grigio: ['#868E96', '#F1F3F5', '#495057'],
  ardesia: ['#495057', '#EEF0F2', '#343A40'],
};

/** Il testo del primo blocco { ... } che comincia con `inizio`, a partire da `da`. */
function blocco(inizio, da) {
  const i = CSS.indexOf(inizio, da || 0);
  assert.ok(i >= 0, 'manca ' + inizio);
  let livello = 0;
  for (let j = CSS.indexOf('{', i); j < CSS.length; j++) {
    if (CSS[j] === '{') livello++;
    if (CSS[j] === '}' && --livello === 0) return { testo: CSS.slice(i, j + 1), fine: j + 1 };
  }
  throw new Error('blocco non chiuso: ' + inizio);
}

function variabili(testo) {
  const v = {};
  for (const m of testo.matchAll(/--([\w-]+):\s*([^;]+);/g)) v[m[1]] = m[2].trim();
  return v;
}

const radice = blocco(':root {');
const scuroAuto = blocco('@media (prefers-color-scheme: dark)', radice.fine);
const scuroScelto = blocco(':root[data-tema="scuro"] {', scuroAuto.fine);
const chiaro = variabili(radice.testo);
const scuro = Object.assign({}, chiaro, variabili(scuroScelto.testo));

/** I colori dei gruppi: { nome: { g, 'g-tenue', 'g-ink', 'g-punto' } } nel tema chiaro e nei due blocchi scuri. */
function coloriGruppi() {
  const c = {};
  for (const m of CSS.matchAll(/^\.col-(\w+) \{([^}]*)\}/gm)) c[m[1]] = variabili(m[2]);
  const auto = {};
  const scelto = {};
  const gruppiAuto = blocco('@media (prefers-color-scheme: dark)', scuroScelto.fine).testo;
  for (const m of gruppiAuto.matchAll(/:root:not\(\[data-tema="chiaro"\]\) \.col-(\w+) \{([^}]*)\}/g)) auto[m[1]] = variabili(m[2]);
  for (const m of CSS.matchAll(/^:root\[data-tema="scuro"\] \.col-(\w+) \{([^}]*)\}/gm)) scelto[m[1]] = variabili(m[2]);
  return { chiaro: c, auto, scelto };
}
const GRUPPI = coloriGruppi();

function luminanza(hex) {
  const n = hex.replace('#', '');
  const c = [0, 2, 4].map((i) => parseInt(n.slice(i, i + 2), 16) / 255).map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

function contrasto(a, b) {
  const [x, y] = [luminanza(a), luminanza(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

test('i fondi e le superfici della guida: neutri, schede bianche (chiaro) e grigio scuro (scuro)', () => {
  assert.equal(chiaro.fondo, '#F4F4F2');
  assert.equal(chiaro.superficie, '#FFFFFF');
  assert.equal(chiaro['superficie-2'], '#E7E6E2');
  assert.equal(chiaro.testo, '#1B1B1F');
  assert.equal(chiaro.tenue, '#6B6B73');
  assert.equal(chiaro.riga, '#EFEEEB');
  assert.equal(chiaro['accento-tenue'], '#FFF1E8');
  assert.equal(chiaro['accento-tenue-2'], '#FFE8D9');
  assert.equal(chiaro['accento-testo'], '#B23A0B');
  assert.deepEqual([chiaro.truffa, chiaro['truffa-tenue'], chiaro['truffa-testo']], ['#C92A2A', '#FDEEEC', '#9B1C1C']);
  assert.equal(scuro.fondo, '#121214');
  assert.equal(scuro.superficie, '#1C1C1F');
  assert.equal(scuro['superficie-2'], '#2A2A2E');
  assert.equal(scuro.testo, '#F2F2F3');
  assert.equal(scuro.tenue, '#A1A1AA');
  assert.equal(scuro['tenue-2'], '#85858E');
  assert.equal(scuro.riga, '#2C2C30');
  assert.equal(scuro['accento-tenue'], '#3A2418');
  assert.equal(scuro['accento-testo'], '#FF9A66');
  assert.deepEqual([scuro.truffa, scuro['truffa-tenue'], scuro['truffa-testo']], ['#E03131', '#3A1C1C', '#FFA8A8']);
  // L'accento della guida (#D9480F) col testo bianco fa 4,3:1: uno scalino più scuro per arrivare a 4,5:1.
  assert.equal(chiaro.accento, '#D4450E');
  assert.ok(contrasto(chiaro.accento, '#D9480F') < 1.1, 'a occhio è lo stesso arancio');
  assert.match(CSS, /body \{[^}]*background: var\(--fondo\)/);
  assert.match(CSS, /\.scheda \{[^}]*background: var\(--superficie\);[^}]*box-shadow: var\(--ombra-rilievo\)/, 'schede bianche con ombra leggera');
  assert.equal(scuro['ombra-rilievo'], 'none', 'nel tema scuro niente ombre');
});

test('il tema scuro scelto nelle impostazioni è uguale a quello automatico (colori e gruppi)', () => {
  const auto = variabili(scuroAuto.testo);
  assert.match(scuroAuto.testo, /:root:not\(\[data-tema="chiaro"\]\) \{/, 'l\'automatico non vale se si sceglie "chiaro"');
  assert.deepEqual(variabili(scuroScelto.testo), auto);
  assert.deepEqual(GRUPPI.scelto, GRUPPI.auto);
  assert.deepEqual(Object.keys(GRUPPI.auto).sort(), Object.keys(GRUPPI.chiaro).sort());
});

test('i 12 colori dei gruppi: quelli della guida e gli altri sei, ognuno con pieno, tenue, iniziali e pallino', () => {
  assert.deepEqual(Object.keys(GRUPPI.chiaro).sort(), L.COLORI_GRUPPI.slice().sort(), 'gli stessi 12 nomi del contratto');
  for (const [nome, [pieno, tenue, ink]] of Object.entries(GUIDA)) {
    const g = GRUPPI.chiaro[nome];
    assert.deepEqual([g.g, g['g-tenue'], g['g-ink']], [pieno, tenue, ink], nome);
    assert.equal(g['g-punto'], pieno, nome + ': pallino = colore pieno');
  }
  for (const [nome, g] of Object.entries(GRUPPI.chiaro)) {
    for (const k of ['g', 'g-tenue', 'g-ink', 'g-punto']) assert.match(g[k] || '', /^#[0-9A-F]{6}$/, nome + ' ' + k);
    for (const k of ['g-tenue', 'g-ink']) assert.match(GRUPPI.auto[nome][k] || '', /^#[0-9A-F]{6}$/, nome + ' ' + k + ' (scuro)');
  }
});

test('contrasti: testo almeno 4,5:1, pallini e testo grande almeno 3:1, in tutti e due i temi', () => {
  const coppie = [
    ['testo', 'fondo', 4.5], ['testo', 'superficie', 4.5], ['testo', 'superficie-2', 4.5], ['testo-2', 'superficie-2', 4.5],
    ['testo-corpo', 'superficie', 4.5], ['tenue', 'superficie', 4.5], ['tenue', 'fondo', 4.5], ['tenue', 'accento-tenue', 4.5],
    ['tenue-2', 'superficie', 4.5], ['accento-testo', 'accento-tenue', 4.5], ['accento-testo', 'accento-tenue-2', 4.5],
    ['accento-testo', 'superficie', 4.5], ['accento-testo', 'fondo', 4.5], ['su-accento', 'accento', 4.5],
    ['truffa-testo', 'truffa-tenue', 4.5], ['truffa-testo-2', 'truffa-tenue', 4.5], ['#FFFFFF', 'truffa', 4.5],
    ['urgente', 'superficie', 4.5], ['urgente', 'accento-tenue', 4.5], ['prova', 'prova-tenue', 4.5],
    ['avviso-testo', 'avviso-fondo', 4.5], ['avviso-azione', 'avviso-fondo', 4.5],
    ['accento', 'superficie', 3], ['ok', 'superficie', 3], ['truffa', 'superficie', 3],
  ];
  for (const [nomeTema, v] of [['chiaro', chiaro], ['scuro', scuro]]) {
    const colore = (x) => (x.startsWith('#') ? x : v[x]);
    for (const [a, b, minimo] of coppie) {
      const c = contrasto(colore(a), colore(b));
      assert.ok(c >= minimo, nomeTema + ': ' + a + ' su ' + b + ' = ' + c.toFixed(2) + ' (minimo ' + minimo + ')');
    }
  }
  for (const nome of Object.keys(GRUPPI.chiaro)) {
    const c = GRUPPI.chiaro[nome];
    const s = Object.assign({}, c, GRUPPI.auto[nome]);
    assert.ok(contrasto(c['g-ink'], c['g-tenue']) >= 4.5, nome + ': iniziali nel cerchio (chiaro)');
    assert.ok(contrasto(s['g-ink'], s['g-tenue']) >= 4.5, nome + ': iniziali nel cerchio (scuro)');
    assert.ok(contrasto(c['g-punto'], chiaro.superficie) >= 3, nome + ': pallino nel tema chiaro');
    assert.ok(contrasto(s['g-punto'], scuro.superficie) >= 3, nome + ': pallino nel tema scuro');
  }
});

test('i colori dei gruppi stanno solo in cerchi, pallini e pillole del gruppo', () => {
  const usi = [...CSS.matchAll(/([^{}]+)\{[^}]*var\(--g(?:-tenue|-ink|-punto)?[,)]/g)].map((m) => m[1].trim());
  const ammessi = /avatar|cerchio|pallino|chip-gruppo|pillola-gruppo|icona-raccolta|bersaglio|testa-sezione|voce-gruppo|colore-scelta|pulsante-gruppo|gruppo-impostato/;
  for (const sel of usi) assert.match(sel, ammessi, 'colore del gruppo fuori posto: ' + sel);
  assert.match(CSS, /\.avatar \{[^}]*background: var\(--g-tenue[^}]*color: var\(--g-ink/);
});

test('l\'accento pieno solo sulle azioni principali; il rosso solo per le possibili truffe', () => {
  const pieni = [...CSS.matchAll(/([^{}]+)\{[^}]*background: var\(--accento\)/g)].map((m) => m[1].trim());
  const ammessiAccento = /pulsante\.pieno|pulsante\.primario|pallino-numero|barra-avanzamento|barra-scorre|indicatore|pallini-avanzamento|interruttore/;
  for (const sel of pieni) assert.match(sel, ammessiAccento, 'accento pieno fuori posto: ' + sel);
  const rossi = [...CSS.matchAll(/([^{}]+)\{[^}]*var\(--(?:truffa|urgente)[\w-]*\)/g)].map((m) => m[1].trim());
  // La guida: rosso per le possibili truffe, l'ora delle urgenti (§4) e il pallino delle caselle in errore (§8).
  const ammessiRosso = /truffa|urgente|pallino-stato\.errore/;
  for (const sel of rossi) assert.match(sel, ammessiRosso, 'rosso fuori posto: ' + sel);
  assert.doesNotMatch(CSS, /\.pulsante\.pericolo \{[^}]*--truffa/, 'scartare non è una truffa');
  assert.match(CSS, /\.segno\.truffa \{[^}]*background: var\(--truffa\)/, 'la pillola rossa piena della possibile truffa');
  assert.match(CSS, /\.segno\.pronta \{[^}]*background: var\(--accento-tenue\);[^}]*color: var\(--accento-testo\)/);
});

test('la disposizione: colonna stretta a sinistra, Oggi a larghezza leggibile, Posta in due colonne', () => {
  const colonna = /\.app \{[^}]*grid-template-columns: (\d+)px/.exec(CSS);
  assert.ok(colonna && Number(colonna[1]) <= 100, 'colonna di sinistra stretta');
  const pagina = /\.pagina \{[^}]*max-width: (\d+)px/.exec(CSS);
  assert.ok(pagina && Number(pagina[1]) >= 600 && Number(pagina[1]) <= 760, 'Oggi non si allarga su tutto lo schermo');
  assert.match(CSS, /\.posta-griglia \{[^}]*grid-template-columns: minmax\(\d+px, \d+px\) minmax\(0, 1fr\)/, 'elenco al centro, mail a destra');
  assert.match(CSS, /\.barra-risposta \{ flex: none;/, 'la barra Invia in fondo alla colonna');
  assert.match(CSS, /\.barra-risposta:empty \{ display: none; \}/, 'senza pulsanti, nessuna barra');
});

test('Figtree dal pacchetto, niente caratteri o immagini presi da internet', () => {
  assert.match(CSS, /@font-face \{[^}]*font-family: "Figtree";[^}]*src: local\("Figtree"\), url\("font\/Figtree\.woff2"\)/);
  assert.match(chiaro.carattere, /^"Figtree", "Segoe UI", system-ui/, 'senza i file vale Segoe UI (col suo nero per i titoli)');
  assert.doesNotMatch(CSS, /https?:\/\//);
});

test('movimenti brevi e calmi; in loop solo le righe grigie di attesa; tutti tolti con prefers-reduced-motion', () => {
  const durata = parseInt(chiaro.durata, 10);
  assert.ok(durata >= 150 && durata <= 250, 'durata ' + chiaro.durata);
  const infiniti = [...CSS.matchAll(/animation:\s*(\w+)[^;]*infinite/g)].map((m) => m[1]);
  assert.deepEqual(infiniti, ['pulsa'], 'solo lo scheletro pulsa piano');
  assert.match(CSS, /animation: pulsa 1\.8s ease-in-out infinite alternate/);
  assert.doesNotMatch(CSS, /bounce|rimbalz|spin|ruota/i);
  for (const m of CSS.matchAll(/animation:\s*(\w+)\s+(\d+)ms/g)) {
    const ms = Number(m[2]);
    assert.ok(ms >= 150 && ms <= 250, 'animazione ' + m[1] + ' di ' + ms + ' ms');
  }
  assert.match(CSS, /animation: scorre 1\.6s var\(--curva\) 1 forwards/, 'la barra di "Sol riscrive" passa una volta');
  const meno = blocco('@media (prefers-reduced-motion: reduce)').testo;
  assert.match(meno, /\*, \*::before, \*::after \{ animation: none !important; transition: none !important; \}/);
  assert.match(meno, /li\.mail\.uscita \{ opacity: 1; transform: none; \}/);
});

test('trascinando, il gruppo sotto si illumina del suo colore; la riga che esce scivola via', () => {
  assert.match(CSS, /\.bersaglio\.sopra \{[^}]*background: var\(--g-tenue\);[^}]*color: var\(--g-ink\)/);
  assert.match(CSS, /li\.mail\.uscita \{[^}]*opacity: 0;[^}]*transform: translateX/);
  assert.match(CSS, /li\.mail\.entrata \{ animation: entra var\(--durata\)/);
});
