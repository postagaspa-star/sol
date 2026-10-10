using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace Sol.Rete;

/// <summary>Una risposta del server. <see cref="Stato"/> 0 = il server non ha risposto (rete, tempo scaduto).</summary>
public readonly record struct Risposta(int Stato, string Corpo)
{
    public bool Ok => Stato is >= 200 and < 300;
    public bool Rete => Stato == 0;
    public static Risposta SenzaRete => new(0, "");
}

/// <summary>
/// Il client HTTP verso il server di Sol. Le letture riusano le connessioni; le modifiche
/// (POST, PUT, PATCH, DELETE) partono su una connessione nuova chiusa subito, così .NET non le
/// ripete da solo su una connessione riusata: un invio di mail non deve mai partire due volte.
/// Nei test si passa un handler finto, che fa da server.
/// </summary>
public sealed class ClientSol : IDisposable
{
    public static readonly TimeSpan TempoMassimo = TimeSpan.FromSeconds(25);

    private readonly HttpClient letture;
    private readonly HttpClient modifiche;
    private readonly bool stessoClient;

    public ClientSol(HttpMessageHandler? handlerFinto = null)
    {
        if (handlerFinto != null)
        {
            letture = modifiche = new HttpClient(handlerFinto, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };
            stessoClient = true;
        }
        else
        {
            letture = new HttpClient(new SocketsHttpHandler
            {
                ConnectTimeout = TimeSpan.FromSeconds(10),
                PooledConnectionLifetime = TimeSpan.FromMinutes(5),
                // uvicorn chiude le connessioni ferme dopo 5 secondi: si lasciano prima.
                PooledConnectionIdleTimeout = TimeSpan.FromSeconds(2),
            })
            { Timeout = Timeout.InfiniteTimeSpan };
            modifiche = new HttpClient(new SocketsHttpHandler
            {
                ConnectTimeout = TimeSpan.FromSeconds(10),
                PooledConnectionLifetime = TimeSpan.Zero,
            })
            { Timeout = Timeout.InfiniteTimeSpan };
        }
        foreach (var c in stessoClient ? new[] { letture } : new[] { letture, modifiche })
        {
            c.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Sol-PC", Versione.Nome));
        }
    }

    /// <summary>
    /// Una richiesta: <paramref name="percorso"/> relativo alla base del server (es. <c>api/mail?limite=50</c>).
    /// Mai eccezioni: un problema di rete è <see cref="Risposta.SenzaRete"/>.
    /// </summary>
    public async Task<Risposta> InviaAsync(string metodo, string server, string percorso, string? token, string? corpoJson,
        TimeSpan? tempoMassimo = null, CancellationToken annulla = default)
    {
        if (!Uri.TryCreate(server.TrimEnd('/') + "/" + percorso.TrimStart('/'), UriKind.Absolute, out var uri)) return Risposta.SenzaRete;
        var metodoHttp = new HttpMethod(metodo.ToUpperInvariant());
        using var richiesta = new HttpRequestMessage(metodoHttp, uri);
        bool modifica = metodoHttp != HttpMethod.Get && metodoHttp != HttpMethod.Head;
        if (modifica) richiesta.Headers.ConnectionClose = true;
        if (token != null) richiesta.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        richiesta.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (corpoJson != null) richiesta.Content = new StringContent(corpoJson, Encoding.UTF8, "application/json");

        using var scadenza = CancellationTokenSource.CreateLinkedTokenSource(annulla);
        scadenza.CancelAfter(tempoMassimo ?? TempoMassimo);
        try
        {
            using var risposta = await (modifica ? modifiche : letture).SendAsync(richiesta, HttpCompletionOption.ResponseContentRead, scadenza.Token).ConfigureAwait(false);
            var corpo = await risposta.Content.ReadAsStringAsync(scadenza.Token).ConfigureAwait(false);
            return new Risposta((int)risposta.StatusCode, corpo);
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or IOException)
        {
            return Risposta.SenzaRete;
        }
    }

    /// <summary>Scarica un file (lo zip dell'aggiornamento) in <paramref name="destinazione"/>. false se non riesce.</summary>
    public async Task<bool> ScaricaAsync(string url, string destinazione, CancellationToken annulla = default)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        using var scadenza = CancellationTokenSource.CreateLinkedTokenSource(annulla);
        scadenza.CancelAfter(TimeSpan.FromMinutes(10));
        var parziale = destinazione + ".part";
        try
        {
            using var risposta = await letture.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, scadenza.Token).ConfigureAwait(false);
            if (!risposta.IsSuccessStatusCode) return false;
            Directory.CreateDirectory(Path.GetDirectoryName(destinazione)!);
            await using (var file = new FileStream(parziale, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                await risposta.Content.CopyToAsync(file, scadenza.Token).ConfigureAwait(false);
            }
            File.Move(parziale, destinazione, overwrite: true);
            return true;
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or IOException or UnauthorizedAccessException)
        {
            try { File.Delete(parziale); } catch (Exception) { }
            return false;
        }
    }

    public void Dispose()
    {
        letture.Dispose();
        if (!stessoClient) modifiche.Dispose();
    }
}
