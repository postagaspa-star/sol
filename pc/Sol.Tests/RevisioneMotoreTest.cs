using System.Text.Json.Nodes;
using Sol.Interfaccia;
using Sol.Motore;
using Sol.Nucleo;

namespace Sol.Tests;

/// <summary>
/// I difetti del motore trovati dalla revisione (prove in sol-studio/revisione-pc/cs) e le regole nuove del
/// contratto v1.1 che riguardano il programma: ognuno di questi test falliva con il motore di prima.
/// </summary>
public class RevisioneMotoreTest
{
    private const string Revocato = "{\"errore\": \"Questo dispositivo non è abbinato o il suo accesso è stato revocato.\", \"codice\": \"non_autorizzato\"}";

    [Fact]
    public async Task Salute_200_col_token_revocato_non_fa_credere_di_essere_riconosciuti()
    {
        // Prova 2 del revisore: /api/salute risponde 200 anche senza token valido (§15.12).
        using var banco = new Banco(r => r.Percorso == "/api/salute" ? (200, "{\"ok\": true, \"versione\": \"1.0.0\"}") : (401, Revocato));

        await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        Assert.True(banco.Motore.NonRiconosciuto);
        await banco.Motore.InoltraAsync("GET", "api/salute", null, null);

        Assert.True(banco.Motore.NonRiconosciuto, "un 200 di /api/salute senza autenticato non prova niente");
        Assert.True(banco.Motore.Stato()["non_riconosciuto"]!.GetValue<bool>());
    }

    [Fact]
    public async Task Salute_con_autenticato_false_segna_il_computer_come_non_riconosciuto()
    {
        using var banco = new Banco(_ => (200, "{\"ok\": true, \"versione\": \"1.0.0\", \"autenticato\": false}"));
        await banco.Motore.InoltraAsync("GET", "api/salute", null, null);
        Assert.True(banco.Motore.NonRiconosciuto);
    }

    [Fact]
    public async Task Salute_con_autenticato_true_toglie_il_problema()
    {
        bool revocato = true;
        using var banco = new Banco(r => r.Percorso == "/api/salute" ? (200, "{\"ok\": true, \"autenticato\": " + (revocato ? "false" : "true") + "}") : (200, "[]"));
        await banco.Motore.InoltraAsync("GET", "api/salute", null, null);
        Assert.True(banco.Motore.NonRiconosciuto);
        revocato = false;
        await banco.Motore.InoltraAsync("GET", "api/salute", null, null);
        Assert.False(banco.Motore.NonRiconosciuto);
    }

    [Fact]
    public async Task Sveglia_ripetuta_non_mette_in_fila_decine_di_giri()
    {
        // Prova 5 del revisore: F5 tenuto premuto = ~30 Sveglia al secondo.
        using var banco = new Banco(r => r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal) ? (200, "{\"cursore\": \"10\"}") : (200, "{\"urgenti\": 0}"));
        banco.Motore.Avvia();
        for (int i = 0; i < 100 && banco.Motore.GiriFatti < 1; i++) await Task.Delay(100);
        Assert.Equal(1, banco.Motore.GiriFatti);

        for (int i = 0; i < 30; i++) banco.Motore.Sveglia();
        await Task.Delay(1500);

