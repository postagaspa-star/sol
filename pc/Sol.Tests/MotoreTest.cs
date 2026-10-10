using System.Text.Json.Nodes;
using Sol.Motore;
using Sol.Nucleo;
using Sol.Sistema;

namespace Sol.Tests;

public class MotoreTest
{
    [Fact]
    public async Task Inoltra_col_token_e_la_query_della_pagina()
    {
        using var banco = new Banco(_ => (200, "{\"mail\": [], \"altre\": false}"));

        var r = await banco.Motore.InoltraAsync("GET", "api/mail", "?gruppo_id=2&cerca=Rossi&limite=50", null);

        Assert.Equal(200, r.Stato);
        Assert.Null(r.DaMemoriaDel);
        var richiesta = Assert.Single(banco.Finto.Ricevute);
        Assert.Equal("/api/mail?gruppo_id=2&cerca=Rossi&limite=50", richiesta.Percorso);
        Assert.Equal("Bearer " + Banco.Token, richiesta.Autorizzazione);
    }

    [Fact]
    public async Task Server_irraggiungibile_le_letture_tornano_dagli_ultimi_dati_visti()
    {
        bool rete = true;
        using var banco = new Banco(_ => rete ? (200, "{\"mail\": [{\"id\": 812, \"oggetto\": \"Offerta capannone Bassano\"}], \"altre\": false}") : (0, ""));
        await banco.Motore.InoltraAsync("GET", "api/mail", "?limite=50", null);
        rete = false;

        var r = await banco.Motore.InoltraAsync("GET", "api/mail", "?limite=50", null);

        Assert.Equal(200, r.Stato);
        Assert.Equal(banco.Adesso, r.DaMemoriaDel);
        Assert.Contains("Offerta capannone Bassano", r.Json);
        Assert.False(banco.Motore.ServerRaggiungibile);
    }

    [Fact]
    public async Task Gli_ultimi_dati_visti_sul_disco_sono_cifrati()
    {
        using var banco = new Banco(_ => (200, "{\"mail\": [{\"id\": 812, \"oggetto\": \"Offerta capannone Bassano\"}]}"));
        await banco.Motore.InoltraAsync("GET", "api/mail", "?limite=50", null);

        var file = Assert.Single(Directory.GetFiles(banco.Percorsi.Memoria));
        Assert.DoesNotContain("capannone", File.ReadAllText(file));
    }

    [Fact]
    public async Task Server_irraggiungibile_senza_copia_o_per_una_modifica_lo_dice_in_chiaro()
    {
        using var banco = new Banco(_ => (0, ""));

        var lettura = await banco.Motore.InoltraAsync("GET", "api/scadenze", "?stato=aperta", null);
        var modifica = await banco.Motore.InoltraAsync("POST", "api/mail/812/sposta", null, "{\"gruppo_id\": 2}");

        foreach (var r in new[] { lettura, modifica })
        {
            Assert.Equal(503, r.Stato);
            var o = JsonNode.Parse(r.Json)!;
            Assert.Equal(Frasi.ServerIrraggiungibile, o["errore"]!.GetValue<string>());
            Assert.Equal("rete", o["codice"]!.GetValue<string>());
        }
    }

    [Fact]
    public async Task Pagina_html_di_errore_diventa_una_frase_leggibile()
    {
        using var banco = new Banco(_ => (502, "<html><body>502 Bad Gateway</body></html>"));

        var r = await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);

