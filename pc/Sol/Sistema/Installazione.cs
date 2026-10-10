using Microsoft.Win32;

namespace Sol.Sistema;

/// <summary>
/// Installazione per utente, senza amministratore e senza copiare niente: Sol gira dalla cartella
/// dove lo zip è stato scompattato. L'unica cosa che scrive fuori è, se l'utente lo sceglie, la voce
/// di avvio all'accesso in <c>HKCU\...\Run</c> (nome "Sol").
/// </summary>
public static class Installazione
{
    public const string NomeAvvio = "Sol";
    private const string ChiaveRun = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>
    /// true quando gira dal pacchetto pubblicato (self-contained): accanto all'exe c'è il runtime .NET.
    /// Una build di sviluppo e i test no: non toccano l'avvio automatico e non si aggiornano.
    /// </summary>
    public static bool ÈPacchetto => File.Exists(Path.Combine(AppContext.BaseDirectory, "coreclr.dll"));

    /// <summary>Se accanto all'exe manca l'interfaccia (cancellata per sbaglio), la tira fuori da quella incorporata.</summary>
    public static void AssicuraUi(string cartellaExe)
    {
        var ui = Path.Combine(cartellaExe, "ui");
        if (File.Exists(Path.Combine(ui, "index.html"))) return;
        var assembly = typeof(Installazione).Assembly;
        foreach (var nome in assembly.GetManifestResourceNames().Where(n => n.StartsWith("ui/", StringComparison.Ordinal)))
        {
            var relativo = nome["ui/".Length..].Replace('\\', Path.DirectorySeparatorChar).Replace('/', Path.DirectorySeparatorChar);
            if (relativo.Contains("..", StringComparison.Ordinal)) continue;
            var file = Path.Combine(ui, relativo);
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            using var sorgente = assembly.GetManifestResourceStream(nome)!;
            using var uscita = File.Create(file);
            sorgente.CopyTo(uscita);
        }
    }

    /// <summary>Scrive o toglie la voce di avvio all'accesso, che punta all'exe dov'è adesso.</summary>
    public static void ImpostaAvvio(string eseguibile, bool attivo)
    {
        using var chiave = Registry.CurrentUser.CreateSubKey(ChiaveRun);
        if (!attivo)
        {
            if (chiave.GetValue(NomeAvvio) != null) chiave.DeleteValue(NomeAvvio, throwOnMissingValue: false);
            return;
        }
        var valore = $"\"{eseguibile}\" --avvio";
        if (!string.Equals(chiave.GetValue(NomeAvvio) as string, valore, StringComparison.OrdinalIgnoreCase))
        {
            chiave.SetValue(NomeAvvio, valore, RegistryValueKind.String);
        }
    }
}
