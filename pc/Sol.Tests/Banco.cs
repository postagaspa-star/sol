using Sol.Motore;
using Sol.Nucleo;
using Sol.Rete;
using Sol.Sistema;

namespace Sol.Tests;

/// <summary>Un motore già abbinato a un server finto, su una cartella temporanea.</summary>
internal sealed class Banco : IDisposable
{
    public const string Server = "https://sol.esempio.ts.net";
    public const string Token = "token-finto-di-prova-0123456789";

    private readonly CartellaTemporanea cartella = new();
    private readonly ClientSol client;

    public Banco(Func<HandlerFinto.Richiesta, (int, string)> risponde, bool abbinato = true, string server = Server)
    {
        Finto = new HandlerFinto(risponde);
        Percorsi = new Percorsi(cartella.Percorso);
        if (abbinato)
        {
            Archivio.ScriviJson(Percorsi.Config, new Configurazione
            {
                Server = server,
                TokenProtetto = Dpapi.Proteggi(Token),
                DispositivoId = 7,
                NomeDispositivo = "Computer dello studio",
            });
        }
        client = new ClientSol(Finto);
        Motore = new Motore.Motore(Percorsi, client, () => Adesso);
    }

    public HandlerFinto Finto { get; }
    public Percorsi Percorsi { get; }
    public Motore.Motore Motore { get; }
    public DateTimeOffset Adesso { get; set; } = new(2026, 10, 10, 9, 30, 0, TimeSpan.FromHours(2));

    public IEnumerable<HandlerFinto.Richiesta> Richieste(string metodo, string inizioPercorso) =>
        Finto.Ricevute.Where(r => r.Metodo == metodo && r.Percorso.StartsWith(inizioPercorso, StringComparison.Ordinal));

    public void Dispose()
    {
        Motore.Dispose();
        client.Dispose();
        cartella.Dispose();
    }
}
