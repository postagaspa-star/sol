using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using Sol.Nucleo;
using Sol.Sistema;
using Log = Sol.Motore.Log;
using MotoreSol = Sol.Motore.Motore;

namespace Sol.Interfaccia;

/// <summary>
/// Il programma quando gira: l'icona vicino all'orologio (col numero delle urgenti, o sbiadita se il collegamento
/// non va), il menu, le notifiche di Windows, la finestra quando serve e la conferma prima dell'invio.
/// </summary>
public sealed class ContestoSol : ApplicationContext
{
    private static readonly TimeSpan IntervalloNotifiche = TimeSpan.FromSeconds(7);
    // Destinazioni delle notifiche che non sono una sezione della pagina.
    private const string AzioneScarica = "scarica-aggiornamento";
    private const string AzioneMostraZip = "mostra-zip";

    private readonly MotoreSol motore;
    private readonly Ponte ponte;
    private readonly Opzioni opzioni;
    private readonly Istanza istanza;
    private readonly NotifyIcon icona;
    private readonly Control invocatore;
    private readonly ToolStripMenuItem voceScarica;
    private readonly Queue<Notifica> coda = new();
    private readonly System.Windows.Forms.Timer timerNotifiche;
    private Icon iconaAttuale;
    private FinestraSol? finestra;
    private string? zipScaricato;
    private bool uscito;
    private bool chiusuraInCorso;

    public ContestoSol(MotoreSol motore, Opzioni opzioni, Istanza istanza)
    {
        this.motore = motore;
        this.opzioni = opzioni;
        this.istanza = istanza;
        ponte = new Ponte(motore) { ApriNelBrowser = ApriNelBrowser };
        motore.ChiediConferma = ChiediConfermaAsync;

        invocatore = new Control();
        invocatore.CreateControl();

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("Apri Sol", null, (_, _) => Apri(null)) { Font = new Font(SystemFonts.MenuFont ?? Control.DefaultFont, FontStyle.Bold) });
        menu.Items.Add(new ToolStripMenuItem("Controlla le novità adesso", null, (_, _) => motore.Sveglia()));
        menu.Items.Add(new ToolStripMenuItem("Notifiche recenti", null, (_, _) => Apri("notifiche")));
        voceScarica = new ToolStripMenuItem("Scarica la versione nuova", null, (_, _) => Scarica()) { Visible = false };
        menu.Items.Add(voceScarica);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Chiudi Sol", null, async (_, _) => await EsciSalvandoAsync()));

