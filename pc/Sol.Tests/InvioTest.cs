using System.Text.Json.Nodes;
using Sol.Interfaccia;
using Sol.Motore;

namespace Sol.Tests;

/// <summary>Nessuna mail parte senza la conferma esplicita: la regola più importante del programma.</summary>
public class InvioTest
{
    private const string BozzaPronta = """
    {"id": 55, "mail_id": 812, "tipo": "risposta", "stato": "pronta",
     "a": ["rossi@esempio.it"], "cc": ["bianchi@esempio.it"], "oggetto": "Re: Offerta capannone Bassano",
     "testo": "Buongiorno Mario, ...", "modificata": false, "errore": null, "versione": 3}
    """;

    private static (int, string) Server(HandlerFinto.Richiesta r, string bozza = BozzaPronta, int statoInvio = 200, string? corpoInvio = null)
    {
        if (r.Metodo == "GET" && r.Percorso == "/api/bozze/55") return (200, bozza);
        if (r.Metodo == "POST" && r.Percorso == "/api/bozze/55/invia")
        {
            return (statoInvio, corpoInvio ?? bozza.Replace("\"pronta\"", "\"inviata\""));
        }
        return (404, "{\"errore\":\"non trovato\",\"codice\":\"non_trovato\"}");
    }

    [Fact]
    public async Task Senza_conferma_non_parte_niente()
    {
        using var banco = new Banco(r => Server(r));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a =>
        {
            vista = a;
            return Task.FromResult(false);
        };

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.True(esito["annullato"]?.GetValue<bool>());
        Assert.NotNull(vista);
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Fact]
    public async Task Con_conferma_parte_una_volta_con_conferma_true()
    {
        using var banco = new Banco(r => Server(r));
        int domande = 0;
        banco.Motore.ChiediConferma = _ =>
        {
            domande++;
            return Task.FromResult(true);
        };

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.True(esito["inviata"]?.GetValue<bool>());
        Assert.Equal(1, domande);
        var invio = Assert.Single(banco.Richieste("POST", "/api/bozze/55/invia"));
        Assert.Equal("Bearer " + Banco.Token, invio.Autorizzazione);
        Assert.True(JsonNode.Parse(invio.Corpo!)!["conferma"]!.GetValue<bool>());
        Assert.Equal("inviata", esito["bozza"]?["stato"]?.GetValue<string>());
    }

    [Fact]
    public async Task La_conferma_mostra_destinatari_e_oggetto_letti_dal_server()
    {
        using var banco = new Banco(r => Server(r));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a =>
        {
            vista = a;
            return Task.FromResult(false);
        };

        await banco.Motore.InviaBozzaAsync(55);

        // Prima della conferma si rilegge la bozza dal server: si mostra quello che partirebbe davvero.
        var prima = banco.Finto.Ricevute.First();
        Assert.Equal("GET", prima.Metodo);
        Assert.Equal("/api/bozze/55", prima.Percorso);
        Assert.Equal(new[] { "rossi@esempio.it" }, vista!.A);
        Assert.Equal(new[] { "bianchi@esempio.it" }, vista.Cc);
        Assert.Equal("Re: Offerta capannone Bassano", vista.Oggetto);

        var testo = ConfermaInvio.Testo(vista);
        Assert.Contains("A: rossi@esempio.it", testo);
        Assert.Contains("Cc: bianchi@esempio.it", testo);
        Assert.Contains("Oggetto: Re: Offerta capannone Bassano", testo);
    }

    [Fact]
    public void Il_testo_della_conferma_non_nasconde_destinatari_senza_dirlo()
    {
        var tanti = Enumerable.Range(1, 11).Select(i => $"cliente{i}@esempio.it").ToList();
        var testo = ConfermaInvio.Testo(new AnteprimaInvio(1, tanti, Array.Empty<string>(), "", "risposta"));
        Assert.Contains("cliente1@esempio.it", testo);
        Assert.Contains("cliente8@esempio.it", testo);
        Assert.Contains("e altri 3", testo);
        Assert.Contains("(senza oggetto)", testo);
        Assert.DoesNotContain("Cc:", testo);
    }

