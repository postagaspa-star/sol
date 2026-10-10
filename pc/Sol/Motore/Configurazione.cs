namespace Sol.Motore;

/// <summary>Dove stanno i dati: <c>%LOCALAPPDATA%\Sol\</c> (o la cartella di <c>--dati</c> per le prove).</summary>
public sealed class Percorsi
{
    public Percorsi(string radice)
    {
        Radice = radice;
        Directory.CreateDirectory(radice);
    }

    public static string Predefinita =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Sol");

    public string Radice { get; }
    public string Config => Path.Combine(Radice, "config.json");
    public string Notifiche => Path.Combine(Radice, "notifiche.json");
    public string Memoria => Path.Combine(Radice, "memoria");
    public string Aggiornamento => Path.Combine(Radice, "aggiornamento");
    public string Log => Path.Combine(Radice, "log");
    public string WebView => Path.Combine(Radice, "WebView2");
}

/// <summary><c>config.json</c>: server, token protetto con DPAPI, dispositivo. Mai il token in chiaro.</summary>
public sealed class Configurazione
{
    public string? Server { get; set; }
    public string? TokenProtetto { get; set; }
    public long? DispositivoId { get; set; }
    public string? NomeDispositivo { get; set; }
    public DateTimeOffset? AbbinatoIl { get; set; }

    /// <summary>Apri Sol all'accesso a Windows: scelto all'abbinamento, cambiabile nelle impostazioni.</summary>
    public bool AvvioAlLogin { get; set; }
}