        Assert.Equal(2, banco.Motore.GiriFatti);
    }

    [Fact]
    public async Task Scollega_a_server_spento_dice_che_sul_server_il_computer_resta_collegato()
    {
        // Prova 6 del revisore.
        using var banco = new Banco(_ => (0, ""));

        var esito = await banco.Motore.ScollegaAsync();

        Assert.True(esito["ok"]!.GetValue<bool>());
        Assert.False(esito["revocato_sul_server"]!.GetValue<bool>());
        Assert.Contains("risulta ancora collegato", esito["avviso"]!.GetValue<string>());
        Assert.False(banco.Motore.Abbinato, "qui si scollega comunque");
    }

    [Theory]
    [InlineData(200)]
    [InlineData(401)]
    [InlineData(404)]
    public async Task Scollega_con_revoca_confermata_niente_avviso(int stato)
    {
        using var banco = new Banco(_ => (stato, "{}"));
        var esito = await banco.Motore.ScollegaAsync();
        Assert.True(esito["revocato_sul_server"]!.GetValue<bool>());
        Assert.Null(esito["avviso"]);
    }

    [Fact]
    public async Task Su_http_le_password_non_partono_mai()
    {
        using var banco = new Banco(_ => (201, "{}"), server: "http://192.168.1.51:8200");

        var gmail = await banco.Motore.InoltraAsync("POST", "api/caselle", null, "{\"tipo\": \"gmail\", \"indirizzo\": \"manuel.esempio@gmail.com\", \"password_app\": \"abcd efgh ijkl mnop\"}");
        var ricollega = await banco.Motore.InoltraAsync("POST", "api/caselle/2/ricollega", null, "{\"password_app\": \"abcd efgh ijkl mnop\"}");

        foreach (var r in new[] { gmail, ricollega })
        {
            Assert.Equal(422, r.Stato);
            var o = JsonNode.Parse(r.Json)!;
            Assert.Contains("https di Tailscale", o["errore"]!.GetValue<string>());
            Assert.Equal("password_app", o["campo"]!.GetValue<string>());
        }
        Assert.Empty(banco.Finto.Ricevute);
        Assert.True(banco.Motore.Stato()["senza_protezione"]!.GetValue<bool>());
    }

    [Fact]
    public async Task Su_http_senza_password_si_lavora_e_su_https_le_password_partono()
    {
        using var casa = new Banco(_ => (202, "{}"), server: "http://192.168.1.51:8200");
        var outlook = await casa.Motore.InoltraAsync("POST", "api/caselle", null, "{\"tipo\": \"outlook\", \"nome\": \"Studio\"}");
        Assert.Equal(202, outlook.Stato);

        using var tailscale = new Banco(_ => (201, "{}"));
        var gmail = await tailscale.Motore.InoltraAsync("POST", "api/caselle", null, "{\"tipo\": \"gmail\", \"password_app\": \"abcd efgh ijkl mnop\"}");
        Assert.Equal(201, gmail.Stato);
        Assert.False(tailscale.Motore.Stato()["senza_protezione"]!.GetValue<bool>());
    }

    [Fact]
    public async Task Abbinamento_su_http_fuori_dalla_rete_di_casa_rifiutato_senza_chiamare_il_server()
    {
        using var banco = new Banco(_ => (200, "{}"), abbinato: false);
        var esito = await banco.Motore.AbbinaAsync("http://sol.esempio.ts.net", "48213377", null, false);
        Assert.Contains("https", esito["errore"]!.GetValue<string>());
        Assert.Empty(banco.Finto.Ricevute);
    }

    [Fact]
    public async Task Il_campo_dei_422_arriva_alla_pagina_insieme_alla_frase()
    {
        using var banco = new Banco(_ => (422, "{\"errore\": \"I giorni per ricordare le mail senza risposta vanno da 1 a 60.\", \"codice\": \"non_valido\", \"campo\": \"giorni_risposta\"}"));
        var r = await banco.Motore.InoltraAsync("PATCH", "api/impostazioni", null, "{\"giorni_risposta\": 99}");
        var o = JsonNode.Parse(r.Json)!;
        Assert.Equal("I giorni per ricordare le mail senza risposta vanno da 1 a 60.", o["errore"]!.GetValue<string>());
        Assert.Equal("giorni_risposta", o["campo"]!.GetValue<string>());
    }

    [Fact]
    public async Task Server_irraggiungibile_da_mezz_ora_una_notifica_sola_e_icona_diversa()
    {
        using var banco = new Banco(_ => (0, ""));
        var notifiche = new List<Notifica>();
        banco.Motore.NuovaNotifica += notifiche.Add;

        await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        banco.Motore.ControllaCollegamento();
        Assert.Empty(notifiche);
        Assert.False(banco.Motore.ProblemaCollegamento);

        banco.Adesso = banco.Adesso.AddMinutes(31);
        await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        banco.Motore.ControllaCollegamento();
        banco.Adesso = banco.Adesso.AddMinutes(10);
        banco.Motore.ControllaCollegamento();

        var n = Assert.Single(notifiche);
        Assert.Equal("Sol non raggiunge il server", n.Titolo);
        Assert.True(banco.Motore.ProblemaCollegamento);
        Assert.True(banco.Motore.Stato()["problema_collegamento"]!.GetValue<bool>());
    }

    [Fact]
    public async Task Computer_non_piu_riconosciuto_una_notifica_sola()
    {
        using var banco = new Banco(_ => (401, Revocato));
        var notifiche = new List<Notifica>();
        banco.Motore.NuovaNotifica += notifiche.Add;

        await banco.Motore.InoltraAsync("GET", "api/gruppi", null, null);
        banco.Motore.ControllaCollegamento();
        banco.Motore.ControllaCollegamento();

        Assert.Equal("Sol non è più collegato", Assert.Single(notifiche).Titolo);
        Assert.True(banco.Motore.ProblemaCollegamento);
    }

    [Fact]
    public async Task Il_ponte_passa_la_versione_vista_dalla_pagina()
    {
        const string bozza = "{\"id\": 55, \"mail_id\": 812, \"tipo\": \"risposta\", \"stato\": \"pronta\", \"a\": [\"rossi@esempio.it\"], \"cc\": [], \"oggetto\": \"Re: Offerta\", \"testo\": \"Buongiorno\", \"versione\": 8}";
        using var banco = new Banco(r => r.Percorso == "/api/bozze/55" ? (200, bozza) : (404, "{}"));
        bool chiesto = false;
        banco.Motore.ChiediConferma = _ => { chiesto = true; return Task.FromResult(false); };
        var ponte = new Ponte(banco.Motore);

        var r = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/invia"), "{\"bozza_id\": 55, \"versione\": 7}");

        Assert.False(chiesto, "la pagina aveva visto la versione 7: niente conferma sulla 8");
        Assert.Equal("conflitto", JsonNode.Parse(r.Json)!["codice"]!.GetValue<string>());
    }
}

