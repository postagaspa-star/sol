global using System;
global using System.Collections.Generic;
global using System.IO;
global using System.Linq;
global using System.Threading;
global using System.Threading.Tasks;
global using Xunit;

using System.Net;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Text;

namespace Sol.Tests;

/// <summary>Una cartella temporanea che sparisce a fine test.</summary>
internal sealed class CartellaTemporanea : IDisposable
{
    public CartellaTemporanea()
    {
        Percorso = Path.Combine(Path.GetTempPath(), "sol-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Percorso);
    }

    public string Percorso { get; }

    public string File(string nome) => Path.Combine(Percorso, nome);

    public void Dispose()
    {
        try
        {
            Directory.Delete(Percorso, recursive: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }
}

/// <summary>
/// Il server finto: un <see cref="HttpMessageHandler"/> che risponde con <see cref="Risponde"/> e tiene le
/// richieste ricevute, in ordine. Stato 0 = "il server non risponde" (eccezione di rete).
/// </summary>
internal sealed class HandlerFinto : HttpMessageHandler
{
    private readonly List<Richiesta> ricevute = new();

    public HandlerFinto(Func<Richiesta, (int Stato, string Corpo)> risponde)
    {
        Risponde = risponde;
    }

    /// <param name="Percorso">Con la query: <c>/api/mail?limite=50</c>.</param>
    public sealed record Richiesta(string Metodo, string Percorso, string? Autorizzazione, string? Corpo, string? Agente);

    public Func<Richiesta, (int Stato, string Corpo)> Risponde { get; set; }

    /// <summary>Per i download: se c'è e restituisce dei byte, vale al posto di <see cref="Risponde"/>.</summary>
    public Func<Richiesta, byte[]?>? RispondeByte { get; set; }

    public IReadOnlyList<Richiesta> Ricevute
    {
        get { lock (ricevute) return ricevute.ToList(); }
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var corpo = request.Content == null ? null : await request.Content.ReadAsStringAsync(cancellationToken);
        var r = new Richiesta(request.Method.Method, request.RequestUri!.PathAndQuery, request.Headers.Authorization?.ToString(), corpo,
            request.Headers.UserAgent.ToString());
        lock (ricevute) ricevute.Add(r);
        if (RispondeByte?.Invoke(r) is byte[] dati)
        {
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(dati) };
        }
        var (stato, testo) = Risponde(r);
        if (stato == 0) throw new HttpRequestException("il server non risponde");
        return new HttpResponseMessage((HttpStatusCode)stato) { Content = new StringContent(testo, Encoding.UTF8, "application/json") };
    }
}

internal static class Sorgenti
{
    /// <summary>La cartella <c>pc</c> del repository, trovata dal percorso di questo file al momento della compilazione.</summary>
    public static string CartellaPc([CallerFilePath] string questoFile = "") =>
        Path.GetFullPath(Path.Combine(Path.GetDirectoryName(questoFile)!, ".."));
}
