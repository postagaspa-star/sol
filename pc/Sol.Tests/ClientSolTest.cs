using Sol.Rete;

namespace Sol.Tests;

public class ClientSolTest
{
    [Fact]
    public async Task Get_con_token_porta_intestazioni_e_percorso_giusti()
    {
        var finto = new HandlerFinto(_ => (200, "{\"ok\":true}"));
        using var client = new ClientSol(finto);

        var r = await client.InviaAsync("GET", "https://sol.esempio.ts.net/", "/api/mail?limite=50", "token-di-prova", null);

        Assert.True(r.Ok);
        Assert.Equal("{\"ok\":true}", r.Corpo);
        var richiesta = Assert.Single(finto.Ricevute);
        Assert.Equal("GET", richiesta.Metodo);
        Assert.Equal("/api/mail?limite=50", richiesta.Percorso);
        Assert.Equal("Bearer token-di-prova", richiesta.Autorizzazione);
        Assert.Contains("Sol-PC/" + Versione.Nome, richiesta.Agente);
        Assert.Null(richiesta.Corpo);
    }

    [Fact]
    public async Task Post_manda_il_corpo_json_e_senza_token_niente_autorizzazione()
    {
        var finto = new HandlerFinto(_ => (201, "{}"));
        using var client = new ClientSol(finto);

        var r = await client.InviaAsync("POST", "http://192.168.1.51:8200", "api/abbina", null, "{\"codice\":\"12345678\"}");

        Assert.Equal(201, r.Stato);
        var richiesta = Assert.Single(finto.Ricevute);
        Assert.Equal("POST", richiesta.Metodo);
        Assert.Null(richiesta.Autorizzazione);
        Assert.Equal("{\"codice\":\"12345678\"}", richiesta.Corpo);
    }

    [Fact]
    public async Task Server_che_non_risponde_diventa_stato_zero_senza_eccezioni()
    {
        var finto = new HandlerFinto(_ => (0, ""));
        using var client = new ClientSol(finto);

        var r = await client.InviaAsync("GET", "https://sol.esempio.ts.net", "api/salute", "t", null);

        Assert.True(r.Rete);
        Assert.False(r.Ok);
    }

    [Fact]
    public async Task Tempo_scaduto_diventa_stato_zero()
    {
        var finto = new HandlerFinto(_ =>
        {
            Thread.Sleep(600);
            return (200, "{}");
        });
        using var client = new ClientSol(new RitardoHandler(finto));

        var r = await client.InviaAsync("GET", "https://sol.esempio.ts.net", "api/salute", "t", null, TimeSpan.FromMilliseconds(100));

        Assert.True(r.Rete);
    }

    [Fact]
    public async Task Gli_errori_del_server_arrivano_con_stato_e_corpo()
    {
        var finto = new HandlerFinto(_ => (422, "{\"errore\":\"Google ha rifiutato la password per le app.\",\"codice\":\"non_valido\"}"));
        using var client = new ClientSol(finto);

        var r = await client.InviaAsync("POST", "https://sol.esempio.ts.net", "api/caselle", "t", "{}");

        Assert.Equal(422, r.Stato);
        Assert.Contains("Google ha rifiutato", r.Corpo);
    }

    [Fact]
    public async Task Indirizzo_non_valido_non_lancia()
    {
        using var client = new ClientSol(new HandlerFinto(_ => (200, "{}")));
        var r = await client.InviaAsync("GET", "non è un indirizzo", "api/salute", null, null);
        Assert.True(r.Rete);
    }

    [Fact]
    public async Task Scarica_scrive_il_file_e_su_errore_non_lascia_niente()
    {
        using var cartella = new CartellaTemporanea();
        var finto = new HandlerFinto(r => r.Percorso.EndsWith("manca.zip") ? (404, "{}") : (200, "{}"))
        {
            RispondeByte = r => r.Percorso.EndsWith("sol-pc.zip") ? new byte[] { 1, 2, 3 } : null,
        };
        using var client = new ClientSol(finto);

        Assert.True(await client.ScaricaAsync("https://sol.esempio.ts.net/scarica/sol-pc.zip", cartella.File("a.zip")));
        Assert.Equal(new byte[] { 1, 2, 3 }, File.ReadAllBytes(cartella.File("a.zip")));

        Assert.False(await client.ScaricaAsync("https://sol.esempio.ts.net/scarica/manca.zip", cartella.File("b.zip")));
        Assert.False(File.Exists(cartella.File("b.zip")));
        Assert.False(File.Exists(cartella.File("b.zip.part")));
    }

    /// <summary>Fa scattare davvero l'annullamento: il finto server dorme, il client smette di aspettare.</summary>
    private sealed class RitardoHandler : System.Net.Http.DelegatingHandler
    {
        public RitardoHandler(System.Net.Http.HttpMessageHandler interno) : base(interno) { }

        protected override async Task<System.Net.Http.HttpResponseMessage> SendAsync(System.Net.Http.HttpRequestMessage request, CancellationToken cancellationToken)
        {
            await Task.Delay(500, cancellationToken);
            return await base.SendAsync(request, cancellationToken);
        }
    }
}