        Assert.Equal(502, r.Stato);
        var errore = JsonNode.Parse(r.Json)!["errore"]!.GetValue<string>();
        Assert.DoesNotContain("502", errore);
        Assert.DoesNotContain("Gateway", errore);
        Assert.Contains("server di casa", errore);
    }

    [Fact]
    public async Task La_frase_del_server_passa_alla_pagina_insieme_agli_altri_campi()
    {
        using var banco = new Banco(_ => (422, "{\"errore\": \"Google ha rifiutato la password per le app.\", \"codice\": \"non_valido\"}"));

        var r = await banco.Motore.InoltraAsync("POST", "api/caselle", null, "{\"tipo\":\"gmail\"}");

        var o = JsonNode.Parse(r.Json)!;
        Assert.Equal(422, r.Stato);
        Assert.Equal("Google ha rifiutato la password per le app.", o["errore"]!.GetValue<string>());
        Assert.Equal("non_valido", o["codice"]!.GetValue<string>());
    }

    [Fact]
    public async Task Token_rifiutato_segna_il_computer_come_non_riconosciuto()
    {
        bool rifiuta = true;
        using var banco = new Banco(_ => rifiuta ? (401, "{\"errore\": \"Token non valido\", \"codice\": \"non_autorizzato\"}") : (200, "[]"));

        await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        Assert.True(banco.Motore.NonRiconosciuto);
        Assert.True(banco.Motore.Stato()["non_riconosciuto"]!.GetValue<bool>());

        rifiuta = false;
        await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        Assert.False(banco.Motore.NonRiconosciuto);
    }

    [Theory]
    [InlineData("api/abbina")]
    [InlineData("api/../scarica/sol-pc.zip")]
    [InlineData("scarica/sol-pc.zip")]
    [InlineData("api//mail")]
    [InlineData("api/mail%2F..%2F")]
    public async Task Percorsi_fuori_dall_accordo_non_arrivano_al_server(string percorso)
    {
        using var banco = new Banco(_ => (200, "{}"));
        var r = await banco.Motore.InoltraAsync("GET", percorso, null, null);
        Assert.Equal(404, r.Stato);
        Assert.Empty(banco.Finto.Ricevute);
    }

    [Fact]
    public async Task Senza_abbinamento_niente_inoltro()
    {
        using var banco = new Banco(_ => (200, "{}"), abbinato: false);
        var r = await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        Assert.Equal(401, r.Stato);
        Assert.Equal(Frasi.NonCollegato, JsonNode.Parse(r.Json)!["errore"]!.GetValue<string>());
        Assert.Empty(banco.Finto.Ricevute);
    }

    [Fact]
    public async Task Abbinamento_riuscito_salva_il_token_solo_cifrato()
    {
        var token = new string('f', 64);
        using var banco = new Banco(r => r.Percorso == "/api/abbina" ? (200, "{\"token\": \"" + token + "\", \"dispositivo_id\": 3}") : (200, "{}"), abbinato: false);

        var esito = await banco.Motore.AbbinaAsync(" https://sol.esempio.ts.net/api/ ", "4821 3377", "Computer dello studio", avvioAlLogin: true);

        Assert.True(esito["ok"]?.GetValue<bool>());
        var richiesta = Assert.Single(banco.Finto.Ricevute);
        Assert.Equal("POST", richiesta.Metodo);
        Assert.Null(richiesta.Autorizzazione);
        var corpo = JsonNode.Parse(richiesta.Corpo!)!;
        Assert.Equal("48213377", corpo["codice"]!.GetValue<string>());
        Assert.Equal("pc", corpo["tipo"]!.GetValue<string>());
        Assert.Equal("Computer dello studio", corpo["nome"]!.GetValue<string>());

        var config = File.ReadAllText(banco.Percorsi.Config);
        Assert.DoesNotContain(token, config);
        Assert.Contains("https://sol.esempio.ts.net", config);
        var salvata = Archivio.LeggiJson<Configurazione>(banco.Percorsi.Config)!;
        Assert.Equal(token, Dpapi.Svela(salvata.TokenProtetto));
        Assert.Equal(3, salvata.DispositivoId);
        Assert.True(salvata.AvvioAlLogin);
        Assert.True(banco.Motore.Abbinato);
    }

    [Theory]
    [InlineData("1234", "8 cifre")]
    [InlineData("", "8 cifre")]
    public async Task Abbinamento_col_codice_corto_non_chiama_il_server(string codice, string frase)
    {
        using var banco = new Banco(_ => (200, "{}"), abbinato: false);
        var esito = await banco.Motore.AbbinaAsync(Banco.Server, codice, null, false);
        Assert.Contains(frase, esito["errore"]!.GetValue<string>());
        Assert.Empty(banco.Finto.Ricevute);
    }

    [Theory]
    [InlineData(401, "scaduto")]
    [InlineData(429, "quarto d'ora")]
    [InlineData(0, "raggiungere il server")]
    public async Task Abbinamento_rifiutato_spiega_perche(int stato, string frase)
    {
        using var banco = new Banco(_ => (stato, "{\"errore\": \"x\"}"), abbinato: false);
        var esito = await banco.Motore.AbbinaAsync(Banco.Server, "48213377", null, false);
        Assert.Contains(frase, esito["errore"]!.GetValue<string>());
        Assert.False(banco.Motore.Abbinato);
    }

    [Fact]
    public async Task Scollega_revoca_sul_server_e_dimentica_tutto()
    {
        using var banco = new Banco(_ => (200, "{\"mail\": []}"));
        await banco.Motore.InoltraAsync("GET", "api/mail", null, null);
        Assert.True(Directory.Exists(banco.Percorsi.Memoria));

        await banco.Motore.ScollegaAsync();

        Assert.Contains(banco.Finto.Ricevute, r => r.Metodo == "DELETE" && r.Percorso == "/api/dispositivi/7");
        Assert.False(banco.Motore.Abbinato);
        Assert.False(Directory.Exists(banco.Percorsi.Memoria));
        Assert.Null(Archivio.LeggiJson<Configurazione>(banco.Percorsi.Config)!.TokenProtetto);
    }

    [Fact]
    public void Token_che_non_si_decifra_riporta_all_abbinamento()
    {
        using var cartella = new CartellaTemporanea();
        var percorsi = new Percorsi(cartella.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione { Server = Banco.Server, TokenProtetto = "bm9uLWNpZnJhdG8=" });
        using var motore = new Motore.Motore(percorsi, new Rete.ClientSol(new HandlerFinto(_ => (200, "{}"))));
        Assert.False(motore.Abbinato);
    }

    [Fact]
    public void Avvio_al_login_senza_programma_installato_lo_dice()
    {
        using var banco = new Banco(_ => (200, "{}"));
        var esito = banco.Motore.ImpostaAvvioAlLogin(true);
        Assert.Contains("installato", esito["errore"]!.GetValue<string>());

        bool? applicato = null;
        banco.Motore.ApplicaAvvioLogin = v => applicato = v;
        var ok = banco.Motore.ImpostaAvvioAlLogin(false);
        Assert.True(ok["ok"]?.GetValue<bool>());
        Assert.False(applicato);
    }
}
