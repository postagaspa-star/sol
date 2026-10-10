using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Sol.Motore;

namespace Sol.Interfaccia;

/// <summary>
/// La finestra di Sol: una WebView2 che mostra l'interfaccia HTML da <c>https://sol.locale/</c>
/// (cartella <c>ui</c>). Non può andare altrove. Chiusa, si libera del tutto (niente processi
/// della WebView accesi in sottofondo); il programma resta nell'icona vicino all'orologio.
/// </summary>
public sealed class FinestraSol : Form
{
    private readonly WebView2 vista;
    private readonly Ponte ponte;
    private readonly string cartellaUi;
    private readonly string cartellaDatiWebView;
    private CoreWebView2Environment? ambiente;
    private string? frammentoDaAprire;
    private bool chiusuraConfermata;
    private bool chiusuraInCorso;

    /// <summary>Le sezioni dell'interfaccia (SEZIONI di app.js) che si possono aprire da fuori (clic su una notifica).</summary>
    private static readonly HashSet<string> Sezioni = new(StringComparer.Ordinal)
    {
        "posta", "da-rispondere", "urgenti", "da-controllare", "riassunti", "indietro", "scadenze", "impostazioni", "notifiche",
    };

    public FinestraSol(Ponte ponte, string cartellaUi, string cartellaDatiWebView, Icon icona, string? frammento = null)
    {
        this.ponte = ponte;
        this.cartellaUi = cartellaUi;
        this.cartellaDatiWebView = cartellaDatiWebView;
        frammentoDaAprire = frammento;

        Text = "Sol";
        Icon = icona;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        ClientSize = new Size(1360, 840);
        MinimumSize = new Size(980, 600);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(0xF6, 0xF7, 0xF9);

        vista = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = BackColor };
        Controls.Add(vista);
        Load += async (_, _) => await InizializzaAsync();
    }

    public static string? FrammentoSezione(string? sezione) => sezione != null && Sezioni.Contains(sezione) ? sezione : null;

    public static string FrammentoMail(long id) => "mail/" + id.ToString(CultureInfo.InvariantCulture);

    /// <summary>L'indirizzo iniziale: solo frammenti costruiti qui (sezioni note o mail/numero).</summary>
    public static string IndirizzoIniziale(string? frammento) =>
        Ponte.Origine + "/index.html" + (FrammentoValido(frammento) ? "#" + frammento : "");

    internal static bool FrammentoValido(string? f)
    {
        if (string.IsNullOrEmpty(f)) return false;
        if (Sezioni.Contains(f)) return true;
        return f.StartsWith("mail/", StringComparison.Ordinal) && long.TryParse(f.AsSpan(5), NumberStyles.None, CultureInfo.InvariantCulture, out var id) && id > 0;
    }

    /// <summary>Porta la finestra su una mail o su una sezione (clic su una notifica). Se la pagina non c'è ancora, lo fa appena è pronta.</summary>
    public void Mostra(string frammento)
    {
        if (!FrammentoValido(frammento)) return;
        var core = vista.CoreWebView2;
        if (core == null)
        {
            frammentoDaAprire = frammento;
            return;
        }
        try
        {
            // Solo un frammento controllato qui sopra (sezione nota o "mail/" + numero) finisce nello script.
            _ = core.ExecuteScriptAsync("window.solApri ? window.solApri('" + frammento + "') : (location.hash = '#" + frammento + "');");
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException or System.Runtime.InteropServices.COMException)
        {
            Log.Errore("frammento della finestra non aperto", e);
        }
    }

    /// <summary>
    /// Chiudendo la finestra (X, Alt+F4) la pagina salva prima la risposta che si sta scrivendo; se non ci riesce,
    /// si chiede a Manuel se chiudere lo stesso. Allo spegnimento di Windows non si chiede niente.
    /// </summary>
    protected override async void OnFormClosing(FormClosingEventArgs e)
    {
        if (chiusuraConfermata || e.CloseReason is CloseReason.WindowsShutDown or CloseReason.TaskManagerClosing or CloseReason.ApplicationExitCall)
        {
            base.OnFormClosing(e);
            return;
        }
        e.Cancel = true;
        if (chiusuraInCorso) return;
        if (await PreparaChiusuraAsync())
        {
            chiusuraConfermata = true;
            Close();
        }
    }

    /// <summary>Chiude senza chiedere (l'uscita dal programma ha già fatto i suoi controlli).</summary>
    public void ChiudiSenzaChiedere()
    {
        chiusuraConfermata = true;
        Close();
    }

    /// <summary>
    /// Chiede alla pagina di salvare quello che c'è da salvare e aspetta l'esito (al massimo 15 secondi).
    /// true = si può chiudere; false = Manuel ha scelto di restare.
    /// </summary>
    public async Task<bool> PreparaChiusuraAsync()
    {
        var core = vista.CoreWebView2;
        if (core == null) return true;
        chiusuraInCorso = true;
        try
        {
            string stato;
            try
            {
                stato = StatoSalvataggio(await core.ExecuteScriptAsync("window.solSalvaPrimaDiChiudere ? window.solSalvaPrimaDiChiudere() : 'pulito'"));
                for (int i = 0; i < 50 && stato == "in_corso"; i++)
                {
                    await Task.Delay(300);
                    stato = StatoSalvataggio(await core.ExecuteScriptAsync("window.solStatoSalvataggio ? window.solStatoSalvataggio() : 'pulito'"));
                }
            }
            catch (Exception ex) when (ex is InvalidOperationException or ObjectDisposedException or System.Runtime.InteropServices.COMException)
            {
                Log.Errore("salvataggio prima della chiusura", ex);
                stato = "fallito";
            }
            if (stato == "pulito") return true;
            var resta = new TaskDialogButton("Resta qui");
            var chiudi = new TaskDialogButton("Chiudi senza salvare");
            var pagina = new TaskDialogPage
            {
                Caption = "Sol",
                Heading = "La risposta non è salvata",
                Text = "La risposta che stavi scrivendo non si è salvata sul server di casa. Se chiudi adesso, le ultime modifiche si perdono.",
                Buttons = { resta, chiudi },
                DefaultButton = resta,
                AllowCancel = true,
            };
            return ReferenceEquals(TaskDialog.ShowDialog(this, pagina, TaskDialogStartupLocation.CenterOwner), chiudi);
        }
        finally
        {
            chiusuraInCorso = false;
        }
    }

    /// <summary>Il risultato di ExecuteScriptAsync (testo JSON) come stato del salvataggio: pulito, in_corso o fallito.</summary>
    internal static string StatoSalvataggio(string? json)
    {
        var testo = Nucleo.Json.Testo(Nucleo.Json.Nodo(json));
        return testo is "pulito" or "in_corso" ? testo : "fallito";
    }

    private async Task InizializzaAsync()
    {
        try
        {
            ambiente = await CoreWebView2Environment.CreateAsync(null, cartellaDatiWebView);
            await vista.EnsureCoreWebView2Async(ambiente);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MostraMessaggio("Per aprire Sol serve Microsoft Edge WebView2, che su Windows 11 c'è sempre. Le notifiche intanto continuano ad arrivare.");
            Log.Errore("WebView2 non trovata");
            return;
        }
        catch (Exception e)
        {
            MostraMessaggio("La finestra di Sol non si è aperta. Le notifiche intanto continuano ad arrivare.");
            Log.Errore("WebView2 non avviata", e);
            return;
        }

        var core = vista.CoreWebView2;
        await SvuotaCacheSeCambiataAsync(core);
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = true; // copia e incolla col tasto destro, nei campi della bozza
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.IsWebMessageEnabled = false;
        core.Settings.IsGeneralAutofillEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.Settings.IsZoomControlEnabled = true;

        // Come in Pactum: niente SetVirtualHostNameToFolderMapping (con la mappatura WebResourceRequested
        // non scatta). Tutto https://sol.locale/ passa da qui: /locale e /server al ponte, il resto è la cartella ui.
        core.AddWebResourceRequestedFilter(Ponte.Origine + "/*", CoreWebView2WebResourceContext.All);
        core.WebResourceRequested += SuRichiesta;
        core.NavigationStarting += SuNavigazione;
        core.FrameNavigationStarting += SuNavigazione;
        core.NewWindowRequested += SuNuovaFinestra;
        core.Navigate(IndirizzoIniziale(frammentoDaAprire));
        frammentoDaAprire = null;
    }

    /// <summary>Dopo un aggiornamento la WebView potrebbe servire i file vecchi dalla cache: se l'interfaccia è cambiata, la si svuota.</summary>
    private async Task SvuotaCacheSeCambiataAsync(CoreWebView2 core)
    {
        var fileImpronta = Path.Combine(cartellaDatiWebView, "impronta-ui.txt");
        var impronta = ImprontaUi(cartellaUi);
        string? precedente = null;
        try
        {
            if (File.Exists(fileImpronta)) precedente = File.ReadAllText(fileImpronta);
        }
        catch (IOException)
        {
        }
        if (precedente == impronta) return;
        try
        {
            await core.Profile.ClearBrowsingDataAsync(
                CoreWebView2BrowsingDataKinds.DiskCache | CoreWebView2BrowsingDataKinds.CacheStorage | CoreWebView2BrowsingDataKinds.ServiceWorkers);
            Nucleo.Archivio.ScriviTesto(fileImpronta, impronta);
        }
        catch (Exception e)
        {
            Log.Errore("cache della finestra non svuotata", e);
        }
    }

    public static string ImprontaUi(string cartella)
    {
        var testo = new StringBuilder(Versione.Nome);
        if (Directory.Exists(cartella))
        {
            foreach (var f in Directory.EnumerateFiles(cartella, "*", SearchOption.AllDirectories).OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var info = new FileInfo(f);
                testo.Append('|').Append(Path.GetRelativePath(cartella, f)).Append(':').Append(info.Length).Append(':').Append(info.LastWriteTimeUtc.Ticks);
            }
        }
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(testo.ToString())));
    }

    private async void SuRichiesta(object? mittente, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var rinvio = e.GetDeferral();
        try
        {
            var metodo = e.Request.Method;
            var uri = new Uri(e.Request.Uri);
            if (!Ponte.ÈApi(uri))
            {
                e.Response = FileInterfaccia(metodo, uri);
                return;
            }
            string? corpo = null;
            if (e.Request.Content != null)
            {
                using var lettore = new StreamReader(e.Request.Content, Encoding.UTF8);
                corpo = await lettore.ReadToEndAsync();
            }
            var risposta = await ponte.GestisciAsync(metodo, uri, corpo);
            e.Response = Risposta(risposta);
        }
        catch (Exception ex)
        {
            Log.Errore("richiesta dell'interfaccia", ex);
            e.Response = Risposta(new RispostaPonte(500, "{\"errore\":\"Qualcosa non ha funzionato nel programma. Riprova.\"}"));
        }
        finally
        {
            rinvio.Complete();
        }
    }

    private CoreWebView2WebResourceResponse FileInterfaccia(string metodo, Uri uri)
    {
        var file = metodo is "GET" or "HEAD" ? Ponte.FileDellInterfaccia(cartellaUi, uri.AbsolutePath) : null;
        if (file == null || !File.Exists(file))
        {
            return ambiente!.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes("non trovato")), 404, "Not Found",
                "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store");
        }
        return ambiente!.CreateWebResourceResponse(new MemoryStream(File.ReadAllBytes(file)), 200, "OK",
            $"Content-Type: {Ponte.TipoContenuto(file)}\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff");
    }

    private CoreWebView2WebResourceResponse Risposta(RispostaPonte r)
    {
        var intestazioni = "Content-Type: application/json; charset=utf-8\r\nCache-Control: no-store";
        // Gli ultimi dati visti (server irraggiungibile): la pagina lo dice con la data di quella copia.
        if (r.DaMemoriaDel is DateTimeOffset quando) intestazioni += "\r\nX-Sol-Memoria: " + quando.ToString("o", CultureInfo.InvariantCulture);
        return ambiente!.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes(r.Json)), r.Stato, r.Stato < 400 ? "OK" : "Error", intestazioni);
    }

    private void SuNavigazione(object? mittente, CoreWebView2NavigationStartingEventArgs e)
    {
        if (Ponte.ÈInterno(e.Uri)) return;
        e.Cancel = true;
        if (Ponte.ApribileFuori(e.Uri)) ContestoSol.ApriNelBrowser(e.Uri);
    }

    private void SuNuovaFinestra(object? mittente, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        if (Ponte.ApribileFuori(e.Uri)) ContestoSol.ApriNelBrowser(e.Uri);
    }

    private void MostraMessaggio(string testo)
    {
        Controls.Clear();
        Controls.Add(new Label
        {
            Text = testo,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 11F),
            Padding = new Padding(40),
        });
    }
}
