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
    importanti: { titolo: 'Importanti', parametri: {} },
    tutte: { titolo: 'Tutte', parametri: {} },
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
    if (f === 'posta') return { vista: 'posta', filtro: { tipo: 'importanti' }, mailId: null };
    // Le notifiche di prima (urgenti, truffe) aprono "Tutte" col loro filtro; le bozze pronte aprono Oggi.
    if (f === 'urgenti' || f === 'truffe' || f === 'tutte') return { vista: 'posta', filtro: { tipo: f }, mailId: null };
    if (f === 'da-rispondere' || f === 'oggi' || f === '') return { vista: 'oggi', filtro: null, mailId: null };
    if (f === 'da-controllare' || f === 'smista') return { vista: 'smista', filtro: null, mailId: null };
    if (f === 'indietro' || f === 'aspetti') return { vista: 'aspetti', filtro: null, mailId: null };
    if (['riassunti', 'scadenze', 'impostazioni', 'notifiche'].includes(f)) return { vista: f, filtro: null, mailId: null };
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

  // --- Sol 2.0 (docs/aspetto.md, contratto §17-18) -----------------------------------------------

  /** I 12 colori dei gruppi (contratto §17): il server manda il NOME, la pagina lo traduce nelle tinte del tema. */
  const COLORI_GRUPPI = ['blu', 'verde', 'ambra', 'viola', 'grigio', 'ardesia', 'petrolio', 'rosa', 'indaco', 'oliva', 'marrone', 'ciano'];

  /** Il colore di un gruppo: il nome mandato dal server se è uno dei 12, altrimenti `ardesia`. */
  function coloreGruppo(nome) {
    const n = String(nome || '').trim().toLowerCase();
    return COLORI_GRUPPI.includes(n) ? n : 'ardesia';
  }

  const PAROLINE = new Set(['di', 'da', 'del', 'della', 'dei', 'delle', 'e', 'ed', 'la', 'il', 'lo', 'le', 'per', 's.r.l.', 'srl', 'spa', 's.p.a.']);

  /** Le iniziali per il cerchio: "Mario Rossi" → MR, "Comune di Bassano" → CB, "rossi@esempio.it" → RO. */
  function iniziali(nome, indirizzo) {
    let t = String(nome || '').trim();
    let separatori = /\s+/;
    if (!t && indirizzo) {
      t = String(indirizzo).split('@')[0];
      separatori = /[\s._-]+/;
    }
    // Le sigle della ragione sociale (S.r.l., S.p.A., S.n.c., S.a.s.) non sono un nome.
    t = t.replace(/[<>"'()\[\]]/g, ' ').replace(/[\s,]+(s\.?\s?r\.?\s?l\.?|s\.?\s?p\.?\s?a\.?|s\.?\s?n\.?\s?c\.?|s\.?\s?a\.?\s?s\.?)\s*$/i, '');
    const parole = t.split(separatori).filter((x) => x && !PAROLINE.has(x.toLowerCase()) && /[\p{L}\p{N}]/u.test(x));
    if (!parole.length) return '?';
    const prima = (x) => Array.from(x.replace(/[^\p{L}\p{N}]/gu, ''))[0] || '';
    if (parole.length === 1) return Array.from(parole[0].replace(/[^\p{L}\p{N}]/gu, '')).slice(0, 2).join('').toUpperCase() || '?';
    return (prima(parole[0]) + prima(parole[parole.length - 1])).toUpperCase();
  }

  /** La lettera nel cerchio delle impostazioni: l'iniziale del nome (vuota se il nome non c'è). */
  function inizialeNome(nome) {
    const p = Array.from(String(nome || '').trim().replace(/[^\p{L}\p{N}]/gu, ''));
    return p.length ? p[0].toUpperCase() : '';
  }

  /** "Mario Rossi" → "Rossi"; "Ferramenta Neri" → "Neri"; un indirizzo resta com'è. */
  function cognome(nome) {
    const p = String(nome || '').trim().split(/\s+/).filter(Boolean);
    return p.length ? p[p.length - 1] : '';
  }

  /**
   * Al massimo UN segno per riga (docs/aspetto.md, §4): "Possibile truffa" oppure "Risposta pronta" (non se la
   * risposta è già stata data, §15.13) oppure niente. L'urgenza si vede dal colore dell'ora, non da un segno.
   */
  function segnoRiga(m) {
    if (m.sospetta) return 'truffa';
    if (!m.risposta_data && m.bozza && m.bozza.stato === 'pronta') return 'pronta';
    return null;
  }

  /** Il saluto della pagina Oggi secondo l'ora. */
  function saluto(adesso) {
    const ora = (adesso ? new Date(adesso) : new Date()).getHours();
    if (ora >= 5 && ora < 13) return 'Buongiorno';
    if (ora >= 13 && ora < 18) return 'Buon pomeriggio';
    return 'Buonasera';
  }

  function maiuscola(t) {
    const s = String(t || '');
    return s ? s[0].toUpperCase() + s.slice(1) : s;
  }

  function minuscolaIniziale(t) {
    const s = String(t || '');
    // "Offerta a Rossi" → "offerta a Rossi"; le sigle (PEC, IVA) restano come sono.
    return s.length > 1 && s[1] === s[1].toLowerCase() ? s[0].toLowerCase() + s.slice(1) : s;
  }

  function dataDaGiorno(quando) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(quando || ''));
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
  }

  /** "Sabato 11 ottobre · 14 mail nuove" sotto il saluto. */
  function rigaOggi(data, mailOggi, adesso) {
    const d = dataDaGiorno(data) || (adesso ? new Date(adesso) : new Date());
    const giorno = maiuscola(GIORNI_LUNGHI[d.getDay()]) + ' ' + d.getDate() + ' ' + MESI_LUNGHI[d.getMonth()];
    if (mailOggi == null) return giorno;
    const n = Number(mailOggi) || 0;
    const mail = n === 0 ? 'nessuna mail nuova' : n === 1 ? '1 mail nuova' : n + ' mail nuove';
    return giorno + ' · ' + mail;
  }

  /** Un giorno vicino detto breve: "Oggi", "Domani", "Venerdì" (entro la settimana), poi "16 ottobre". */
  function giornoBreve(quando, adesso) {
    const d = dataDaGiorno(quando);
    if (!d) return String(quando || '');
    const oggi = adesso ? new Date(adesso) : new Date();
    oggi.setHours(0, 0, 0, 0);
    const diff = Math.round((d - oggi) / 86400000);
    if (diff === 0) return 'Oggi';
    if (diff === 1) return 'Domani';
    if (diff > 1 && diff < 7) return maiuscola(GIORNI_LUNGHI[d.getDay()]);
    if (diff < 0) return d.getDate() + ' ' + MESI_LUNGHI[d.getMonth()] + ' (passata)';
    return d.getDate() + ' ' + MESI_LUNGHI[d.getMonth()] + (d.getFullYear() !== oggi.getFullYear() ? ' ' + d.getFullYear() : '');
  }

  /** Il titolo di un giorno nell'elenco delle scadenze: "Oggi", "Domani", "Venerdì 16", "Lunedì 3 novembre". */
  function etichettaGiorno(quando, adesso) {
    const d = dataDaGiorno(quando);
    if (!d) return String(quando || '');
    const oggi = adesso ? new Date(adesso) : new Date();
    oggi.setHours(0, 0, 0, 0);
    const diff = Math.round((d - oggi) / 86400000);
    if (diff < 0) return 'Già passate';
    if (diff === 0) return 'Oggi';
    if (diff === 1) return 'Domani';
    const base = maiuscola(GIORNI_LUNGHI[d.getDay()]) + ' ' + d.getDate();
    if (diff < 7) return base;
    return base + ' ' + MESI_LUNGHI[d.getMonth()] + (d.getFullYear() !== oggi.getFullYear() ? ' ' + d.getFullYear() : '');
  }

  /** La pillola della scadenza più vicina nel punto di oggi: "Venerdì: offerta a Rossi". */
  function pillolaScadenza(s, adesso) {
    if (!s) return '';
    return giornoBreve(s.quando, adesso) + ': ' + minuscolaIniziale(s.descrizione || '');
  }

  /**
   * Una voce di "Da fare" (GET /api/oggi, §18.1) con le parole per lo schermo: il server manda solo i dati.
   * pulsante.stile: 'pieno' (azione principale), 'tenue' (accento tenue), 'truffa' (bianco col bordo rosso).
   */
  function voceOggi(v, adesso) {
    const chi = (x) => x.mittente || x.nome || x.a || '';
    switch (v && v.tipo) {
      case 'risposta':
        return { titolo: 'Rispondi a ' + chi(v), sotto: (v.oggetto || '(senza oggetto)') + ' · risposta pronta', pulsante: { etichetta: 'Rivedi', stile: 'pieno' }, cerchio: 'iniziali' };
      case 'truffa':
        return { titolo: 'Possibile truffa', sotto: v.riassunto || v.oggetto || chi(v), pulsante: { etichetta: 'Guarda', stile: 'truffa' }, cerchio: 'truffa' };
      case 'smista': {
        const n = Number(v.conteggio) || 0;
        return { titolo: n === 1 ? 'Smista 1 mail' : 'Smista ' + n + ' mail', sotto: 'Sol non è sicuro del gruppo', pulsante: { etichetta: 'Inizia', stile: 'tenue' }, cerchio: 'smista' };
      }
      case 'rispondi':
        return { titolo: 'Rispondi a ' + chi(v), sotto: v.oggetto || '(senza oggetto)', pulsante: { etichetta: 'Rispondi', stile: 'tenue' }, cerchio: 'iniziali' };
      case 'sollecito': {
        const g = Number(v.giorni) || 0;
        const nome = v.nome ? cognome(v.nome) : (v.a || '');
        return {
          titolo: nome + ' non risponde da ' + (g === 1 ? '1 giorno' : g + ' giorni'),
          sotto: (v.oggetto || '(senza oggetto)') + (v.bozza_id ? ' · sollecito pronto' : ''),
          pulsante: { etichetta: v.bozza_id ? 'Rivedi' : 'Prepara', stile: 'tenue' },
          cerchio: 'iniziali',
        };
      }
      case 'scadenza':
        return { titolo: v.descrizione || 'Scadenza', sotto: giornoBreve(v.quando, adesso), pulsante: { etichetta: 'Fatto', stile: 'tenue' }, cerchio: 'scadenza' };
      default:
        return null;
    }
  }

  /** "2 di 6 fatte" e la larghezza della barra (0-100). */
  function avanzamentoOggi(fatte, totale) {
    const f = Math.max(0, Number(fatte) || 0);
    const t = Math.max(f, Number(totale) || 0);
    return { testo: f + ' di ' + t + ' fatte', percento: t ? Math.round((f / t) * 100) : 100 };
  }

  /**
   * La pagina Oggi con un server di prima della v1.4 (niente GET /api/oggi): la stessa forma, messa insieme
   * da quello che c'è già (riassunto di oggi, mail sospette, da rispondere, da smistare, scadenze, aspetti tu).
   */
  function oggiDaRisorse(r, adesso) {
    const ora = adesso ? new Date(adesso) : new Date();
    const giorno = ora.getFullYear() + '-' + due(ora.getMonth() + 1) + '-' + due(ora.getDate());
    const voce = (m, tipo) => ({
      tipo, mail_id: m.id, bozza_id: m.bozza && m.bozza.id, mittente: (m.mittente && (m.mittente.nome || m.mittente.indirizzo)) || '',
      oggetto: m.oggetto || '', riassunto: m.riassunto || '', urgenza: m.urgenza, colore: m.colore || null,
      iniziali: iniziali(m.mittente && m.mittente.nome, m.mittente && m.mittente.indirizzo), gruppo_id: m.gruppo_id,
    });
    const sospette = (r.sospette || []).filter((m) => m.sospetta);
    const daRispondere = (r.daRispondere || []).filter((m) => !m.sospetta && !m.risposta_data);
    const pronte = daRispondere.filter((m) => m.bozza && m.bozza.stato === 'pronta');
    const urgentiPronte = pronte.filter((m) => m.urgenza === 'alta');
    const altrePronte = pronte.filter((m) => m.urgenza !== 'alta');
    const senzaBozza = daRispondere.filter((m) => !(m.bozza && m.bozza.stato === 'pronta'));
    const smista = (r.daSmistare || []).filter((m) => !m.sospetta).length;
    const fine = new Date(ora); fine.setHours(0, 0, 0, 0); fine.setDate(fine.getDate() + 7);
    const scadenze = (r.scadenze || []).filter((s) => s.stato === 'aperta' || !s.stato).sort((a, b) => String(a.quando).localeCompare(String(b.quando)));
    const vicine = scadenze.filter((s) => { const d = dataDaGiorno(s.quando); return d && d <= fine; });
    const da = [];
    sospette.forEach((m) => da.push(voce(m, 'truffa')));
    urgentiPronte.forEach((m) => da.push(voce(m, 'risposta')));
    if (smista > 0) da.push({ tipo: 'smista', conteggio: smista });
    altrePronte.forEach((m) => da.push(voce(m, 'risposta')));
    senzaBozza.forEach((m) => da.push(voce(m, 'rispondi')));
    ((r.indietro && r.indietro.aspetti_tu) || []).forEach((x) => {
      const inv = x.inviata || {};
      da.push({ tipo: 'sollecito', inviata_id: inv.id, bozza_id: x.bozza_sollecito && x.bozza_sollecito.id, a: (inv.a || [])[0] || '', nome: '', oggetto: inv.oggetto || '', giorni: x.giorni, iniziali: iniziali('', (inv.a || [])[0] || '') });
    });
    vicine.forEach((s) => da.push({ tipo: 'scadenza', scadenza_id: s.id, descrizione: s.descrizione, quando: s.quando, mail_id: s.mail_id }));
    const daFare = da.slice(0, 8);
    const prossima = scadenze.find((s) => { const d = dataDaGiorno(s.quando); const o = new Date(ora); o.setHours(0, 0, 0, 0); return d && d >= o; }) || null;
    const conta = daFare.reduce((n, v) => n + (v.tipo === 'smista' ? v.conteggio : 1), 0);
    return {
      data: giorno,
      nome: r.nome || '',
      mail_oggi: Number(r.mailOggi) || 0,
      riassunto: r.riassunto || null,
      scadenza_prossima: prossima ? { id: prossima.id, descrizione: prossima.descrizione, quando: prossima.quando, mail_id: prossima.mail_id } : null,
      da_fare: daFare,
      fatte_oggi: 0,
      totale_oggi: conta,
      ricostruita: true,
    };
  }

  /**
   * Posta "Importanti" (§4): le mail dei gruppi che non sono raccolte, divise per gruppo nell'ordine dei
   * gruppi, dalla più recente. Le sezioni senza mail non compaiono.
   */
  function sezioniImportanti(mail, gruppi) {
    const ordinati = gruppiOrdinati(gruppi).filter((g) => !g.raccolta);
    const perGruppo = new Map(ordinati.map((g) => [g.id, []]));
    for (const m of mail || []) if (perGruppo.has(m.gruppo_id)) perGruppo.get(m.gruppo_id).push(m);
    return ordinati
      .map((g) => ({ gruppo: g, mail: perGruppo.get(g.id).slice().sort(confrontaMail) }))
      .filter((s) => s.mail.length > 0);
  }

  /** La riga di una raccolta (§18.2), come il prototipo: "Newsletter" e sotto "6 mail · Webinar sulla nuova norma"
   *  (l'oggetto dell'ultima mail); `daLeggere` serve solo a mettere il nome in grassetto. */
  function rigaRaccolta(gruppo, conteggio) {
    const c = conteggio || {};
    const totale = Number(c.totale) || 0;
    const quante = totale === 1 ? '1 mail' : totale + ' mail';
    const ultima = c.ultima && (c.ultima.oggetto || c.ultima.mittente);
    return { titolo: gruppo.nome, sotto: ultima ? quante + ' · ' + ultima : quante, daLeggere: Number(c.da_leggere) || 0 };
  }

  /** Il numero sul pallino della Posta: le mail da leggere dei gruppi che non sono raccolte. */
  function daLeggereImportanti(gruppi, conteggi) {
    const c = (conteggi && conteggi.gruppi) || {};
    return (gruppi || []).filter((g) => !g.raccolta).reduce((n, g) => n + (Number(c[String(g.id)] && c[String(g.id)].da_leggere) || 0), 0);
  }

  /** Smista (§6): quanto è sicuro Sol del gruppo che propone. */
  function paroleSicurezza(s) {
    if (s == null || isNaN(Number(s))) return '';
    return Number(s) >= 0.7 ? 'abbastanza sicuro' : 'poco sicuro';
  }

  /** Le pillole di tono sotto la risposta di Sol (§5): ognuna chiede a Sol di rifarla con questa istruzione. */
  const TONI = [
    { chiave: 'breve', etichetta: 'Più breve', istruzioni: 'Rendi la risposta più breve, tenendo solo le cose importanti.' },
    { chiave: 'formale', etichetta: 'Più formale', istruzioni: 'Rendi la risposta più formale.' },
    { chiave: 'tempo', etichetta: 'Chiedi più tempo', istruzioni: 'Rispondi chiedendo gentilmente qualche giorno in più per rispondere nel merito.' },
  ];

  /**
   * Smista coi tasti: freccia a destra o Invio = Va bene, freccia a sinistra = Cambia (si aprono i gruppi),
   * 1-9 = quel gruppo, Esc = chiude i gruppi (o la vista). Un tasto tenuto premuto non ripete niente.
   */
  function azioneTastoSmista(tasto, ctx) {
    const k = tasto.key;
    if (tasto.inCampo || tasto.ctrl || tasto.alt || tasto.meta || tasto.ripetuto) return null;
    if (k === 'ArrowRight' || k === 'Enter') return { tipo: 'va-bene' };
    if (k === 'ArrowLeft') return { tipo: 'cambia' };
    if (k === 'Escape') return { tipo: ctx && ctx.gruppiAperti ? 'chiudi-gruppi' : 'chiudi' };
    if (/^[1-9]$/.test(k)) {
      const indice = Number(k) - 1;
      return indice < ((ctx && ctx.numeroGruppi) || 0) ? { tipo: 'gruppo', indice } : null;
    }
    return null;
  }

  /**
   * Il testo del riassunto con i riferimenti [#id] come parole toccabili (§3): il nome del mittente, se c'è
   * già nella frase prima del riferimento, diventa il collegamento; se no il cognome si aggiunge alla fine
   * della frase. Senza nome conosciuto il riferimento resta un collegamento "apri".
   */
  function riferimentiConNomi(testo, nomi) {
    const pezzi = dividiRiferimenti(testo);
    const conosciuti = nomi || {};
    const esito = [];
    for (let i = 0; i < pezzi.length; i++) {
      const p = pezzi[i];
      if (p.mailId == null) { esito.push({ testo: p.testo }); continue; }
      const nome = conosciuti[p.mailId];
      const prima = esito.length && esito[esito.length - 1].mailId == null ? esito[esito.length - 1] : null;
      if (!nome) { esito.push({ mailId: p.mailId }); continue; }
      const candidati = [nome].concat(String(nome).split(/\s+/).filter((x) => x.length >= 3).reverse());
      let fatto = false;
      if (prima) {
        for (const c of candidati) {
          const re = new RegExp('(^|[^\\p{L}])(' + c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')(?![\\p{L}])', 'u');
          const trovato = re.exec(prima.testo);
          if (trovato) {
            const inizio = trovato.index + trovato[1].length;
            const fine = inizio + trovato[2].length;
            const dopo = prima.testo.slice(fine).replace(/\s+$/, '');
            esito.pop();
            if (inizio > 0) esito.push({ testo: prima.testo.slice(0, inizio) });
            esito.push({ testo: trovato[2], mailId: p.mailId });
            if (dopo) esito.push({ testo: dopo });
            fatto = true;
            break;
          }
        }
      }
      if (!fatto) {
        // Il nome non è nella frase: lo si aggiunge tra parentesi, "… un IBAN nuovo (Neri)."
        if (prima) prima.testo = prima.testo.replace(/\s+$/, '');
        esito.push({ testo: ' (' });
        esito.push({ testo: cognome(nome) || nome, mailId: p.mailId });
        esito.push({ testo: ')' });
      }
    }
    // Lo spazio rimasto prima della punteggiatura ("Rossi ." → "Rossi.").
    for (const e of esito) if (e.mailId == null && e.testo) e.testo = e.testo.replace(/^\s+(?=[.,;:!?])/, '');
    return esito;
  }

  /** "Rispondi a tutti": gli altri destinatari della mail (tolta la nostra casella e chi ha scritto). */
  function altriDestinatari(m, nostra) {
    const via = new Set([String(nostra || '').toLowerCase(), String((m.mittente && m.mittente.indirizzo) || '').toLowerCase()]);
    const tutti = [].concat(m.destinatari || [], m.cc || []).map((x) => (typeof x === 'string' ? x : (x && x.indirizzo) || '')).map((x) => elencoIndirizzi(x)[0] || '');
    const visti = new Set();
    return tutti.filter((x) => {
      const k = x.toLowerCase();
      if (!x || via.has(k) || visti.has(k)) return false;
      visti.add(k);
      return true;
    });
  }

  /** Il tema scelto (Impostazioni > App): 'chiaro', 'scuro' oppure automatico (null). */
  function temaPagina(scelta) {
    return scelta === 'chiaro' || scelta === 'scuro' ? scelta : null;
  }

  /** L'ora in Smista e nelle righe: come oraBreve. */
  function oraIntestazione(iso, adesso) {
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const ora = adesso ? new Date(adesso) : new Date();
    const hm = due(d.getHours()) + ':' + due(d.getMinutes());
    if (stessoGiorno(d, ora)) return 'oggi ' + hm;
    const ieri = new Date(ora); ieri.setDate(ora.getDate() - 1);
    if (stessoGiorno(d, ieri)) return 'ieri ' + hm;
    return GIORNI_LUNGHI[d.getDay()] + ' ' + d.getDate() + ' ' + MESI_LUNGHI[d.getMonth()] + (d.getFullYear() !== ora.getFullYear() ? ' ' + d.getFullYear() : '') + ', ' + hm;
  }

  return {
    FILTRI,
    COLORI_GRUPPI,
    TONI,
    coloreGruppo,
    iniziali,
    inizialeNome,
    cognome,
    segnoRiga,
    saluto,
    rigaOggi,
    giornoBreve,
    etichettaGiorno,
    pillolaScadenza,
    voceOggi,
    avanzamentoOggi,
    oggiDaRisorse,
    sezioniImportanti,
    rigaRaccolta,
    daLeggereImportanti,
    paroleSicurezza,
    azioneTastoSmista,
    riferimentiConNomi,
    altriDestinatari,
    temaPagina,
    oraIntestazione,
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