    [Fact]
    public async Task Senza_chi_chiede_conferma_la_mail_non_parte()
    {
        using var banco = new Banco(r => Server(r));
        banco.Motore.ChiediConferma = null;

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.NotNull(esito["errore"]);
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Fact]
    public async Task Se_la_finestra_di_conferma_si_rompe_la_mail_non_parte()
    {
        using var banco = new Banco(r => Server(r));
        banco.Motore.ChiediConferma = _ => throw new InvalidOperationException("finestra rotta");

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.True(esito["annullato"]?.GetValue<bool>());
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Theory]
    [InlineData("inviata", "già stata inviata")]
    [InlineData("in_preparazione", "sta ancora scrivendo")]
    [InlineData("scartata", "scartata")]
    public async Task Bozza_non_inviabile_non_chiede_nemmeno_conferma(string stato, string frase)
    {
        using var banco = new Banco(r => Server(r, BozzaPronta.Replace("\"pronta\"", "\"" + stato + "\"")));
        bool chiesto = false;
        banco.Motore.ChiediConferma = _ =>
        {
            chiesto = true;
            return Task.FromResult(true);
        };

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Contains(frase, esito["errore"]!.GetValue<string>());
        Assert.False(chiesto);
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Fact]
    public async Task Senza_destinatario_niente_conferma_e_niente_invio()
    {
        using var banco = new Banco(r => Server(r, BozzaPronta.Replace("[\"rossi@esempio.it\"]", "[]")));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Contains("destinatario", esito["errore"]!.GetValue<string>());
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Fact]
    public async Task Invio_fallito_torna_con_la_frase_del_server_e_la_bozza_che_sta_dentro_bozza()
    {
        // La forma vera di server/app/routes/bozze.py: la bozza aggiornata sta in "bozza", non in cima.
        var errore = "{\"errore\": \"Gmail non ha accettato l'invio.\", \"codice\": \"casella_non_raggiungibile\", \"bozza\": "
            + BozzaPronta.Replace("\"pronta\"", "\"errore\"") + "}";
        using var banco = new Banco(r => Server(r, statoInvio: 502, corpoInvio: errore));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal("Gmail non ha accettato l'invio.", esito["errore"]!.GetValue<string>());
        Assert.Equal("casella_non_raggiungibile", esito["codice"]!.GetValue<string>());
        Assert.Equal(55, esito["bozza"]?["id"]?.GetValue<long>());
        Assert.Equal("errore", esito["bozza"]?["stato"]?.GetValue<string>());
        Assert.Null(esito["inviata"]);
    }

    [Fact]
    public async Task Il_server_vuole_la_versione_mostrata_nella_conferma()
    {
        using var banco = new Banco(r => Server(r));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        await banco.Motore.InviaBozzaAsync(55, versioneVista: 3);

        var invio = Assert.Single(banco.Richieste("POST", "/api/bozze/55/invia"));
        var corpo = JsonNode.Parse(invio.Corpo!)!;
        Assert.True(corpo["conferma"]!.GetValue<bool>());
        Assert.Equal(3, corpo["versione"]!.GetValue<long>());
    }

    [Fact]
    public async Task Bozza_cambiata_dopo_che_la_pagina_l_ha_mostrata_niente_conferma_e_si_torna_con_quella_nuova()
    {
        using var banco = new Banco(r => Server(r));
        bool chiesto = false;
        banco.Motore.ChiediConferma = _ => { chiesto = true; return Task.FromResult(true); };

        var esito = await banco.Motore.InviaBozzaAsync(55, versioneVista: 2);

        Assert.False(chiesto);
        Assert.Equal("conflitto", esito["codice"]!.GetValue<string>());
        Assert.Equal(Motore.Motore.FraseCambiata, esito["errore"]!.GetValue<string>());
        Assert.Equal(3, esito["bozza"]?["versione"]?.GetValue<long>());
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Fact]
    public async Task Bozza_cambiata_mentre_la_finestra_era_aperta_il_409_torna_con_la_bozza_nuova()
    {
        // Come la prova del revisore: la finestra mostra una versione, intanto arriva un salvataggio.
        var conflitto = "{\"errore\": \"" + Motore.Motore.FraseCambiata + "\", \"codice\": \"conflitto\", \"bozza\": "
            + BozzaPronta.Replace("\"versione\": 3", "\"versione\": 4").Replace("[\"bianchi@esempio.it\"]", "[\"bianchi@esempio.it\", \"verdi@esempio.it\"]") + "}";
        using var banco = new Banco(r => Server(r, statoInvio: 409, corpoInvio: conflitto));
        AnteprimaInvio? mostrata = null;
        banco.Motore.ChiediConferma = a => { mostrata = a; return Task.FromResult(true); };

        var esito = await banco.Motore.InviaBozzaAsync(55, versioneVista: 3);

        Assert.Equal(new[] { "bianchi@esempio.it" }, mostrata!.Cc);
        Assert.Equal(3, JsonNode.Parse(Assert.Single(banco.Richieste("POST", "/api/bozze/55/invia")).Corpo!)!["versione"]!.GetValue<long>());
        Assert.Null(esito["inviata"]);
        Assert.Equal("conflitto", esito["codice"]!.GetValue<string>());
        Assert.Equal(4, esito["bozza"]?["versione"]?.GetValue<long>());
    }

