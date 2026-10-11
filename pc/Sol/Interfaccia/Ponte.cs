using System.Text.Json.Nodes;
using Sol.Nucleo;

namespace Sol.Interfaccia;

/// <summary>
/// L'accordo fra la pagina (pc/ui) e il motore: le richieste della pagina a
/// <c>https://sol.locale/locale/*</c> e <c>/server/*</c> arrivano qui, dentro WebView2. Niente porte
/// aperte sul computer, e la pagina non vede mai il token. Tutte le risposte sono JSON.
/// </summary>
public sealed class Ponte
{
    public const string Host = "sol.locale";
    public const string Origine = "https://" + Host;

    // Gli unici siti che la pagina può aprire nel browser: l'accesso Microsoft (Outlook) e le pagine Google
    // delle password per le app (Gmail). Nient'altro, nemmeno da un link finito per sbaglio nella pagina.
    private static readonly string[] DominiEsterni =
    {
        "microsoft.com", "microsoftonline.com", "live.com", "office.com",
        "myaccount.google.com", "support.google.com",
    };

    private readonly Motore.Motore motore;

    public Ponte(Motore.Motore motore) => this.motore = motore;

    /// <summary>Apre un indirizzo nel browser predefinito (già controllato con <see cref="ApribileFuori"/>).</summary>
    public Action<string>? ApriNelBrowser { get; set; }

    /// <summary>Apre la dettatura di Windows (Win+H) nella finestra di Sol: c'è solo mentre la finestra è aperta.</summary>
    public Action? Detta { get; set; }

    /// <summary>Il tema scelto nelle impostazioni (null = come Windows).</summary>
    public string? TemaScelto => motore.TemaScelto;

    /// <summary>Il tema scelto nelle impostazioni è cambiato.</summary>
    public event Action? TemaCambiato
    {
        add => motore.TemaCambiato += value;
        remove => motore.TemaCambiato -= value;
    }

    public static bool ÈInterno(string? indirizzo)
    {
        if (string.IsNullOrEmpty(indirizzo)) return false;
        if (indirizzo == "about:blank") return true;
        return Uri.TryCreate(indirizzo, UriKind.Absolute, out var u)
               && u.Scheme == Uri.UriSchemeHttps
               && string.Equals(u.Host, Host, StringComparison.OrdinalIgnoreCase)
               && u.IsDefaultPort;
    }

    /// <summary>Solo https e solo i domini di Microsoft e delle password per le app di Google.</summary>
    public static bool ApribileFuori(string? indirizzo)
    {
        if (!Uri.TryCreate(indirizzo, UriKind.Absolute, out var u) || u.Scheme != Uri.UriSchemeHttps || !string.IsNullOrEmpty(u.UserInfo)) return false;
        var host = u.Host.ToLowerInvariant();
        return DominiEsterni.Any(d => host == d || host.EndsWith("." + d, StringComparison.Ordinal));
    }

    public static bool ÈApi(Uri uri) =>
        uri.AbsolutePath.StartsWith("/locale/", StringComparison.Ordinal)
        || uri.AbsolutePath.StartsWith("/server/", StringComparison.Ordinal);

    /// <summary>
    /// Il file della cartella ui per un percorso della pagina ("/" = index.html), oppure null se il
    /// percorso prova a uscire dalla cartella o non è un nome di file pulito.
    /// </summary>
    public static string? FileDellInterfaccia(string cartellaUi, string percorsoUrl)
    {
        string relativo;
        try
        {
            relativo = Uri.UnescapeDataString(percorsoUrl).TrimStart('/');
        }
        catch (UriFormatException)
        {
            return null;
        }
        if (relativo.Length == 0) relativo = "index.html";
        if (relativo.Contains('\\') || relativo.Contains(':') || relativo.Contains('\0')) return null;
        if (relativo.Split('/').Any(p => p is "" or "." or "..")) return null;
        var radice = Path.GetFullPath(cartellaUi).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var completo = Path.GetFullPath(Path.Combine(radice, relativo.Replace('/', Path.DirectorySeparatorChar)));
        return completo.StartsWith(radice, StringComparison.OrdinalIgnoreCase) ? completo : null;
    }

