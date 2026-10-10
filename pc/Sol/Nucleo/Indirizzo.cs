using System.Net;
using System.Net.Sockets;

namespace Sol.Nucleo;

/// <summary>L'indirizzo del server come lo scrive l'utente, ridotto alla base (<c>https://host[:porta]</c>).</summary>
public static class Indirizzo
{
    public const string Esempio = "https://sol.esempio.ts.net";
    public const string FraseSoloHttps =
        "Fuori dalla rete di casa serve un indirizzo sicuro, che comincia con https: usa quello di Tailscale, per esempio " + Esempio;

    /// <summary>
    /// Toglie spazi, barre finali e un <c>/api</c> finale. Senza schema: <c>http://</c> per un indirizzo della rete
    /// di casa scritto in numeri (come <c>192.168.1.51:8200</c>), <c>https://</c> per tutto il resto.
    /// <c>http://</c> vale solo per gli indirizzi privati di casa (§15.13): 192.168.x.x, 10.x.x.x, 172.16-31.x.x.
    /// </summary>
    public static bool Normalizza(string? scritto, out string baseServer, out string? errore)
    {
        baseServer = "";
        errore = null;
        var t = (scritto ?? "").Trim();
        if (t.Length == 0)
        {
            errore = $"Scrivi l'indirizzo del server, per esempio {Esempio}";
            return false;
        }
        if (!t.Contains("://", StringComparison.Ordinal))
        {
            var host = t.Split('/', 2)[0].Split(':')[0];
            t = (DiCasa(host) ? "http://" : "https://") + t;
        }
        if (!Uri.TryCreate(t, UriKind.Absolute, out var u) || (u.Scheme != Uri.UriSchemeHttp && u.Scheme != Uri.UriSchemeHttps) || string.IsNullOrEmpty(u.Host)
            || !string.IsNullOrEmpty(u.Query) || !string.IsNullOrEmpty(u.Fragment) || !string.IsNullOrEmpty(u.UserInfo))
        {
            errore = $"L'indirizzo non sembra giusto. Deve essere simile a {Esempio} oppure, in casa, http://192.168.1.51:8200";
            return false;
        }
        if (u.Scheme == Uri.UriSchemeHttp && !DiCasa(u.Host))
        {
            errore = FraseSoloHttps;
            return false;
        }
        var percorso = u.AbsolutePath.TrimEnd('/');
        if (percorso.EndsWith("/api", StringComparison.OrdinalIgnoreCase)) percorso = percorso[..^4];
        baseServer = u.GetLeftPart(UriPartial.Authority) + percorso;
        return true;
    }

    /// <summary>Un indirizzo IPv4 della rete privata di casa: 10.x.x.x, 172.16-31.x.x, 192.168.x.x.</summary>
    public static bool DiCasa(string? host)
    {
        if (!IPAddress.TryParse(host, out var ip) || ip.AddressFamily != AddressFamily.InterNetwork) return false;
        if (host!.Count(c => c == '.') != 3) return false; // "10" o "10.1" non sono indirizzi scritti per intero
        var b = ip.GetAddressBytes();
        return b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] >= 16 && b[1] <= 31);
    }

    /// <summary>Il collegamento passa in chiaro (http): si può usare in casa, ma mai per mandare password.</summary>
    public static bool SenzaProtezione(string? baseServer) =>
        baseServer != null && baseServer.StartsWith("http://", StringComparison.OrdinalIgnoreCase);
}
