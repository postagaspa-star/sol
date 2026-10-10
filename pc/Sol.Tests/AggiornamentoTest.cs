using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Sol.Interfaccia;
using Sol.Motore;
using Sol.Nucleo;
using Sol.Rete;

namespace Sol.Tests;

public class AggiornamentoTest
{
    private static readonly byte[] Zip = { 0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6 };
    private static readonly string ImprontaZip = Convert.ToHexString(SHA256.HashData(Zip)).ToLowerInvariant();

    private static string Versione(string? sha256 = null, long? byte_ = null, int codice = 2) =>
        "{\"server\": \"1.0.0\", \"pc\": {\"codice\": " + codice + ", \"nome\": \"1.1.0\""
        + (sha256 != null ? ", \"sha256\": \"" + sha256 + "\"" : "")
        + (byte_ != null ? ", \"byte\": " + byte_ : "") + "}}";

    private static (HandlerFinto, ClientSol) Server(string versione, byte[]? zip = null)
    {
        var finto = new HandlerFinto(r => r.Percorso == "/api/versione" ? (200, versione) : (404, "{}"))
        {
            RispondeByte = r => r.Percorso == "/scarica/sol-pc.zip" ? zip ?? Zip : null,
        };
        return (finto, new ClientSol(finto));
    }

    [Theory]
    [InlineData(2, 1, true)]
    [InlineData(1, 1, false)]
    [InlineData(0, 1, false)]
    public void Piu_nuova_solo_col_codice_piu_alto(int server, int mio, bool atteso)
    {
        var v = new InfoVersione { Pc = new VersioneApp { Codice = server, Nome = "x" } };
        Assert.Equal(atteso, Aggiornatore.ÈPiùNuova(v, mio));
        Assert.False(Aggiornatore.ÈPiùNuova(new InfoVersione(), mio));
        Assert.False(Aggiornatore.ÈPiùNuova(null, mio));
    }

    [Fact]
    public async Task Versione_nuova_si_segnala_una_volta_con_impronta_e_indirizzo_dello_zip()
    {
        var (finto, client) = Server(Versione(ImprontaZip, Zip.Length));
        using var _ = client;
        var agg = new Aggiornatore(client, 1);

        var prima = await agg.ControllaAsync("https://sol.esempio.ts.net/");
        var seconda = await agg.ControllaAsync("https://sol.esempio.ts.net/");

        Assert.NotNull(prima);
        Assert.Equal("1.1.0", prima!.Nome);
        Assert.Equal("https://sol.esempio.ts.net/scarica/sol-pc.zip", prima.IndirizzoZip);
        Assert.Equal(ImprontaZip, prima.Sha256);
        Assert.Equal(Zip.Length, prima.Byte);
        Assert.Null(seconda); // niente notifica ripetuta a ogni giro
        Assert.Equal(prima, agg.Disponibile);
        Assert.All(finto.Ricevute, r => Assert.Null(r.Autorizzazione)); // la versione si chiede senza token
        Assert.DoesNotContain(finto.Ricevute, r => r.Percorso.StartsWith("/scarica", StringComparison.Ordinal)); // niente download da solo
    }