        iconaAttuale = IconaVassoio.ConNumero(null);
        icona = new NotifyIcon { Icon = iconaAttuale, Text = "Sol", ContextMenuStrip = menu, Visible = true };
        icona.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) Apri(null);
        };
        icona.BalloonTipClicked += (_, _) => SuClicNotifica();
        icona.BalloonTipClosed += (_, _) => motore.Notifiche.Chiusa(DateTimeOffset.Now);

        timerNotifiche = new System.Windows.Forms.Timer { Interval = (int)IntervalloNotifiche.TotalMilliseconds };
        timerNotifiche.Tick += (_, _) => ProssimaNotifica();

        motore.NuovaNotifica += n => SulFiloGrafico(() => Accoda(n));
        motore.UrgentiCambiati += _ => SulFiloGrafico(AggiornaIcona);
        motore.StatoCambiato += () => SulFiloGrafico(AggiornaIcona);
        motore.AggiornamentoDisponibile += v => SulFiloGrafico(() =>
        {
            voceScarica.Visible = true;
            voceScarica.Text = $"Scarica la versione {v.Nome}";
            Accoda(new Notifica("C'è una versione nuova di Sol", $"È uscita la {v.Nome}. Fai clic per scaricarla: Sol controlla che il file sia quello giusto.", Sezione: AzioneScarica));
        });
        motore.ScaricamentoFinito += esito => SulFiloGrafico(() => SuScaricamentoFinito(esito));

        istanza.RichiestaApertura += () => SulFiloGrafico(() => Apri(null));
        istanza.SvuotaAperturaInSospeso();

        motore.Avvia();
        if (opzioni.Apri || !opzioni.Avvio || !motore.Abbinato) Apri(null);

        if (opzioni.EsciDopoSecondi is int secondi)
        {
            var t = new System.Windows.Forms.Timer { Interval = secondi * 1000 };
            t.Tick += (_, _) =>
            {
                t.Stop();
                Esci();
            };
            t.Start();
        }
    }

    /// <summary>Apre un indirizzo nel browser predefinito di Windows (già controllato dal ponte).</summary>
    public static void ApriNelBrowser(string indirizzo)
    {
        if (!Ponte.ApribileFuori(indirizzo)) return;
        try
        {
            Process.Start(new ProcessStartInfo(new Uri(indirizzo).AbsoluteUri) { UseShellExecute = true })?.Dispose();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Errore("indirizzo esterno non aperto", e);
        }
    }

    private bool SulFiloGrafico(Action azione)
    {
        if (uscito || invocatore.IsDisposed) return false;
        try
        {
            invocatore.BeginInvoke(azione);
            return true;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    private void Apri(string? frammento)
    {
        if (uscito) return;
        if (finestra == null || finestra.IsDisposed)
        {
            finestra = new FinestraSol(ponte, Path.Combine(AppContext.BaseDirectory, "ui"), Path.Combine(opzioni.CartellaDati, "WebView2"), IconaVassoio.Base(), frammento);
            finestra.FormClosed += (_, _) => finestra = null;
            finestra.Show();
        }
        else if (frammento != null)
        {
            finestra.Mostra(frammento);
        }
        if (finestra.WindowState == FormWindowState.Minimized) finestra.WindowState = FormWindowState.Normal;
        finestra.Activate();
    }

    private void AggiornaIcona()
    {
        if (uscito) return;
        var problema = motore.ProblemaCollegamento;
        var nuova = IconaVassoio.ConNumero(motore.UrgentiDaLeggere, problema);
        icona.Icon = nuova;
        iconaAttuale.Dispose();
        iconaAttuale = nuova;
        icona.Text = IconaVassoio.Suggerimento(motore.UrgentiDaLeggere, motore.NonRiconosciuto, problema);
    }

    // ---------------- Notifiche di Windows ----------------

    private void Accoda(Notifica n)
    {
        if (uscito) return;
        coda.Enqueue(n);
        if (!timerNotifiche.Enabled) ProssimaNotifica();
    }

    private void ProssimaNotifica()
    {
        if (coda.Count == 0)
        {
            timerNotifiche.Stop();
            return;
        }
        var n = coda.Dequeue();
        motore.Notifiche.Mostrata(n, DateTimeOffset.Now);
        icona.ShowBalloonTip((int)IntervalloNotifiche.TotalMilliseconds, Frasi.Accorcia(n.Titolo, 60), Frasi.Accorcia(n.Testo, 250), ToolTipIcon.None);
        timerNotifiche.Start();
    }

    private void SuClicNotifica()
    {
        var destinazione = motore.Notifiche.Cliccata();
        switch (destinazione)
        {
            case AzioneScarica:
                Scarica();
                break;
            case AzioneMostraZip:
                if (zipScaricato != null) Cartelle.MostraFile(zipScaricato);
                break;
            default:
                Apri(destinazione);
                break;
        }
    }

    // ---------------- Invio, aggiornamento, chiusura ----------------

    /// <summary>La conferma esplicita: una finestra di Windows con destinatari, oggetto e prima riga, "Non inviare" come scelta predefinita.</summary>
    private Task<bool> ChiediConfermaAsync(Motore.AnteprimaInvio anteprima)
    {
        var esito = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        bool accodata = SulFiloGrafico(() =>
        {
            try
            {
                var invia = new TaskDialogButton(Motore.ConfermaInvio.PulsantePer(anteprima));
                var annulla = new TaskDialogButton(Motore.ConfermaInvio.PulsanteAnnulla);
                var pagina = new TaskDialogPage
                {
                    Caption = "Sol",
                    Heading = Motore.ConfermaInvio.TitoloPer(anteprima),
                    Text = Motore.ConfermaInvio.Testo(anteprima),
                    Buttons = { invia, annulla },
                    DefaultButton = annulla,
                    AllowCancel = true,
                    SizeToContent = true,
                    // §16.3: risposta a una possibile truffa = avviso in rosso (la barra rossa in cima alla finestra).
                    Icon = anteprima.Sospetta ? TaskDialogIcon.ShieldErrorRedBar : null,
                };
                var scelta = finestra != null && !finestra.IsDisposed
                    ? TaskDialog.ShowDialog(finestra, pagina, TaskDialogStartupLocation.CenterOwner)
                    : TaskDialog.ShowDialog(pagina, TaskDialogStartupLocation.CenterScreen);
                esito.TrySetResult(ReferenceEquals(scelta, invia));
            }
            catch (Exception e)
            {
                Log.Errore("finestra di conferma", e);
                esito.TrySetResult(false);
            }
        });
        if (!accodata) esito.TrySetResult(false);
        return esito.Task;
    }

    private void Scarica()
    {
        if (!motore.AvviaScaricamento()) return;
        Accoda(new Notifica("Sol scarica la versione nuova", "Ci vuole qualche minuto: quando ha finito e ha controllato il file, te lo dice."));
    }

    private void SuScaricamentoFinito(Motore.EsitoScaricamento esito)
    {
        if (esito.Ok && esito.File != null)
        {
            zipScaricato = esito.File;
            Cartelle.MostraFile(esito.File);
            Accoda(new Notifica("Versione nuova scaricata e controllata",
                "Chiudi Sol (tasto destro sull'icona, \"Chiudi Sol\") e scompatta lo zip nella cartella del programma, sostituendo i file.",
                Sezione: AzioneMostraZip));
        }
        else
        {
            Accoda(new Notifica("Versione nuova non scaricata", esito.Errore ?? "Riprova più tardi.", Sezione: "impostazioni"));
        }
    }

    /// <summary>"Chiudi Sol": prima la pagina salva la risposta che si sta scrivendo (e, se non riesce, chiede).</summary>
    private async Task EsciSalvandoAsync()
    {
        if (uscito || chiusuraInCorso) return;
        chiusuraInCorso = true;
        try
        {
            if (finestra != null && !finestra.IsDisposed && !await finestra.PreparaChiusuraAsync()) return;
            Esci();
        }
        finally
        {
            chiusuraInCorso = false;
        }
    }

    private void Esci()
    {
        if (uscito) return;
        uscito = true;
        Log.Info("Sol si chiude");
        timerNotifiche.Stop();
        finestra?.ChiudiSenzaChiedere();
        icona.Visible = false;
        icona.Dispose();
        iconaAttuale.Dispose();
        motore.Dispose();
        ExitThread();
    }
}
