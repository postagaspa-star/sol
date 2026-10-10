// L'aspetto della pagina (pc/ui/stile.css) contro la guida docs/aspetto.md (v1.3): colori dei gruppi per
// nome, accento e fondi dei due temi, contrasti (testo 4,5:1, icone e testo grande 3:1), movimenti brevi
// e tolti con prefers-reduced-motion, niente animazioni in loop. Non serve jsdom: si legge il CSS.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const CSS = fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'stile.css'), 'utf8');

// La tabella della guida: base (cerchi), etichetta chiara (sfondo/testo), etichetta scura (sfondo/testo).
const GUIDA = {
  blu: ['#1D4ED8', '#DBEAFE', '#1E40AF', '#1E3A8A', '#BFDBFE'],
  verde: ['#15803D', '#DCFCE7', '#166534', '#14532D', '#BBF7D0'],
  ambra: ['#B45309', '#FEF3C7', '#92400E', '#78350F', '#FDE68A'],
  viola: ['#7E22CE', '#F3E8FF', '#6B21A8', '#581C87', '#E9D5FF'],
  grigio: ['#4B5563', '#F3F4F6', '#374151', '#374151', '#E5E7EB'],
  ardesia: ['#334155', '#E2E8F0', '#1E293B', '#1E293B', '#CBD5E1'],
  petrolio: ['#0F766E', '#CCFBF1', '#115E59', '#134E4A', '#99F6E4'],
  rosa: ['#BE185D', '#FCE7F3', '#9D174D', '#831843', '#FBCFE8'],
  indaco: ['#4338CA', '#E0E7FF', '#3730A3', '#312E81', '#C7D2FE'],
  oliva: ['#4D7C0F', '#ECFCCB', '#3F6212', '#365314', '#D9F99D'],
  marrone: ['#92400E', '#FFEDD5', '#7C2D12', '#7C2D12', '#FED7AA'],
  ciano: ['#0E7490', '#CFFAFE', '#155E75', '#164E63', '#A5F3FC'],
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
const scuroMedia = blocco('@media (prefers-color-scheme: dark)', radice.fine);
const chiaro = variabili(radice.testo);
const scuro = Object.assign({}, chiaro, variabili(scuroMedia.testo));
const gruppiScuri = blocco('@media (prefers-color-scheme: dark)', scuroMedia.fine).testo;

function luminanza(hex) {
  const n = hex.replace('#', '');
  const c = [0, 2, 4].map((i) => parseInt(n.slice(i, i + 2), 16) / 255).map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

function contrasto(a, b) {
  const [x, y] = [luminanza(a), luminanza(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

test('i 12 colori dei gruppi hanno le tinte della guida, nel tema chiaro e nello scuro', () => {
  for (const [nome, [base, sfC, txC, sfS, txS]] of Object.entries(GUIDA)) {
    const chiaroG = new RegExp('\\.col-' + nome + ' \\{ --g: ' + base + '; --g-sf: ' + sfC + '; --g-tx: ' + txC + '; \\}');
    assert.match(CSS, chiaroG, nome + ' (chiaro)');
    const scuroG = new RegExp('\\.col-' + nome + ' \\{ --g-sf: ' + sfS + '; --g-tx: ' + txS + '; \\}');
    assert.match(gruppiScuri, scuroG, nome + ' (scuro)');
  }
  const classi = new Set([...CSS.matchAll(/\.col-(\w+) \{ --g:/g)].map((m) => m[1]));
  assert.deepEqual([...classi].sort(), Object.keys(GUIDA).sort(), 'nessun colore in più o in meno');
});

test('accento "sole" e fondi caldi dei due temi come la guida', () => {
  assert.equal(chiaro.sole, '#C2410C');
  assert.equal(chiaro.pulsante, '#C2410C');
  assert.equal(chiaro.fondo, '#FFFBF7');
  assert.equal(chiaro.superficie, '#FFFFFF');
  assert.equal(chiaro['fascia-da'], '#EA580C');
  assert.equal(chiaro['fascia-a'], '#C2410C');
  assert.equal(scuro.sole, '#FB923C', 'selezionato nel tema scuro');
  assert.equal(scuro.fondo, '#14110F');
  assert.equal(scuro.superficie, '#1F1A17');
  assert.equal(scuro['fascia-da'], '#9A3412');
  assert.equal(scuro['fascia-a'], '#7C2D12');
  assert.equal(chiaro.truffa, '#B91C1C', 'etichetta piena rossa della possibile truffa');
  assert.match(CSS, /\.segno\.truffa \{[^}]*background: var\(--truffa\);[^}]*color: var\(--su-truffa\)/);
});

test('contrasti: testo almeno 4,5:1, icone e testo grande almeno 3:1, in tutti e due i temi', () => {
  const coppie = [
    ['testo', 'fondo', 4.5], ['testo-2', 'superficie', 4.5], ['testo-3', 'superficie', 4.5], ['testo-3', 'fondo', 4.5],
    ['testo-3', 'superficie-2', 4.5], ['testo-3', 'selezione', 4.5], ['testo-2', 'sole-tenue', 4.5],
    ['sole', 'superficie', 4.5], ['sole', 'fondo', 4.5], ['sole', 'selezione', 4.5], ['sole', 'sole-tenue', 3],
    ['urgente', 'superficie', 4.5], ['urgente', 'selezione', 4.5], ['controllare', 'superficie', 4.5], ['controllare', 'selezione', 4.5],
    ['ok', 'superficie', 4.5], ['truffa-testo', 'superficie', 4.5], ['truffa-testo', 'sole-tenue', 4.5],
    ['#FFFFFF', 'truffa', 4.5], ['#FFFFFF', 'pulsante', 4.5], ['#FFFFFF', 'pulsante-sopra', 4.5], ['su-sole', 'sole', 4.5],
    ['su-ok', 'ok', 3], ['prova', 'prova-tenue', 4.5], ['testo-3', 'controllare-tenue', 4.5], ['testo-3', 'ok-tenue', 4.5],
    ['testo', 'truffa-tenue', 4.5], ['sole-forte', 'sole-tenue', 4.5], ['truffa-bordo', 'truffa-tenue', 3],
  ];
  for (const [nomeTema, v] of [['chiaro', chiaro], ['scuro', scuro]]) {
    const colore = (x) => (x.startsWith('#') ? x : v[x]);
    for (const [a, b, minimo] of coppie) {
      const c = contrasto(colore(a), colore(b));
      assert.ok(c >= minimo, nomeTema + ': ' + a + ' su ' + b + ' = ' + c.toFixed(2) + ' (minimo ' + minimo + ')');
    }
    assert.ok(contrasto('#FFFFFF', colore('fascia-da')) >= 3, nomeTema + ': titolo bianco grande sulla fascia');
  }
  for (const [nome, [base, sfC, txC, sfS, txS]] of Object.entries(GUIDA)) {
    assert.ok(contrasto('#FFFFFF', base) >= 4.5, nome + ': iniziali bianche nel cerchio');
    assert.ok(contrasto(txC, sfC) >= 4.5, nome + ': etichetta chiara');
    assert.ok(contrasto(txS, sfS) >= 4.5, nome + ': etichetta scura');
    assert.ok(contrasto(base, chiaro.superficie) >= 3, nome + ': pallino nel tema chiaro');
    const segno = new RegExp('\\.col-' + nome + ' \\{ --g-segno: (#\\w+); \\}').exec(gruppiScuri);
    assert.ok(segno, nome + ': tinta del pallino nel tema scuro');
    assert.ok(contrasto(segno[1], scuro.superficie) >= 3, nome + ': pallino nel tema scuro');
  }
});

test('movimenti brevi e calmi, nessuna animazione in loop, tutti tolti con prefers-reduced-motion', () => {
  const durata = parseInt(chiaro.durata, 10);
  assert.ok(durata >= 150 && durata <= 250, 'durata ' + chiaro.durata);
  assert.doesNotMatch(CSS, /infinite/, 'niente animazioni che girano all\'infinito');
  assert.doesNotMatch(CSS, /bounce|rimbalz/i);
  for (const m of CSS.matchAll(/animation:\s*(\w+)\s+(\d+)ms/g)) {
    const ms = Number(m[2]);
    assert.ok(ms >= 150 && ms <= 250, 'animazione ' + m[1] + ' di ' + ms + ' ms');
  }
  const meno = blocco('@media (prefers-reduced-motion: reduce)').testo;
  assert.match(meno, /\*, \*::before, \*::after \{ animation: none !important; transition: none !important; \}/);
});

test('trascinando, il gruppo sotto si illumina del suo colore; la riga che esce scivola via', () => {
  assert.match(CSS, /\.voce\.gruppo\.sopra \{[^}]*background: var\(--g-sf\);[^}]*color: var\(--g-tx\)/);
  assert.match(CSS, /\.mail\.uscita \{[^}]*opacity: 0;[^}]*transform: translateX/);
  assert.match(CSS, /\.mail\.entrata \{ animation: entra var\(--durata\)/);
});

test('la striscia della prova è sottile e ambra, la barra della risposta sta fissa in basso', () => {
  assert.match(CSS, /\.striscia-prova \{[^}]*padding: 3px[^}]*color: var\(--prova\);[^}]*background: var\(--prova-tenue\)/);
  assert.match(CSS, /\.lettura \{[^}]*display: flex; flex-direction: column/);
  assert.match(CSS, /\.lettura-scorre \{[^}]*overflow-y: auto/);
  assert.match(CSS, /\.barra-risposta \{ flex: none;/);
  assert.match(CSS, /\.barra-risposta:empty \{ display: none; \}/, 'senza pulsanti, nessuna barra');
});