public class CollegamentoTest
{
    private static readonly DateTimeOffset T0 = new(2026, 10, 10, 9, 0, 0, TimeSpan.FromHours(2));

    [Fact]
    public void Un_attimo_senza_rete_non_e_un_problema_mezz_ora_si()
    {
        var s = new SorveglianzaCollegamento();
        s.Esito(senzaRisposta: true, T0);
        Assert.Null(s.Controlla(false, T0.AddMinutes(10)));
        Assert.False(s.Problema(false, T0.AddMinutes(10)));
        Assert.NotNull(s.Controlla(false, T0.AddMinutes(30)));
        Assert.True(s.Problema(false, T0.AddMinutes(30)));
        Assert.Null(s.Controlla(false, T0.AddMinutes(60)));
    }

    [Fact]
    public void Quando_il_server_torna_si_riparte_da_zero()
    {
        var s = new SorveglianzaCollegamento();
        s.Esito(true, T0);
        Assert.NotNull(s.Controlla(false, T0.AddMinutes(40)));
        s.Esito(false, T0.AddMinutes(41));
        Assert.False(s.Problema(false, T0.AddMinutes(41)));
        Assert.Null(s.IrraggiungibileDal);
        s.Esito(true, T0.AddMinutes(50));
        Assert.Null(s.Controlla(false, T0.AddMinutes(60)));
        Assert.NotNull(s.Controlla(false, T0.AddMinutes(81)));
    }

    [Fact]
    public void Non_riconosciuto_avvisa_subito_una_volta_per_ogni_volta_che_succede()
    {
        var s = new SorveglianzaCollegamento();
        Assert.Equal("Sol non è più collegato", s.Controlla(true, T0)!.Titolo);
        Assert.Null(s.Controlla(true, T0.AddMinutes(2)));
        Assert.True(s.Problema(true, T0));
        Assert.Null(s.Controlla(false, T0.AddMinutes(3)));
        Assert.NotNull(s.Controlla(true, T0.AddMinutes(4)));
    }
}
