/*
 * Sol per il computer: le sole chiamate che l'interfaccia fa.
 *
 * La pagina è servita da https://sol.locale/ e chiama SOLO /locale/* (il programma)
 * e /server/* (girato al server di casa col token, che la pagina non vede mai).
 * Il programma le intercetta dentro WebView2: niente porte aperte sul computer.
 * Qui non si decide niente sui dati: si consegna { ok, stato, dati, rete, memoria, errore }.
 */
(function (radice) {
  'use strict';

  const ATTESA_LOCALE_MS = 10000;
  // Abbinamento e scollegamento passano dal server: il programma smette di aspettarlo dopo 30 secondi,
  // la pagina aspetta un po' di più, così la risposta del programma arriva sempre.
  const ATTESA_ABBINAMENTO_MS = 40000;
  const ATTESA_SERVER_MS = 40000;
  const FRASE_RETE = 'Sol non riesce a raggiungere il server di casa. Controlla la connessione a internet: Sol riprova da solo.';

  function percorsoAmmesso(percorso) {
    return typeof percorso === 'string' && /^\/(locale|server)\//.test(percorso);
  }

  async function chiama(metodo, percorso, corpo) {
    if (!percorsoAmmesso(percorso)) throw new Error('Chiamata fuori dall\'accordo: ' + percorso);
    const controllo = new AbortController();
    // L'invio aspetta la conferma di Manuel nella finestra di Windows: nessun limite di tempo.
    const limite = percorso === '/locale/invia' ? null
      : percorso === '/locale/abbina' || percorso === '/locale/scollega' ? ATTESA_ABBINAMENTO_MS
        : percorso.startsWith('/server/') ? ATTESA_SERVER_MS : ATTESA_LOCALE_MS;
    const timer = limite ? setTimeout(() => controllo.abort(), limite) : null;
    try {
      const opzioni = {
        method: metodo,
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
        signal: controllo.signal,
      };
      if (corpo !== undefined) {
        opzioni.headers['Content-Type'] = 'application/json';
        opzioni.body = JSON.stringify(corpo);
      }
      const risposta = await fetch(percorso, opzioni);
      const testo = await risposta.text();
      let dati = null;
      if (testo) {
        try { dati = JSON.parse(testo); } catch (e) { dati = null; }
      }
      const memoria = risposta.headers.get('X-Sol-Memoria');
      return normalizza(risposta.status, dati, memoria);
    } catch (e) {
      return { ok: false, stato: 0, dati: null, rete: true, memoria: null, errore: FRASE_RETE };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  function normalizza(stato, dati, memoria) {
    const codice = Number(stato) || 0;
    const ok = codice >= 200 && codice < 300;
    let errore = null;
    if (!ok) {
      errore = (dati && typeof dati === 'object' && typeof dati.errore === 'string' && dati.errore) || 'L\'operazione non è riuscita. Riprova tra poco.';
    }
    const rete = codice === 0 || (dati && dati.codice === 'rete') || false;
    return { ok, stato: codice, dati, rete, memoria: memoria || null, errore };
  }

  radice.SolApi = {
    get: (p) => chiama('GET', p),
    post: (p, c) => chiama('POST', p, c === undefined ? {} : c),
    put: (p, c) => chiama('PUT', p, c),
    patch: (p, c) => chiama('PATCH', p, c),
    elimina: (p) => chiama('DELETE', p),
  };
})(window);