    [Fact]
    public async Task Zip_con_l_impronta_giusta_si_tiene_nella_cartella_download()
    {
        using var cartella = new CartellaTemporanea();
        var (_, client) = Server(Versione(ImprontaZip.ToUpperInvariant(), Zip.Length));
        using var __ = client;
        var agg = new Aggiornatore(client, 1);
        await agg.ControllaAsync(Banco.Server);

        var esito = await agg.ScaricaAsync(cartella.Percorso);

        Assert.True(esito.Ok, esito.Errore);
        Assert.Equal(Path.Combine(cartella.Percorso, "sol-pc-1.1.0.zip"), esito.File);
        Assert.Equal(Zip, File.ReadAllBytes(esito.File!));
        Assert.Single(Directory.GetFiles(cartella.Percorso)); // niente file provvisori lasciati in giro
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    public async Task Zip_con_impronta_o_dimensione_sbagliata_si_scarta(bool improntaSbagliata, bool dimensioneSbagliata)
    {
        using var cartella = new CartellaTemporanea();
        var sha = improntaSbagliata ? new string('a', 64) : ImprontaZip;
        var (_, client) = Server(Versione(sha, dimensioneSbagliata ? Zip.Length + 1 : Zip.Length));
        using var __ = client;
        var agg = new Aggiornatore(client, 1);
        await agg.ControllaAsync(Banco.Server);

        var esito = await agg.ScaricaAsync(cartella.Percorso);

        Assert.False(esito.Ok);
        Assert.Contains("scartato", esito.Errore);
        Assert.Empty(Directory.GetFiles(cartella.Percorso));
    }

    [Fact]
    public async Task Senza_impronta_dal_server_non_si_scarica_niente()
    {
        using var cartella = new CartellaTemporanea();
        var (finto, client) = Server(Versione());
        using var __ = client;
        var agg = new Aggiornatore(client, 1);
        await agg.ControllaAsync(Banco.Server);

        var esito = await agg.ScaricaAsync(cartella.Percorso);

        Assert.False(esito.Ok);
        Assert.Contains("impronta", esito.Errore);
        Assert.DoesNotContain(finto.Ricevute, r => r.Percorso.StartsWith("/scarica", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Stessa_versione_niente_segnalazione_ne_scaricamento()
    {
        using var cartella = new CartellaTemporanea();
        var (_, client) = Server("{\"pc\": {\"codice\": 1, \"nome\": \"1.0.0\"}}");
        using var __ = client;
        var agg = new Aggiornatore(client, 1);

        Assert.Null(await agg.ControllaAsync(Banco.Server));
        Assert.Null(agg.Disponibile);
        Assert.Equal("1.0.0", agg.Annunciata!.Nome);
        Assert.False((await agg.ScaricaAsync(cartella.Percorso)).Ok);
    }

    [Fact]
    public async Task Server_che_non_risponde_non_cambia_niente()
    {
        var finto = new HandlerFinto(_ => (0, ""));
        using var client = new ClientSol(finto);
        var agg = new Aggiornatore(client, 1);
        Assert.Null(await agg.ControllaAsync(Banco.Server));
        Assert.Null(agg.Disponibile);
    }

    [Fact]
    public async Task Dalla_pagina_lo_scaricamento_parte_solo_se_c_e_una_versione_nuova_e_finisce_con_l_esito()
    {
        using var cartella = new CartellaTemporanea();
        using var banco = new Banco(r => r.Percorso == "/api/versione" ? (200, Versione(ImprontaZip, Zip.Length, codice: 5)) : (404, "{}"));
        banco.Finto.RispondeByte = r => r.Percorso == "/scarica/sol-pc.zip" ? Zip : null;
        banco.Motore.CartellaDownload = cartella.Percorso;
        var finito = new TaskCompletionSource<EsitoScaricamento>();
        banco.Motore.ScaricamentoFinito += e => finito.TrySetResult(e);
        var ponte = new Ponte(banco.Motore);

        var prima = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/scarica-aggiornamento"), "{}");
        Assert.Equal(422, prima.Stato);

        await banco.Motore.Aggiornatore.ControllaAsync(Banco.Server);
        var dopo = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/scarica-aggiornamento"), "{}");
        Assert.Equal(200, dopo.Stato);
        var esito = await finito.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(esito.Ok, esito.Errore);
        var stato = JsonNode.Parse(banco.Motore.Stato().ToJsonString())!["aggiornamento"]!;
        Assert.Equal("1.1.0", stato["disponibile"]!.GetValue<string>());
        Assert.Equal(esito.File, stato["scaricato"]!.GetValue<string>());
        Assert.False(stato["scaricamento_in_corso"]!.GetValue<bool>());
    }

    [Fact]
    public void Il_controllo_dell_impronta_da_solo()
    {
        using var cartella = new CartellaTemporanea();
        var file = cartella.File("x.zip");
        File.WriteAllBytes(file, Zip);
        Assert.Null(Aggiornatore.Controlla(file, ImprontaZip, Zip.Length));
        Assert.Null(Aggiornatore.Controlla(file, ImprontaZip, null));
        Assert.Equal("l'impronta non coincide", Aggiornatore.Controlla(file, new string('0', 64), Zip.Length));
        Assert.Equal("la dimensione non coincide", Aggiornatore.Controlla(file, ImprontaZip, 3));
    }
}