    public static string TipoContenuto(string file) => Path.GetExtension(file).ToLowerInvariant() switch
    {
        ".html" or ".htm" => "text/html; charset=utf-8",
        ".js" or ".mjs" => "text/javascript; charset=utf-8",
        ".css" => "text/css; charset=utf-8",
        ".json" => "application/json; charset=utf-8",
        ".svg" => "image/svg+xml",
        ".png" => "image/png",
        ".ico" => "image/x-icon",
        ".woff2" => "font/woff2",
        ".woff" => "font/woff",
        ".ttf" => "font/ttf",
        ".txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    };

    public async Task<Motore.RispostaPonte> GestisciAsync(string metodo, Uri uri, string? corpo)
    {
        metodo = metodo.ToUpperInvariant();
        var percorso = uri.AbsolutePath;
        if (percorso.StartsWith("/locale/", StringComparison.Ordinal))
        {
            var (stato, o) = await LocaleAsync(metodo, percorso["/locale/".Length..].TrimEnd('/'), corpo).ConfigureAwait(false);
            return new Motore.RispostaPonte(stato, o.ToJsonString());
        }
        if (percorso.StartsWith("/server/", StringComparison.Ordinal))
        {
            return await motore.InoltraAsync(metodo, percorso["/server/".Length..], uri.Query, corpo).ConfigureAwait(false);
        }
        return new Motore.RispostaPonte(404, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta }.ToJsonString());
    }

    private async Task<(int, JsonObject)> LocaleAsync(string metodo, string nome, string? corpo)
    {
        var o = Json.Nodo(corpo) as JsonObject;
        (string Metodo, Func<Task<JsonObject>> Azione)? rotta = nome switch
        {
            "stato" => ("GET", () => Task.FromResult(motore.Stato())),
            "abbina" => ("POST", () => motore.AbbinaAsync(Json.Testo(o?["server"]), Json.Testo(o?["codice"]), Json.Testo(o?["nome"]), Json.Booleano(o?["avvio_al_login"]) ?? false)),
            "scollega" => ("POST", motore.ScollegaAsync),
            // La pagina dice quale versione della bozza ha mostrato (§15.11): se sul server è cambiata, niente conferma.
            "invia" => ("POST", () => motore.InviaBozzaAsync(Json.Intero(o?["bozza_id"]), Json.Intero(o?["versione"]))),
            "notifiche" => ("GET", () => Task.FromResult(motore.NotificheRecenti())),
            "avvio-login" => ("POST", () => Task.FromResult(motore.ImpostaAvvioAlLogin(Json.Booleano(o?["attivo"]) ?? false))),
            "tutorial-visto" => ("POST", () => Task.FromResult(motore.SegnaTutorialVisto())),
            "tema" => ("POST", () => Task.FromResult(motore.ImpostaTema(Json.Testo(o?["tema"])))),
            "detta" => ("POST", () =>
            {
                if (Detta == null) return Task.FromResult(new JsonObject { ["errore"] = "La dettatura si apre solo con la finestra di Sol aperta.", ["codice"] = "non_disponibile" });
                Detta();
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            "aggiorna-adesso" => ("POST", () =>
            {
                motore.Sveglia();
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            "scarica-aggiornamento" => ("POST", () => Task.FromResult(motore.AvviaScaricamento()
                ? new JsonObject { ["ok"] = true }
                : new JsonObject { ["errore"] = motore.Aggiornatore.Disponibile == null ? "Non c'è una versione nuova da scaricare." : "Lo scaricamento è già in corso.", ["codice"] = "non_disponibile" })),
            "apri" => ("POST", () =>
            {
                var url = Json.Testo(o?["url"]);
                if (!ApribileFuori(url) || ApriNelBrowser == null)
                {
                    return Task.FromResult(new JsonObject { ["errore"] = "Questo indirizzo non si può aprire da Sol.", ["codice"] = "non_valido" });
                }
                ApriNelBrowser(new Uri(url!).AbsoluteUri);
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            _ => null,
        };
        if (rotta == null) return (404, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta, ["codice"] = "non_trovato" });
        if (rotta.Value.Metodo != metodo) return (405, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta, ["codice"] = "non_valido" });
        var esito = await rotta.Value.Azione().ConfigureAwait(false);
        return (esito["errore"] != null && esito["annullato"] == null ? 422 : 200, esito);
    }
}