    [Fact]
    public async Task Testo_vuoto_niente_conferma()
    {
        using var banco = new Banco(r => Server(r, BozzaPronta.Replace("\"Buongiorno Mario, ...\"", "\"  \"")));
        int domande = 0;
        banco.Motore.ChiediConferma = _ => { domande++; return Task.FromResult(true); };

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal(0, domande);
        Assert.Contains("vuoto", esito["errore"]!.GetValue<string>());
        Assert.Empty(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Theory]
    [InlineData(500, "{\"errore\": \"Qualcosa non va.\", \"codice\": \"errore_interno\"}")]
    [InlineData(504, "")]
    [InlineData(502, "<html>Bad Gateway</html>")]
    public async Task Esito_incerto_dell_invio_dice_di_guardare_in_posta_inviata(int stato, string corpo)
    {
        using var banco = new Banco(r => Server(r, statoInvio: stato, corpoInvio: corpo));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal(Motore.Motore.FraseInvioIncerto, esito["errore"]!.GetValue<string>());
        Assert.Contains("Posta inviata", esito["errore"]!.GetValue<string>());
    }

    [Fact]
    public async Task Il_502_di_non_riesco_a_capire_se_e_partita_arriva_con_la_frase_del_server()
    {
        const string frase = "Non riesco a capire se la mail è già partita. Guarda in Posta inviata prima di riprovare.";
        using var banco = new Banco(r => Server(r, statoInvio: 502, corpoInvio: "{\"errore\": \"" + frase + "\", \"codice\": \"casella_non_raggiungibile\"}"));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal(frase, esito["errore"]!.GetValue<string>());
    }

    [Fact]
    public async Task Gia_inviata_risulta_inviata_e_lo_dice()
    {
        var corpo = BozzaPronta.Replace("\"pronta\"", "\"inviata\"").TrimEnd().TrimEnd('}') + ", \"gia_inviata\": true}";
        using var banco = new Banco(r => Server(r, statoInvio: 200, corpoInvio: corpo));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.True(esito["inviata"]!.GetValue<bool>());
        Assert.True(esito["gia_inviata"]!.GetValue<bool>());
        Assert.Equal("inviata", esito["bozza"]?["stato"]?.GetValue<string>());
    }

    [Fact]
    public async Task La_conferma_mostra_la_prima_riga_del_testo()
    {
        using var banco = new Banco(r => Server(r, BozzaPronta.Replace("\"Buongiorno Mario, ...\"", "\"\\n\\nBuongiorno Mario,\\nle confermo giovedì.\"")));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a => { vista = a; return Task.FromResult(false); };

        await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal("Buongiorno Mario,", vista!.PrimaRiga);
        Assert.Contains("Comincia con: «Buongiorno Mario,»", ConfermaInvio.Testo(vista));
    }

    [Fact]
    public async Task Sollecito_la_conferma_dice_per_quale_mail_inviata()
    {
        const string sollecito = """
        {"id": 55, "mail_id": null, "inviata_id": 77, "tipo": "sollecito", "stato": "pronta", "a": ["neri@esempio.it"], "cc": [],
         "oggetto": "Re: Offerta compressore", "testo": "Gentile Neri, le ricordo la nostra offerta.", "versione": 2}
        """;
        const string indietro = """
        {"aspettano_te": [], "aspetti_tu": [
          {"inviata": {"id": 78, "a": ["bianchi@esempio.it"], "oggetto": "Preventivo impianto", "inviata_il": "2026-10-01T10:00:00+02:00"}, "giorni": 9},
          {"inviata": {"id": 77, "a": ["neri@esempio.it"], "oggetto": "Offerta compressore", "inviata_il": "2026-10-02T10:00:00+02:00"}, "giorni": 8}]}
        """;
        using var banco = new Banco(r => r.Percorso == "/api/indietro" ? (200, indietro) : Server(r, sollecito));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a => { vista = a; return Task.FromResult(false); };

        await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal("sollecito", vista!.Tipo);
        Assert.StartsWith("neri@esempio.it – Offerta compressore", vista.SollecitoPer);
        var testo = ConfermaInvio.Testo(vista);
        Assert.Contains("Sollecito per: neri@esempio.it – Offerta compressore", testo);
        Assert.Contains("Gentile Neri", testo);
        Assert.Equal(ConfermaInvio.TitoloSollecito, ConfermaInvio.TitoloPer(vista));
    }

    [Fact]
    public async Task Risposta_gia_data_da_un_altro_programma_la_conferma_lo_dice()
    {
        using var banco = new Banco(r => r.Percorso == "/api/mail/812" ? (200, "{\"id\": 812, \"risposta_data\": true}") : Server(r));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a => { vista = a; return Task.FromResult(false); };

        await banco.Motore.InviaBozzaAsync(55);

        Assert.True(vista!.GiaRisposto);
        Assert.StartsWith(ConfermaInvio.AvvisoGiaRisposto, ConfermaInvio.Testo(vista));
    }

    [Fact]
    public async Task Risposta_non_ancora_data_niente_avviso()
    {
        using var banco = new Banco(r => r.Percorso == "/api/mail/812" ? (200, "{\"id\": 812, \"risposta_data\": false}") : Server(r));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a => { vista = a; return Task.FromResult(false); };

        await banco.Motore.InviaBozzaAsync(55);

        Assert.False(vista!.GiaRisposto);
        Assert.DoesNotContain("già risposto", ConfermaInvio.Testo(vista));
    }

    [Fact]
    public async Task Rete_caduta_durante_l_invio_lo_dice_chiaramente()
    {
        using var banco = new Banco(r => r.Metodo == "POST" ? (0, "") : Server(r));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(55);

        Assert.Equal(Motore.Motore.FraseInvioIncerto, esito["errore"]!.GetValue<string>());
    }

    [Fact]
    public async Task Due_invii_insieme_della_stessa_bozza_ne_fanno_partire_uno()
    {
        using var banco = new Banco(r => Server(r));
        var lascia = new TaskCompletionSource<bool>();
        banco.Motore.ChiediConferma = _ => lascia.Task;

        var primo = banco.Motore.InviaBozzaAsync(55);
        await Task.Delay(100);
        var secondo = await banco.Motore.InviaBozzaAsync(55);
        lascia.SetResult(true);
        var esitoPrimo = await primo;

        Assert.Contains("già in corso", secondo["errore"]!.GetValue<string>());
        Assert.True(esitoPrimo["inviata"]?.GetValue<bool>());
        Assert.Single(banco.Richieste("POST", "/api/bozze/55/invia"));
    }

    [Theory]
    [InlineData("api/bozze/55/invia")]
    [InlineData("api/bozze/55/invia/")]
    [InlineData("API/BOZZE/55/INVIA")]
    public async Task La_pagina_non_raggiunge_mai_l_invio_direttamente(string percorso)
    {
        using var banco = new Banco(r => Server(r));

        var r = await banco.Motore.InoltraAsync("POST", percorso, null, "{\"conferma\":true}");

        Assert.Equal(403, r.Stato);
        Assert.Contains("conferma", JsonNode.Parse(r.Json)!["errore"]!.GetValue<string>());
        Assert.Empty(banco.Finto.Ricevute);
    }

    [Fact]
    public async Task Anche_dal_ponte_l_invio_diretto_e_rifiutato()
    {
        using var banco = new Banco(r => Server(r));
        var ponte = new Ponte(banco.Motore);

        var r = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/server/api/bozze/55/invia"), "{\"conferma\":true}");

        Assert.Equal(403, r.Stato);
        Assert.Empty(banco.Finto.Ricevute);
    }

    [Fact]
    public async Task Dal_ponte_l_invio_passa_dalla_conferma()
    {
        using var banco = new Banco(r => Server(r));
        int domande = 0;
        banco.Motore.ChiediConferma = _ =>
        {
            domande++;
            return Task.FromResult(true);
        };
        var ponte = new Ponte(banco.Motore);

        var r = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/invia"), "{\"bozza_id\": 55}");

        Assert.Equal(200, r.Stato);
        Assert.Equal(1, domande);
        Assert.Single(banco.Richieste("POST", "/api/bozze/55/invia"));
    }
}
