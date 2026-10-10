using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Sol.Nucleo;
using Sol.Rete;
using Sol.Sistema;

namespace Sol.Motore;

/// <summary>
/// Quello che la finestra di conferma mostra prima di un invio: letto dal server (la versione che parte davvero),
/// non dalla pagina. <see cref="SollecitoPer"/>: per un sollecito, a quale mail inviata si riferisce.
/// </summary>
public sealed record AnteprimaInvio(long BozzaId, IReadOnlyList<string> A, IReadOnlyList<string> Cc, string Oggetto, string? Tipo,
    string PrimaRiga = "", string? SollecitoPer = null, bool GiaRisposto = false, bool Sospetta = false);

/// <summary>La conferma esplicita dell'utente prima di ogni invio. true = "Invia".</summary>
public delegate Task<bool> ChiediConfermaInvio(AnteprimaInvio anteprima);

/// <summary>Una risposta per la pagina. <see cref="DaMemoriaDel"/>: il server non ha risposto e questi sono gli ultimi dati visti.</summary>
public sealed record RispostaPonte(int Stato, string Json, DateTimeOffset? DaMemoriaDel = null);

/// <summary>
/// Il cuore del programma, senza finestre: tiene il collegamento col server, gira le richieste
/// della pagina (col token, che la pagina non vede mai), conserva gli ultimi dati visti, chiede
/// le novità ogni 2 minuti e controlla le versioni nuove. Niente logica di posta: quella è del server.
/// </summary>
public sealed class Motore : IDisposable
{
    public static readonly TimeSpan IntervalloNovita = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan IntervalloVersione = TimeSpan.FromHours(12);
    public static readonly TimeSpan TempoAbbinamento = TimeSpan.FromSeconds(30);

    public const string FraseCambiata = "La risposta è cambiata dopo che l'hai controllata: ricontrollala prima di inviarla.";
    public const string FraseInvioIncerto = "Non è sicuro che la mail sia partita: guarda in Posta inviata prima di riprovare. Se riprovi, Sol controlla da solo che non parta due volte.";
    public const string FrasePasswordSenzaHttps = "Questo computer è collegato al server con un indirizzo di casa non protetto (http): da lì Sol non manda password. "
        + "Collega il computer con l'indirizzo https di Tailscale (Impostazioni > Questo computer > Scollega, poi di nuovo con l'indirizzo https) e riprova.";

