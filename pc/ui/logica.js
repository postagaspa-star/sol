/*
 * Sol per il computer: la logica pura dell'interfaccia (niente pagina, niente rete).
 * La provano i test in pc/prove/js con Node. Nel browser sta in window.SolLogica.
 */
(function (radice, fabbrica) {
  'use strict';
  const L = fabbrica();
  if (typeof module === 'object' && module.exports) module.exports = L;
  else radice.SolLogica = L;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';

  // --- Filtri della posta -------------------------------------------------------------

  /** I filtri fissi della colonna di sinistra, con la richiesta al server (§6). */
  const FILTRI = {
    tutte: { titolo: 'Tutta la posta', parametri: {} },
    'da-rispondere': { titolo: 'Da rispondere', parametri: { da_rispondere: 'true' } },
    urgenti: { titolo: 'Urgenti', parametri: { urgenza: 'alta' } },
    'da-controllare': { titolo: 'Da controllare', parametri: { smistamento: 'da_controllare' } },
    truffe: { titolo: 'Possibili truffe', parametri: { sospetta: 'true' } },
  };

  /** "?gruppo_id=3&cerca=...&limite=50&prima_di=812" per GET /api/mail. */
  function queryPosta(filtro, cerca, primaDi, limite) {
    const p = new URLSearchParams();
    if (filtro && filtro.tipo === 'gruppo') p.set('gruppo_id', String(filtro.id));
    else if (filtro && FILTRI[filtro.tipo]) {
      for (const [k, v] of Object.entries(FILTRI[filtro.tipo].parametri)) p.set(k, v);
    }
    const testo = (cerca || '').trim();
    if (testo) p.set('cerca', testo);
    p.set('limite', String(limite || 50));
    if (primaDi) p.set('prima_di', String(primaDi));
    return '?' + p.toString();
  }

  /**
   * Il frammento dell'indirizzo (#mail/812, #urgenti, #impostazioni...) come destinazione.
   * Lo usano le notifiche di Windows per aprire la mail giusta.
   */
  function destinazioneDaFrammento(frammento) {
    const f = String(frammento || '').replace(/^#/, '');
    const mail = /^mail\/(\d{1,15})$/.exec(f);
    if (mail) return { vista: 'posta', filtro: null, mailId: Number(mail[1]) };
    if (f === 'posta' || f === '') return { vista: 'posta', filtro: { tipo: 'tutte' }, mailId: null };
    if (FILTRI[f]) return { vista: 'posta', filtro: { tipo: f }, mailId: null };
    if (['riassunti', 'indietro', 'scadenze', 'impostazioni'].includes(f)) return { vista: f, filtro: null, mailId: null };
    return null;
  }

  function stessoFiltro(a, b) {
    if (!a || !b) return false;
    return a.tipo === b.tipo && (a.tipo !== 'gruppo' || Number(a.id) === Number(b.id));
  }

  // --- Scorciatoie --------------------------------------------------------------------

  /**
   * Il tasto premuto nell'elenco come azione di smistamento. null = non è una scorciatoia
   * (e il tasto fa quello che fa di solito). Mai con il fuoco in un campo di testo, tranne Esc.
   *   frecce / J K      scorre (con Maiusc allarga la selezione)
   *   1-9               sposta le selezionate nel gruppo con quel numero
   *   Invio             "va bene così": accetta il gruppo proposto e passa alla successiva
   *   R                 apre la bozza di risposta (o la fa preparare)
   *   L                 segna letta / da leggere
   *   D                 toglie o rimette "da rispondere"
   *   / oppure Ctrl+F   cerca
   *   Ctrl+A            seleziona tutto l'elenco (solo col fuoco sull'elenco: altrove seleziona il testo)
   *   Esc               esce dal campo o toglie la selezione
   *   ?                 mostra le scorciatoie
   * Un tasto tenuto premuto (ripetizione automatica di Windows) scorre soltanto: le azioni partono
   * una volta per pressione. Le azioni che cambiano qualcosa sul server hanno `smista: true`.
   */
  function azioneTasto(tasto, contesto) {
    const azione = azioneTastoBase(tasto, contesto || {});
    if (azione && tasto.ripetuto && azione.tipo !== 'scorri' && azione.tipo !== 'estendi') return null;
    return azione;
  }

  const AZIONI_CHE_CAMBIANO = new Set(['sposta', 'va-bene', 'bozza', 'letta', 'rispondere']);

  function azioneTastoBase(tasto, ctx) {
    const k = tasto.key;
    if (tasto.inCampo) return k === 'Escape' ? { tipo: 'esci-campo' } : null;
    if (tasto.alt || tasto.meta) return null;
    if (tasto.ctrl) {
      if (k === 'a' || k === 'A') return tasto.suElenco ? { tipo: 'seleziona-tutto' } : null;
      if (k === 'f' || k === 'F') return { tipo: 'cerca' };
      return null;
    }
    let azione = null;
    switch (k) {
      case 'ArrowDown': case 'j': case 'J':
        azione = { tipo: tasto.shift ? 'estendi' : 'scorri', passo: 1 }; break;
      case 'ArrowUp': case 'k': case 'K':
        azione = { tipo: tasto.shift ? 'estendi' : 'scorri', passo: -1 }; break;
      case 'Home': azione = { tipo: 'scorri', passo: -Infinity }; break;
      case 'End': azione = { tipo: 'scorri', passo: Infinity }; break;
      case 'Enter': azione = ctx.haSelezione ? { tipo: 'va-bene' } : null; break;
      case 'r': case 'R': azione = ctx.haSelezione ? { tipo: 'bozza' } : null; break;
      case 'l': case 'L': azione = ctx.haSelezione ? { tipo: 'letta' } : null; break;
      case 'd': case 'D': azione = ctx.haSelezione ? { tipo: 'rispondere' } : null; break;
      case '/': azione = { tipo: 'cerca' }; break;
      case 'Escape': azione = { tipo: 'annulla' }; break;
      case '?': azione = { tipo: 'aiuto' }; break;
      default:
        if (/^[1-9]$/.test(k)) {
          const indice = Number(k) - 1;
          if (ctx.haSelezione && indice < (ctx.numeroGruppi || 0)) azione = { tipo: 'sposta', indice };
        }
        break;
    }
    if (azione && AZIONI_CHE_CAMBIANO.has(azione.tipo)) azione.smista = true;
    return azione;
  }

  /**
   * La selezione dopo un clic su una mail. Clic semplice = solo quella; Ctrl = aggiunge o toglie;
   * Maiusc = da quella di partenza (ancora) a questa, nell'ordine dell'elenco.
   */
  function selezioneDopoClic(selezione, ordine, id, modi, ancora) {
    const m = modi || {};
    if (m.shift && ancora != null && ordine.includes(ancora)) {
      const a = ordine.indexOf(ancora);
      const b = ordine.indexOf(id);
      const [da, a2] = a <= b ? [a, b] : [b, a];
      const nuova = m.ctrl ? new Set(selezione) : new Set();
      for (let i = da; i <= a2; i++) nuova.add(ordine[i]);
      return { selezione: nuova, ancora, attiva: id };
    }
    if (m.ctrl) {
      const nuova = new Set(selezione);
      if (nuova.has(id)) nuova.delete(id); else nuova.add(id);
      return { selezione: nuova, ancora: id, attiva: nuova.has(id) ? id : null };
    }
    return { selezione: new Set([id]), ancora: id, attiva: id };
  }

  /** Scorre di un passo (o allarga la selezione con Maiusc) partendo dalla mail attiva. */
  function selezioneDopoTasto(selezione, ordine, attiva, passo, estendi, ancora) {
    if (ordine.length === 0) return { selezione: new Set(), ancora: null, attiva: null };
    let i = attiva != null ? ordine.indexOf(attiva) : -1;
    if (passo === -Infinity) i = 0;
    else if (passo === Infinity) i = ordine.length - 1;
    else if (i < 0) i = passo > 0 ? 0 : ordine.length - 1;
    else i = Math.min(ordine.length - 1, Math.max(0, i + passo));
    const id = ordine[i];
    if (estendi) {
      const base = ancora != null && ordine.includes(ancora) ? ancora : (attiva != null ? attiva : id);
      return selezioneDopoClic(selezione, ordine, id, { shift: true }, base);
    }
    return { selezione: new Set([id]), ancora: id, attiva: id };
  }

  /**
   * Dopo uno spostamento o un "va bene così": la mail da aprire, cioè la prima dopo l'ultima
   * selezionata che resta in elenco (o, alla fine, quella prima).
   */
  function prossimaDopo(ordinePrima, tolte, restanti) {
    const fuori = new Set(tolte);
    const resta = new Set(restanti);
    let ultima = -1;
    ordinePrima.forEach((id, i) => { if (fuori.has(id)) ultima = i; });
    for (let i = ultima + 1; i < ordinePrima.length; i++) if (resta.has(ordinePrima[i])) return ordinePrima[i];
    for (let i = ultima - 1; i >= 0; i--) if (resta.has(ordinePrima[i])) return ordinePrima[i];
    return null;
  }

  /** L'ordine dell'elenco: dalla più recente; a parità di ora, dall'id più alto. */
  function confrontaMail(a, b) {
    return String(b.ricevuta_il || '').localeCompare(String(a.ricevuta_il || '')) || (Number(b.id) - Number(a.id));
  }

  /**
   * La ricarica silenziosa dell'elenco (ogni minuto): `nuove` è la prima pagina fresca dal server.
   * Le mail già caricate si aggiornano, quelle nuove entrano in cima, quelle più vecchie della prima
   * pagina (caricate con "Mostra le mail più vecchie") restano, e restano anche quelle da `tenere`
   * (selezionate o aperta) anche se nel frattempo sono uscite dal filtro: niente sparisce sotto le mani.
   */
  function unisciElenco(vecchie, nuove, altreNuove, altreVecchie, tenere) {
    const fresche = new Set(nuove.map((m) => m.id));
    const daTenere = tenere || new Set();
    const restano = vecchie.filter((m) => !fresche.has(m.id));
    if (!nuove.length) {
      return { mail: restano.filter((m) => daTenere.has(m.id)).sort(confrontaMail), altre: false };
    }
    const confine = nuove[nuove.length - 1];
    const oltre = altreNuove ? restano.filter((m) => confrontaMail(m, confine) > 0) : [];
    const oltreIds = new Set(oltre.map((m) => m.id));
    const tenute = restano.filter((m) => !oltreIds.has(m.id) && daTenere.has(m.id));
    const mail = nuove.concat(tenute, oltre).sort(confrontaMail);
    return { mail, altre: oltre.length ? !!altreVecchie : !!altreNuove };
  }

  /** Le mail che si trascinano: tutta la selezione se la mail presa ne fa parte, altrimenti solo lei. */
  function idsTrascinati(idPreso, selezione) {
    return selezione && selezione.has(idPreso) ? Array.from(selezione) : [idPreso];
  }

  /** I gruppi nell'ordine del server; i tasti 1-9 seguono questo ordine. */
  function gruppiOrdinati(gruppi) {
    return (gruppi || []).slice().sort((a, b) => (a.ordine ?? 0) - (b.ordine ?? 0) || String(a.nome).localeCompare(String(b.nome), 'it'));
  }

  // --- Testi ---------------------------------------------------------------------------

  /** Il testo di un riassunto diviso in pezzi: testo normale e riferimenti [#812] alle mail. */
  function dividiRiferimenti(testo) {
    const pezzi = [];
    const re = /\[#(\d{1,15})\]/g;
    let ultimo = 0;
    let m;
    const t = String(testo || '');
    while ((m = re.exec(t)) !== null) {
      if (m.index > ultimo) pezzi.push({ testo: t.slice(ultimo, m.index) });
      pezzi.push({ mailId: Number(m[1]) });
      ultimo = re.lastIndex;
    }
    if (ultimo < t.length) pezzi.push({ testo: t.slice(ultimo) });
    return pezzi;
  }

  /** "rossi@esempio.it, Bianchi <bianchi@esempio.it>; neri@esempio.it" → indirizzi puliti. */
  function elencoIndirizzi(testo) {
    return String(testo || '')
      .split(/[,;\n]/)
      .map((x) => x.trim())
      .filter(Boolean)
      .map((x) => {
        const m = /<([^>]+)>/.exec(x);
        return (m ? m[1] : x).trim();
      })
      .filter(Boolean);
  }

  function indirizzoValido(x) {
    return /^[^\s@<>(),;:"]+@[^\s@<>(),;:"]+\.[^\s@<>(),;:"]+$/.test(String(x || ''));
  }

  /** Gli indirizzi che non sembrano indirizzi email (per avvisare prima di salvare). */
  function indirizziStrani(testo) {
    return elencoIndirizzi(testo).filter((x) => !indirizzoValido(x));
  }

  const GIORNI = ['dom', 'lun', 'mar', 'mer', 'gio', 'ven', 'sab'];
  const MESI = ['gen', 'feb', 'mar', 'apr', 'mag', 'giu', 'lug', 'ago', 'set', 'ott', 'nov', 'dic'];
  const MESI_LUNGHI = ['gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre'];
  const GIORNI_LUNGHI = ['domenica', 'lunedì', 'martedì', 'mercoledì', 'giovedì', 'venerdì', 'sabato'];

  function due(n) { return String(n).padStart(2, '0'); }

  function stessoGiorno(a, b) {
    return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  }

  /** L'ora in elenco: "09:30" oggi, "ieri", "lun 6" nella settimana, "6 ott" quest'anno, "6/10/25" prima. */
  function oraBreve(iso, adesso) {
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const ora = adesso ? new Date(adesso) : new Date();
    if (stessoGiorno(d, ora)) return due(d.getHours()) + ':' + due(d.getMinutes());
    const ieri = new Date(ora); ieri.setDate(ora.getDate() - 1);
    if (stessoGiorno(d, ieri)) return 'ieri';
    const giorni = (ora - d) / 86400000;
    if (giorni > 0 && giorni < 6) return GIORNI[d.getDay()] + ' ' + d.getDate();
    if (d.getFullYear() === ora.getFullYear()) return d.getDate() + ' ' + MESI[d.getMonth()];
    return d.getDate() + '/' + (d.getMonth() + 1) + '/' + String(d.getFullYear()).slice(2);
  }

  /** "venerdì 10 ottobre 2026, 09:30" */
  function dataLunga(iso) {
    const d = new Date(iso);
    if (isNaN(d)) return '';
    return GIORNI_LUNGHI[d.getDay()] + ' ' + d.getDate() + ' ' + MESI_LUNGHI[d.getMonth()] + ' ' + d.getFullYear() + ', ' + due(d.getHours()) + ':' + due(d.getMinutes());
  }

  /** Una data senza ora (2026-10-16) come "giovedì 16 ottobre", con "oggi"/"domani" quando serve. */
  function giornoScadenza(quando, adesso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(quando || ''));
    if (!m) return String(quando || '');
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    const oggi = adesso ? new Date(adesso) : new Date();
    oggi.setHours(0, 0, 0, 0);
    const diff = Math.round((d - oggi) / 86400000);
    const base = GIORNI_LUNGHI[d.getDay()] + ' ' + d.getDate() + ' ' + MESI_LUNGHI[d.getMonth()] + (d.getFullYear() !== oggi.getFullYear() ? ' ' + d.getFullYear() : '');
    if (diff === 0) return 'oggi, ' + base;
    if (diff === 1) return 'domani, ' + base;
    if (diff < 0) return base + ' (passata)';
    return base;
  }

  function giorni(n) {
    const v = Number(n) || 0;
    return v === 1 ? '1 giorno' : v + ' giorni';
  }

  function quante(n, una, tante) {
    return n === 1 ? '1 ' + una : n + ' ' + tante;
  }

  /** Com'è andata un'azione su più mail, in una frase sola e senza contraddizioni. null = tutto bene. */
  function esitoMultiplo(riuscite, fallite, verbo, motivo) {
    const tot = riuscite + fallite;
    if (fallite === 0) return null;
    const perche = motivo ? ': ' + motivo : '.';
    if (riuscite === 0) return (tot === 1 ? 'Non è riuscito' : 'Non è riuscito per nessuna delle ' + tot + ' mail') + perche;
    return riuscite + ' ' + verbo + ', ' + fallite + ' no' + perche;
  }

  /**
   * Un indirizzo http (senza s) va bene solo dentro la rete di casa (§15.13): 192.168.x.x, 10.x.x.x,
   * 172.16-31.x.x. Su http non si mandano mai password.
   */
  function indirizzoDiCasa(host) {
    const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(String(host || ''));
    if (!m) return false;
    const n = m.slice(1).map(Number);
    if (n.some((x) => x > 255)) return false;
    return n[0] === 10 || (n[0] === 192 && n[1] === 168) || (n[0] === 172 && n[1] >= 16 && n[1] <= 31);
  }

  function senzaProtezione(server) {
    return /^http:\/\//i.test(String(server || ''));
  }

  return {
    FILTRI,
    queryPosta,
    destinazioneDaFrammento,
    stessoFiltro,
    azioneTasto,
    selezioneDopoClic,
    selezioneDopoTasto,
    prossimaDopo,
    idsTrascinati,
    confrontaMail,
    unisciElenco,
    esitoMultiplo,
    indirizzoDiCasa,
    senzaProtezione,
    gruppiOrdinati,
    dividiRiferimenti,
    elencoIndirizzi,
    indirizzoValido,
    indirizziStrani,
    oraBreve,
    dataLunga,
    giornoScadenza,
    giorni,
    quante,
  };
});