    // L'unico punto del server che spedisce: la pagina non lo raggiunge mai direttamente, passa da InviaBozzaAsync.
    private static readonly Regex PercorsoInvio = new(@"^api/bozze/[^/]+/invia/?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    // Aggiunta e ricollegamento delle caselle: gli unici punti dove passa una password (§4, §15.3).
    private static readonly Regex PercorsoConPassword = new(@"^api/caselle(/[^/]+/ricollega)?/?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex PercorsoAmmesso = new(@"^api/[A-Za-z0-9_\-/]*$", RegexOptions.CultureInvariant);

    private readonly Percorsi percorsi;
    private readonly ClientSol client;
    private readonly bool clientNostro;
    private readonly Func<DateTimeOffset> orologio;
    private readonly Memoria memoria;
    private readonly object stato = new();
    private readonly HashSet<long> inviiInCorso = new();
    // Al massimo UN giro in attesa: F5 o "Aggiorna" ripetuti non mettono in fila decine di giri.
    private readonly SemaphoreSlim sveglia = new(0, 1);
    private readonly SemaphoreSlim giroNovita = new(1, 1);
    private readonly SorveglianzaCollegamento sorveglianza = new();
    private CancellationTokenSource? fine;
    private Task? ciclo;
    private Configurazione config;
    private string? token;
    private StatoNotifiche notifiche;
    private bool problemaMostrato;
    private int scaricamentoInCorso;
    private int svegliaChiesta;
    private EsitoScaricamento? ultimoScaricamento;

    public Motore(Percorsi percorsi, ClientSol? client = null, Func<DateTimeOffset>? orologio = null)
    {
        this.percorsi = percorsi;
        clientNostro = client == null;
        this.client = client ?? new ClientSol();
        this.orologio = orologio ?? (() => DateTimeOffset.Now);
        memoria = new Memoria(percorsi.Memoria);
        config = Archivio.LeggiJson<Configurazione>(percorsi.Config) ?? new Configurazione();
        token = Dpapi.Svela(config.TokenProtetto);
        if (token == null && config.Server != null)
        {
            // Il token non si decifra (copiato da un altro utente o computer): si ricomincia dall'abbinamento.
            config = new Configurazione();
        }
        notifiche = Archivio.LeggiJson<StatoNotifiche>(percorsi.Notifiche) ?? new StatoNotifiche();
        Aggiornatore = new Aggiornatore(this.client, Versione.Codice);
    }

    /// <summary>La conferma prima dell'invio (la finestra di Windows). Senza, nessuna mail parte.</summary>
    public ChiediConfermaInvio? ChiediConferma { get; set; }

    /// <summary>Scrive o toglie l'avvio all'accesso a Windows. Null nelle build di sviluppo e nei test.</summary>
    public Action<bool>? ApplicaAvvioLogin { get; set; }

    /// <summary>Il controllo delle versioni nuove vale solo per il programma installato dallo zip, non per le build di sviluppo.</summary>
    public bool AggiornamentiAttivi { get; set; }

    /// <summary>Dove salvare lo zip della versione nuova (la cartella Download dell'utente; nei test una temporanea).</summary>
    public string CartellaDownload { get; set; } = Cartelle.Download();

    public Aggiornatore Aggiornatore { get; }

    /// <summary>Le notifiche già mostrate e cosa apre il clic (vedi <see cref="ClicNotifiche"/>).</summary>
    public ClicNotifiche Notifiche { get; } = new();

    /// <summary>Quante volte è partito un giro delle novità (per i test: i giri non si accumulano).</summary>
    public int GiriFatti { get; private set; }

    /// <summary>La notifica da mostrare a schermo: una per giro (le singole sono già in <see cref="Notifiche"/>).</summary>
    public event Action<Notifica>? NuovaNotifica;

    /// <summary>Quante mail urgenti (null = non si sa ancora).</summary>
    public event Action<int?>? UrgentiCambiati;

    public event Action? StatoCambiato;
    public event Action<VersioneNuova>? AggiornamentoDisponibile;

    /// <summary>Lo scaricamento della versione nuova è finito (bene o male).</summary>
    public event Action<EsitoScaricamento>? ScaricamentoFinito;

    public bool Abbinato
    {
        get { lock (stato) return config.Server != null && token != null; }
    }

    /// <summary>Il server non riconosce più questo computer (401, oppure <c>autenticato: false</c> da /api/salute).</summary>
    public bool NonRiconosciuto { get; private set; }

    /// <summary>null finché non si è provato; poi l'esito dell'ultima richiesta.</summary>
    public bool? ServerRaggiungibile { get; private set; }

    public DateTimeOffset? UltimoContatto { get; private set; }

    public int? UrgentiDaLeggere { get; private set; }

    /// <summary>C'è un problema di collegamento da far vedere anche nell'icona (§ revisione 7).</summary>
    public bool ProblemaCollegamento => Abbinato && sorveglianza.Problema(NonRiconosciuto, orologio());

    private (string? Server, string? Token) Credenziali()
    {
        lock (stato) return (config.Server, token);
    }

    // ---------------- Ciclo in sottofondo ----------------

    public void Avvia()
    {
        if (ciclo != null) return;
        fine = new CancellationTokenSource();
        ciclo = Task.Run(() => CicloAsync(fine.Token));
    }

    /// <summary>Un giro subito (menu "Controlla adesso", F5, dopo l'abbinamento). Più richieste insieme valgono una.</summary>
    public void Sveglia()
    {
        // Finché il giro richiesto non è partito, le altre richieste non aggiungono niente.
        if (Interlocked.Exchange(ref svegliaChiesta, 1) == 1) return;
        try
        {
            sveglia.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    private async Task CicloAsync(CancellationToken annulla)
    {
        long prossimaVersione = Environment.TickCount64 + 20_000;
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(2), annulla).ConfigureAwait(false);
            while (!annulla.IsCancellationRequested)
            {
                try
                {
                    await GiroNovitaAsync(annulla).ConfigureAwait(false);
                    ControllaCollegamento();
                    if (AggiornamentiAttivi && Environment.TickCount64 >= prossimaVersione && Credenziali().Server is string server)
                    {
                        prossimaVersione = Environment.TickCount64 + (long)IntervalloVersione.TotalMilliseconds;
                        var nuova = await Aggiornatore.ControllaAsync(server, annulla).ConfigureAwait(false);
                        if (nuova != null) AggiornamentoDisponibile?.Invoke(nuova);
                    }
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    Log.Errore("giro in sottofondo", e);
                }
                await sveglia.WaitAsync(IntervalloNovita, annulla).ConfigureAwait(false);
                Interlocked.Exchange(ref svegliaChiesta, 0);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// <c>GET /api/novita</c> → notifiche (una a schermo per giro), poi il numero di mail urgenti per l'icona
    /// (<c>GET /api/conteggi</c>, §15.4). Il primo giro (senza cursore) prende solo il cursore.
    /// </summary>
    public async Task GiroNovitaAsync(CancellationToken annulla = default)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return;
        if (!await giroNovita.WaitAsync(0, annulla).ConfigureAwait(false)) return;
        try
        {
            GiriFatti++;
            string? cursore;
            lock (stato) cursore = notifiche.Cursore;
            var percorso = "api/novita" + (cursore != null ? "?dal=" + Uri.EscapeDataString(cursore) : "");
            var r = await client.InviaAsync("GET", server, percorso, tok, null, annulla: annulla).ConfigureAwait(false);
            Annota(r, percorso);
            if (r.Ok && Json.Leggi<Novita>(r.Corpo) is Novita novita)
            {
                IReadOnlyList<Notifica> daMostrare;
                lock (stato)
                {
                    daMostrare = Notificatore.DaMostrare(novita, notifiche);
                    SalvaNotifiche();
                }
                Mostra(daMostrare);
            }
            if (r.Rete || r.Stato == 401) return;
            await AggiornaUrgentiAsync(server, tok, annulla).ConfigureAwait(false);
        }
        finally
        {
            giroNovita.Release();
        }
    }

    private async Task AggiornaUrgentiAsync(string server, string tok, CancellationToken annulla)
    {
        int? n = null;
        var c = await client.InviaAsync("GET", server, "api/conteggi", tok, null, annulla: annulla).ConfigureAwait(false);
        Annota(c, "api/conteggi");
        if (c.Ok && Json.Nodo(c.Corpo) is JsonObject conteggi && Json.Intero(conteggi["urgenti"]) is long urgenti)
        {
            n = (int)Math.Min(urgenti, int.MaxValue);
        }
        else if (c.Stato is 404 or 405)
        {
            // Server di prima della v1.1, senza /api/conteggi: si contano le urgenti non lette di una pagina.
            var u = await client.InviaAsync("GET", server, "api/mail?urgenza=alta&letta=false&limite=50", tok, null, annulla: annulla).ConfigureAwait(false);
            Annota(u, "api/mail");
            if (u.Ok && Json.Leggi<ElencoMail>(u.Corpo) is ElencoMail elenco) n = elenco.Mail.Count(m => !m.Letta);
        }
        if (n != null && n != UrgentiDaLeggere)
        {
            UrgentiDaLeggere = n;
            UrgentiCambiati?.Invoke(n);
        }
    }

    /// <summary>Le notifiche del giro: tutte nell'elenco delle recenti, UNA sola a schermo (§ revisione 16).</summary>
    private void Mostra(IReadOnlyList<Notifica> giro)
    {
        if (giro.Count == 0) return;
        Notifiche.Registra(giro, orologio());
        if (Notificatore.Raggruppa(giro) is Notifica aSchermo) NuovaNotifica?.Invoke(aSchermo);
    }

    /// <summary>Server che non riconosce più il computer o irraggiungibile da mezz'ora: una notifica e l'icona diversa.</summary>
    public void ControllaCollegamento()
    {
        if (!Abbinato) return;
        var adesso = orologio();
        if (sorveglianza.Controlla(NonRiconosciuto, adesso) is Notifica n) Mostra(new[] { n });
        var problema = sorveglianza.Problema(NonRiconosciuto, adesso);
        if (problema != problemaMostrato)
        {
            problemaMostrato = problema;
            StatoCambiato?.Invoke();
        }
    }

    // ---------------- Stato e abbinamento ----------------

    public JsonObject Stato()
    {
        Configurazione c;
        lock (stato) c = config;
        var nuova = Aggiornatore.Disponibile;
        var scaricato = ultimoScaricamento;
        return new JsonObject
        {
            ["abbinato"] = Abbinato,
            ["server"] = c.Server,
            ["senza_protezione"] = Indirizzo.SenzaProtezione(c.Server),
            ["nome_dispositivo"] = c.NomeDispositivo,
            ["dispositivo_id"] = c.DispositivoId,
            ["non_riconosciuto"] = NonRiconosciuto,
            ["raggiungibile"] = ServerRaggiungibile,
            ["irraggiungibile_dal"] = sorveglianza.IrraggiungibileDal?.ToString("o", CultureInfo.InvariantCulture),
            ["problema_collegamento"] = ProblemaCollegamento,
            ["ultimo_contatto"] = UltimoContatto?.ToString("o", CultureInfo.InvariantCulture),
            ["urgenti_da_leggere"] = UrgentiDaLeggere,
            ["versione"] = Versione.Nome,
            ["avvio_al_login"] = c.AvvioAlLogin,
            ["avvio_disponibile"] = ApplicaAvvioLogin != null,
            ["nome_suggerito"] = NomeSuggerito(),
            ["aggiornamento"] = new JsonObject
            {
                ["attivo"] = AggiornamentiAttivi,
                ["disponibile"] = nuova?.Nome,
                ["annunciata"] = Aggiornatore.Annunciata?.Nome,
                ["scaricamento_in_corso"] = Volatile.Read(ref scaricamentoInCorso) == 1,
                ["scaricato"] = scaricato?.Ok == true ? scaricato.File : null,
                ["errore_scaricamento"] = scaricato?.Ok == false ? scaricato.Errore : null,
            },
        };
    }

    /// <summary>Le notifiche già mostrate, per "Notifiche recenti" nella finestra: ognuna con la sua destinazione.</summary>
    public JsonObject NotificheRecenti() => new()
    {
        ["notifiche"] = new JsonArray(Notifiche.Recenti.Select(n => (JsonNode)new JsonObject
        {
            ["quando"] = n.Quando.ToString("o", CultureInfo.InvariantCulture),
            ["titolo"] = n.Notifica.Titolo,
            ["testo"] = n.Notifica.Testo,
            ["destinazione"] = n.Notifica.Destinazione,
        }).ToArray()),
    };

    public static string NomeSuggerito()
    {
        var macchina = Environment.MachineName;
        return string.IsNullOrWhiteSpace(macchina) ? "Computer dello studio" : "Computer " + macchina;
    }

    /// <summary><c>POST /api/abbina</c> con il codice a 8 cifre; il token si salva cifrato con DPAPI.</summary>
    public async Task<JsonObject> AbbinaAsync(string? indirizzo, string? codice, string? nome, bool avvioAlLogin)
    {
        if (!Indirizzo.Normalizza(indirizzo, out var server, out var erroreIndirizzo)) return Errore(erroreIndirizzo!, "indirizzo");
        var cifre = new string((codice ?? "").Where(char.IsAsciiDigit).ToArray());
        if (cifre.Length != 8) return Errore("Il codice di abbinamento ha 8 cifre: controlla di averle scritte tutte.", "codice");
        var nomeDispositivo = string.IsNullOrWhiteSpace(nome) ? NomeSuggerito() : nome.Trim();
        if (nomeDispositivo.Length > 60) nomeDispositivo = nomeDispositivo[..60];

        var corpo = new JsonObject { ["codice"] = cifre, ["nome"] = nomeDispositivo, ["tipo"] = "pc" }.ToJsonString();
        // La pagina aspetta 40 secondi: il motore smette prima (30), così la risposta arriva sempre alla pagina.
        var r = await client.InviaAsync("POST", server, "api/abbina", null, corpo, TempoAbbinamento).ConfigureAwait(false);
        if (r.Rete) return Errore("Sol non riesce a raggiungere il server a questo indirizzo. Controlla l'indirizzo e la connessione a internet.", "rete");
        if (r.Stato == 401) return Errore("Il codice non è giusto oppure è scaduto (vale 15 minuti). Creane uno nuovo e riprova.", "codice");
        if (r.Stato == 429) return Errore("Troppi codici sbagliati di fila. Aspetta un quarto d'ora e riprova.", "codice");
        if (!r.Ok) return Errore(Frasi.PerRisposta(r.Stato, r.Corpo), "server");
        var risposta = Json.Leggi<RispostaAbbina>(r.Corpo);
        if (string.IsNullOrWhiteSpace(risposta?.Token)) return Errore("Il server ha risposto in modo strano: riprova, e se succede ancora va controllato il server.", "server");

        lock (stato)
        {
            config = new Configurazione
            {
                Server = server,
                TokenProtetto = Dpapi.Proteggi(risposta!.Token!),
                DispositivoId = risposta.DispositivoId,
                NomeDispositivo = nomeDispositivo,
                AbbinatoIl = orologio(),
                AvvioAlLogin = avvioAlLogin,
            };
            token = risposta.Token;
            notifiche = new StatoNotifiche();
            SalvaConfig();
            SalvaNotifiche();
        }
        memoria.Svuota();
        NonRiconosciuto = false;
        ServerRaggiungibile = true;
        UltimoContatto = orologio();
        sorveglianza.Esito(senzaRisposta: false, orologio());
        ApplicaAvvio(avvioAlLogin);
        Log.Info("computer abbinato al server");
        StatoCambiato?.Invoke();
        Sveglia();
        return new JsonObject { ["ok"] = true };
    }

    /// <summary>
    /// Scollega questo computer: prova a revocare il token sul server, poi dimentica tutto (anche gli ultimi dati visti).
    /// Se il server non ha confermato la revoca, lo dice: sul server il computer risulta ancora collegato.
    /// </summary>
    public async Task<JsonObject> ScollegaAsync()
    {
        var (server, tok) = Credenziali();
        long? id;
        lock (stato) id = config.DispositivoId;
        bool revocato = false;
        if (server != null && tok != null && id != null)
        {
            var r = await client.InviaAsync("DELETE", server, "api/dispositivi/" + id.Value.ToString(CultureInfo.InvariantCulture), tok, null, TimeSpan.FromSeconds(8)).ConfigureAwait(false);
            // 401: il token era già revocato; 404: il dispositivo non c'è più. In entrambi i casi sul server non vale più.
            revocato = r.Ok || r.Stato is 401 or 404;
        }
        lock (stato)
        {
            var avvio = config.AvvioAlLogin;
            config = new Configurazione { AvvioAlLogin = avvio };
            token = null;
            notifiche = new StatoNotifiche();
            SalvaConfig();
            SalvaNotifiche();
        }
        memoria.Svuota();
        NonRiconosciuto = false;
        UrgentiDaLeggere = null;
        sorveglianza.Esito(senzaRisposta: false, orologio());
        UrgentiCambiati?.Invoke(null);
        Log.Info(revocato ? "computer scollegato" : "computer scollegato solo qui: il server non ha confermato la revoca");
        StatoCambiato?.Invoke();
        var esito = new JsonObject { ["ok"] = true, ["revocato_sul_server"] = revocato };
        if (!revocato)
        {
            esito["avviso"] = "Questo computer non vede più la posta, ma il server di casa non ha risposto: lì risulta ancora collegato. "
                + "Toglilo da un altro dispositivo, in Impostazioni > Dispositivi collegati.";
        }
        return esito;
    }

    public JsonObject ImpostaAvvioAlLogin(bool attivo)
    {
        lock (stato)
        {
            config.AvvioAlLogin = attivo;
            SalvaConfig();
        }
        if (ApplicaAvvioLogin == null) return Errore("L'avvio automatico si può scegliere solo nel programma installato.", "non_disponibile");
        return ApplicaAvvio(attivo) ? new JsonObject { ["ok"] = true, ["avvio_al_login"] = attivo } : Errore("Windows non ha permesso di cambiare l'avvio automatico.", "sistema");
    }

    /// <summary>
    /// All'avvio del programma installato: se l'utente ha scelto l'avvio automatico, la voce si riscrive col percorso
    /// di adesso (la cartella può essere stata spostata o sostituita da una versione nuova).
    /// </summary>
    public void RiallineaAvvioAlLogin()
    {
        bool attivo;
        lock (stato) attivo = config.AvvioAlLogin && config.Server != null;
        if (attivo) ApplicaAvvio(true);
    }

    private bool ApplicaAvvio(bool attivo)
    {
        try
        {
            ApplicaAvvioLogin?.Invoke(attivo);
            return true;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            Log.Errore("avvio al login non cambiato", e);
            return false;
        }
    }

    // ---------------- Le richieste della pagina ----------------

    /// <summary>
    /// Gira una richiesta della pagina al server, col token. Le letture riuscite si conservano; se il server
    /// non risponde, una lettura riceve l'ultima copia con la sua data. L'invio delle bozze non passa da qui.
    /// </summary>
    public async Task<RispostaPonte> InoltraAsync(string metodo, string percorso, string? query, string? corpo)
    {
        metodo = metodo.ToUpperInvariant();
        percorso = percorso.TrimStart('/');
        if (PercorsoInvio.IsMatch(percorso)) return ErrorePonte(403, Frasi.InvioSoloConConferma, "conferma_obbligatoria");
        if (!PercorsoAmmesso.IsMatch(percorso) || percorso.Contains("//", StringComparison.Ordinal) || percorso.StartsWith("api/abbina", StringComparison.OrdinalIgnoreCase))
        {
            return ErrorePonte(404, Frasi.RichiestaSconosciuta, "non_trovato");
        }
        if (metodo is not ("GET" or "POST" or "PUT" or "PATCH" or "DELETE")) return ErrorePonte(405, Frasi.RichiestaSconosciuta, "non_valido");
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return ErrorePonte(401, Frasi.NonCollegato, "non_abbinato");
        if (metodo == "POST" && Indirizzo.SenzaProtezione(server) && PercorsoConPassword.IsMatch(percorso) && ContienePassword(corpo))
        {
            // Una password (per le app di Gmail) non viaggia mai in chiaro, nemmeno in casa (§15.13).
            var rifiuto = Errore(FrasePasswordSenzaHttps, "non_valido");
            rifiuto["campo"] = "password_app";
            return new RispostaPonte(422, rifiuto.ToJsonString());
        }

        var completo = percorso + (string.IsNullOrEmpty(query) || query == "?" ? "" : (query.StartsWith('?') ? query : "?" + query));
        var chiave = "GET " + completo;
        var r = await client.InviaAsync(metodo, server, completo, tok, metodo == "GET" ? null : (corpo ?? "{}")).ConfigureAwait(false);
        Annota(r, percorso);
        if (r.Rete)
        {
            if (metodo == "GET" && memoria.Leggi(chiave) is Memoria.Voce voce) return new RispostaPonte(200, voce.Corpo, voce.SalvataIl);
            return ErrorePonte(503, Frasi.ServerIrraggiungibile, "rete");
        }
        if (r.Ok)
        {
            var testo = string.IsNullOrWhiteSpace(r.Corpo) ? "{}" : r.Corpo;
            if (metodo == "GET") memoria.Salva(chiave, testo, orologio());
            return new RispostaPonte(r.Stato, testo);
        }
        return new RispostaPonte(r.Stato, ErroreLeggibile(r).ToJsonString());
    }

    /// <summary>
    /// Gli errori arrivano alla pagina sempre nella stessa forma: la frase del server (o una nostra, mai un codice),
    /// il <c>codice</c>, e tutto il resto che il server ha mandato (<c>campo</c> dei 422, <c>bozza</c> dei 409/502).
    /// </summary>
    private static JsonObject ErroreLeggibile(Risposta r)
    {
        var codice = Json.Leggi<ErroreServer>(r.Corpo.TrimStart().StartsWith('{') ? r.Corpo : null)?.Codice;
        var o = new JsonObject { ["errore"] = Frasi.PerRisposta(r.Stato, r.Corpo), ["codice"] = codice ?? "errore" };
        if (Json.Nodo(r.Corpo) is JsonObject originale)
        {
            foreach (var (k, v) in originale)
            {
                if (k is "errore" or "codice") continue;
                o[k] = v?.DeepClone();
            }
        }
        return o;
    }

    /// <summary>
    /// L'invio di una bozza (§7, §15.7, §15.11). Si rilegge la bozza dal server; se non è più la versione che la pagina
    /// ha mostrato (<paramref name="versioneVista"/>), niente conferma: si torna alla pagina con la bozza nuova.
    /// Altrimenti si chiede la conferma esplicita mostrando A, Cc, Oggetto e prima riga di QUESTA versione, e solo con
    /// "Invia" parte <c>POST /api/bozze/{id}/invia</c> con <c>{"conferma": true, "versione": N}</c>: se nel frattempo è
    /// cambiata, il server risponde 409 e non parte niente.
    /// </summary>
    public async Task<JsonObject> InviaBozzaAsync(long? bozzaId, long? versioneVista = null)
    {
        if (bozzaId is not long id || id <= 0) return Errore("Non trovo la bozza da inviare.", "non_valido");
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return Errore(Frasi.NonCollegato, "non_abbinato");
        lock (inviiInCorso)
        {
            if (!inviiInCorso.Add(id)) return Errore("L'invio di questa mail è già in corso.", "conflitto");
        }
        try
        {
            var idTesto = id.ToString(CultureInfo.InvariantCulture);
            var letta = await client.InviaAsync("GET", server, "api/bozze/" + idTesto, tok, null).ConfigureAwait(false);
            Annota(letta, "api/bozze");
            if (!letta.Ok) return Errore(Frasi.PerRisposta(letta.Stato, letta.Corpo), letta.Rete ? "rete" : "server");
            var bozza = Json.Leggi<Bozza>(letta.Corpo);
            if (bozza == null) return Errore("La bozza non si riesce a leggere. Riprova tra poco.", "server");
            switch (bozza.Stato)
            {
                case "inviata": return ConBozza(Errore("Questa mail è già stata inviata.", "conflitto"), letta.Corpo);
                case "in_preparazione": return Errore("Sol sta ancora scrivendo questa bozza: aspetta che sia pronta.", "conflitto");
                case "scartata": return Errore("Questa bozza è stata scartata: preparane una nuova.", "conflitto");
            }
            if (versioneVista != null && bozza.Versione != null && bozza.Versione != versioneVista)
            {
                return ConBozza(Errore(FraseCambiata, "conflitto"), letta.Corpo);
            }
            var a = bozza.A.Where(x => !string.IsNullOrWhiteSpace(x)).ToList();
            if (a.Count == 0) return Errore("Manca il destinatario: scrivi almeno un indirizzo nel campo A.", "non_valido");
            if (string.IsNullOrWhiteSpace(bozza.Testo)) return Errore("Il testo è vuoto: scrivi la risposta prima di inviarla.", "non_valido");
            var conferma = ChiediConferma;
            if (conferma == null) return Errore("Non è possibile chiedere la conferma: la mail non è partita.", "conferma_obbligatoria");

            var sollecitoPer = bozza.Tipo == "sollecito" ? await SollecitoPerAsync(server, tok, bozza).ConfigureAwait(false) : null;
            var (giaRisposto, sospetta) = await MailOriginaleAsync(server, tok, bozza).ConfigureAwait(false);
            var anteprima = new AnteprimaInvio(id, a, bozza.Cc.Where(x => !string.IsNullOrWhiteSpace(x)).ToList(), bozza.Oggetto ?? "", bozza.Tipo,
                ConfermaInvio.PrimaRiga(bozza.Testo), sollecitoPer, giaRisposto, sospetta);
            bool si;
            try
            {
                si = await conferma(anteprima).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                Log.Errore("finestra di conferma dell'invio", e);
                si = false;
            }
            if (!si)
            {
                Log.Info("invio annullato dall'utente");
                return new JsonObject { ["annullato"] = true };
            }

            var richiesta = new JsonObject { ["conferma"] = true };
            if (bozza.Versione is long versione) richiesta["versione"] = versione;
            var r = await client.InviaAsync("POST", server, "api/bozze/" + idTesto + "/invia", tok, richiesta.ToJsonString()).ConfigureAwait(false);
            Annota(r, "api/bozze");
            if (r.Ok)
            {
                var corpo = Json.Nodo(r.Corpo) as JsonObject;
                bool giaInviata = Json.Booleano(corpo?["gia_inviata"]) == true;
                Log.Info(giaInviata ? "mail già partita: il server l'ha trovata in Posta inviata" : "mail inviata");
                var esito = new JsonObject { ["inviata"] = true, ["gia_inviata"] = giaInviata, ["bozza"] = corpo?.DeepClone() };
                return esito;
            }
            Log.Avviso($"invio non riuscito (stato {r.Stato})");
            if (r.Rete || r.Stato is 500 or 504 || (r.Stato == 502 && !ÈOggetto(r.Corpo)))
            {
                // La richiesta è partita e la risposta non è arrivata (o è arrivata rotta): non si sa se la mail è partita.
                return Errore(FraseInvioIncerto, "incerto");
            }
            return ErroreLeggibile(r);
        }
        finally
        {
            lock (inviiInCorso) inviiInCorso.Remove(id);
        }
    }

    /// <summary>"neri@esempio.it – Offerta compressore": a quale mail inviata si riferisce un sollecito.</summary>
    private async Task<string> SollecitoPerAsync(string server, string tok, Bozza bozza)
    {
        if (bozza.InviataId is long inviataId)
        {
            var r = await client.InviaAsync("GET", server, "api/indietro", tok, null, TimeSpan.FromSeconds(10)).ConfigureAwait(false);
            if (r.Ok && Json.Nodo(r.Corpo)?["aspetti_tu"] is JsonArray voci)
            {
                foreach (var voce in voci)
                {
                    var inviata = voce?["inviata"];
                    if (Json.Intero(inviata?["id"]) != inviataId) continue;
                    var a = (inviata?["a"] as JsonArray)?.Select(Json.Testo).Where(x => !string.IsNullOrWhiteSpace(x)) ?? Enumerable.Empty<string?>();
                    var quando = DateTimeOffset.TryParse(Json.Testo(inviata?["inviata_il"]), CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)
                        ? $" (inviata il {d.ToLocalTime().ToString("d MMMM yyyy", CultureInfo.GetCultureInfo("it-IT"))})" : "";
                    return $"{string.Join(", ", a)} – {Json.Testo(inviata?["oggetto"])}{quando}";
                }
            }
        }
        var oggetto = (bozza.Oggetto ?? "").Trim();
        if (oggetto.StartsWith("Re:", StringComparison.OrdinalIgnoreCase)) oggetto = oggetto[3..].Trim();
        return $"{string.Join(", ", bozza.A)} – {oggetto}";
    }

    private static bool ÈOggetto(string corpo) => Json.Nodo(corpo) is JsonObject o && o["errore"] != null;

    private static bool ContienePassword(string? corpo) =>
        Json.Nodo(corpo) is JsonObject o && !string.IsNullOrWhiteSpace(Json.Testo(o["password_app"]));

    /// <summary>
    /// La mail a cui risponde la bozza: ha già una risposta data da un altro programma (§15.13)? È segnalata come
    /// possibile truffa (§16.3)? Tutte e due le cose finiscono nella finestra di conferma.
    /// </summary>
    private async Task<(bool GiaRisposto, bool Sospetta)> MailOriginaleAsync(string server, string tok, Bozza bozza)
    {
        if (bozza.Tipo != "risposta" || bozza.MailId is not long mailId) return (false, false);
        var r = await client.InviaAsync("GET", server, "api/mail/" + mailId.ToString(CultureInfo.InvariantCulture), tok, null, TimeSpan.FromSeconds(10)).ConfigureAwait(false);
        if (!r.Ok || Json.Nodo(r.Corpo) is not JsonObject mail) return (false, false);
        return (Json.Booleano(mail["risposta_data"]) == true, Json.Booleano(mail["sospetta"]) == true);
    }

    private static JsonObject ConBozza(JsonObject esito, string corpoBozza)
    {
        if (Json.Nodo(corpoBozza) is JsonObject bozza) esito["bozza"] = bozza;
        return esito;
    }

    // ---------------- Aggiornamento ----------------

    /// <summary>
    /// Scarica la versione nuova nella cartella Download e ne controlla l'impronta (§15.10). Uno alla volta; l'esito
    /// arriva con <see cref="ScaricamentoFinito"/>. false = non c'è niente da scaricare o è già in corso.
    /// </summary>
    public bool AvviaScaricamento()
    {
        if (Aggiornatore.Disponibile == null) return false;
        if (Interlocked.Exchange(ref scaricamentoInCorso, 1) == 1) return false;
        ultimoScaricamento = null;
        StatoCambiato?.Invoke();
        _ = Task.Run(async () =>
        {
            EsitoScaricamento esito;
            try
            {
                esito = await Aggiornatore.ScaricaAsync(CartellaDownload).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                Log.Errore("scaricamento della versione nuova", e);
                esito = new EsitoScaricamento(false, null, "Lo scaricamento della versione nuova non è riuscito. Riprova più tardi.");
            }
            ultimoScaricamento = esito;
            Volatile.Write(ref scaricamentoInCorso, 0);
            StatoCambiato?.Invoke();
            ScaricamentoFinito?.Invoke(esito);
        });
        return true;
    }

    /// <summary>
    /// Tiene nota dello stato del collegamento dopo ogni risposta. <c>/api/salute</c> risponde 200 anche a un token
    /// revocato (§15.12): lì conta solo <c>autenticato</c>, mai il 200 da solo.
    /// </summary>
    private void Annota(Risposta r, string percorso)
    {
        bool cambiato = false;
        sorveglianza.Esito(r.Rete, orologio());
        if (r.Rete)
        {
            cambiato = ServerRaggiungibile != false;
            ServerRaggiungibile = false;
        }
        else
        {
            cambiato = ServerRaggiungibile != true;
            ServerRaggiungibile = true;
            UltimoContatto = orologio();
            bool? riconosciuto = null;
            if (percorso.StartsWith("api/salute", StringComparison.OrdinalIgnoreCase))
            {
                if (r.Ok) riconosciuto = Json.Booleano((Json.Nodo(r.Corpo) as JsonObject)?["autenticato"]);
            }
            else if (r.Stato == 401)
            {
                riconosciuto = false;
            }
            else if (r.Ok)
            {
                riconosciuto = true;
            }
            if (riconosciuto == false && !NonRiconosciuto)
            {
                Log.Avviso("il server non riconosce più il token di questo computer");
                NonRiconosciuto = true;
                cambiato = true;
            }
            else if (riconosciuto == true && NonRiconosciuto)
            {
                NonRiconosciuto = false;
                cambiato = true;
            }
        }
        if (cambiato) StatoCambiato?.Invoke();
    }

    private static JsonObject Errore(string frase, string codice) => new() { ["errore"] = frase, ["codice"] = codice };

    private static RispostaPonte ErrorePonte(int stato, string frase, string codice) =>
        new(stato, Errore(frase, codice).ToJsonString());

    private void SalvaConfig()
    {
        try
        {
            Archivio.ScriviJson(percorsi.Config, config);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("config.json non scritto", e);
        }
    }

    private void SalvaNotifiche()
    {
        try
        {
            Archivio.ScriviJson(percorsi.Notifiche, notifiche);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("notifiche.json non scritto", e);
        }
    }

    private bool chiuso;

    public void Dispose()
    {
        if (chiuso) return;
        chiuso = true;
        fine?.Cancel();
        try
        {
            ciclo?.Wait(TimeSpan.FromSeconds(2));
        }
        catch (AggregateException)
        {
        }
        fine?.Dispose();
        if (clientNostro) client.Dispose();
    }
}
