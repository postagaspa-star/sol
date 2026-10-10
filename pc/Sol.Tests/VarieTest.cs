using System.Text.Json.Nodes;
using Sol.Interfaccia;
using Sol.Nucleo;
using Sol.Sistema;

namespace Sol.Tests;

public class IndirizzoTest
{
    [Theory]
    [InlineData("https://sol.esempio.ts.net", "https://sol.esempio.ts.net")]
    [InlineData("  https://sol.esempio.ts.net/  ", "https://sol.esempio.ts.net")]
    [InlineData("https://sol.esempio.ts.net/api", "https://sol.esempio.ts.net")]
    [InlineData("sol.esempio.ts.net", "https://sol.esempio.ts.net")]
    [InlineData("192.168.1.51:8200", "http://192.168.1.51:8200")]
    [InlineData("http://192.168.1.51:8200/api/", "http://192.168.1.51:8200")]
    [InlineData("HTTPS://Sol.Esempio.ts.net", "https://sol.esempio.ts.net")]
    public void Indirizzi_normalizzati(string scritto, string atteso)
    {
        Assert.True(Indirizzo.Normalizza(scritto, out var base_, out var errore));
        Assert.Null(errore);
        Assert.Equal(atteso, base_);
    }

    [Theory]
    [InlineData("10.0.0.5:8200", "http://10.0.0.5:8200")]
    [InlineData("http://172.20.1.1:8200", "http://172.20.1.1:8200")]
    [InlineData("https://192.168.1.51:8200", "https://192.168.1.51:8200")]
    public void Http_solo_per_gli_indirizzi_di_casa(string scritto, string atteso)
    {
        Assert.True(Indirizzo.Normalizza(scritto, out var base_, out _));
        Assert.Equal(atteso, base_);
    }

    [Theory]
    [InlineData("http://sol.esempio.ts.net")]
    [InlineData("http://8.8.8.8:8200")]
    [InlineData("http://172.32.0.1:8200")]
    [InlineData("http://localhost:8200")]
    public void Http_fuori_casa_rifiutato_con_la_frase_di_tailscale(string scritto)
    {
        Assert.False(Indirizzo.Normalizza(scritto, out _, out var errore));
        Assert.Equal(Indirizzo.FraseSoloHttps, errore);
    }

    [Fact]
    public void Indirizzi_di_casa_e_collegamento_non_protetto()
    {
        Assert.True(Indirizzo.DiCasa("192.168.1.51"));
        Assert.True(Indirizzo.DiCasa("172.16.0.1"));
        Assert.False(Indirizzo.DiCasa("172.15.0.1"));
        Assert.False(Indirizzo.DiCasa("10.1"));
        Assert.False(Indirizzo.DiCasa("sol.esempio.ts.net"));
        Assert.True(Indirizzo.SenzaProtezione("http://192.168.1.51:8200"));
        Assert.False(Indirizzo.SenzaProtezione("https://sol.esempio.ts.net"));
        Assert.False(Indirizzo.SenzaProtezione(null));
    }

    [Theory]
    [InlineData("")]
    [InlineData("ftp://sol.esempio.ts.net")]
    [InlineData("https://sol.esempio.ts.net/?x=1")]
    [InlineData("https://utente:segreto@sol.esempio.ts.net")]
    public void Indirizzi_sbagliati_con_frase(string scritto)
    {
        Assert.False(Indirizzo.Normalizza(scritto, out _, out var errore));
        Assert.False(string.IsNullOrWhiteSpace(errore));
    }
}

public class FrasiTest
{
    [Fact]
    public void La_frase_del_server_vince()
    {
        Assert.Equal("La casella Studio non risponde.", Frasi.PerRisposta(502, "{\"errore\": \"La casella Studio non risponde.\", \"codice\": \"casella_non_raggiungibile\"}"));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(401)]
    [InlineData(404)]
    [InlineData(409)]
    [InlineData(429)]
    [InlineData(500)]
    [InlineData(502)]
    [InlineData(503)]
    public void Senza_frase_del_server_niente_codici_tecnici(int stato)
    {
        var frase = Frasi.PerRisposta(stato, "<html>Bad Gateway</html>");
        Assert.DoesNotContain(stato.ToString(), frase);
        Assert.DoesNotContain("HTTP", frase);
        Assert.DoesNotContain("Gateway", frase);
        Assert.True(frase.Length > 20);
    }

    [Fact]
    public void Accorcia_toglie_gli_a_capo_e_mette_i_puntini()
    {
        Assert.Equal("uno due", Frasi.Accorcia("uno\n\n  due", 50));
        Assert.Equal(10, Frasi.Accorcia(new string('a', 30), 10).Length);
    }
}

public class PonteTest
{
    [Theory]
    [InlineData("https://microsoft.com/devicelogin", true)]
    [InlineData("https://www.microsoft.com/link", true)]
    [InlineData("https://login.microsoftonline.com/common/oauth2/deviceauth", true)]
    [InlineData("https://myaccount.google.com/apppasswords", true)]
    [InlineData("http://microsoft.com/devicelogin", false)]
    [InlineData("https://microsoft.com.esempio.it/devicelogin", false)]
    [InlineData("https://notmicrosoft.com/", false)]
    [InlineData("https://accounts.google.com.esempio.it/", false)]
    [InlineData("file:///C:/Windows/System32/cmd.exe", false)]
    [InlineData("javascript:alert(1)", false)]
    [InlineData("https://utente@microsoft.com/", false)]
    public void Solo_microsoft_e_google_password_si_aprono_fuori(string url, bool atteso)
    {
        Assert.Equal(atteso, Ponte.ApribileFuori(url));
    }

    [Theory]
    [InlineData("/", "index.html")]
    [InlineData("/app.js", "app.js")]
    [InlineData("/../segreto.txt", null)]
    [InlineData("/%2e%2e/segreto.txt", null)]
    [InlineData("/C:/Windows/win.ini", null)]
    [InlineData("/cartella\\file", null)]
    public void File_dell_interfaccia_mai_fuori_dalla_cartella(string percorso, string? atteso)
    {
        var ui = Path.Combine(Path.GetTempPath(), "sol-ui-finta");
        var file = Ponte.FileDellInterfaccia(ui, percorso);
        if (atteso == null) Assert.Null(file);
        else Assert.Equal(Path.Combine(ui, atteso), file);
    }

    [Fact]
    public async Task Rotte_locali_col_metodo_sbagliato_o_sconosciute()
    {
        using var banco = new Banco(_ => (200, "{}"));
        var ponte = new Ponte(banco.Motore);

        Assert.Equal(405, (await ponte.GestisciAsync("GET", new Uri(Ponte.Origine + "/locale/invia"), null)).Stato);
        Assert.Equal(404, (await ponte.GestisciAsync("GET", new Uri(Ponte.Origine + "/locale/qualcosa"), null)).Stato);
        var stato = await ponte.GestisciAsync("GET", new Uri(Ponte.Origine + "/locale/stato"), null);
        Assert.Equal(200, stato.Stato);
        var o = JsonNode.Parse(stato.Json)!;
        Assert.True(o["abbinato"]!.GetValue<bool>());
        Assert.Equal(Versione.Nome, o["versione"]!.GetValue<string>());
        Assert.DoesNotContain(Banco.Token, stato.Json);
    }

    [Fact]
    public async Task Apri_rifiuta_gli_indirizzi_non_ammessi()
    {
        using var banco = new Banco(_ => (200, "{}"));
        string? aperto = null;
        var ponte = new Ponte(banco.Motore) { ApriNelBrowser = u => aperto = u };

        var no = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/apri"), "{\"url\": \"https://esempio.it/trappola\"}");
        Assert.Equal(422, no.Stato);
        Assert.Null(aperto);

        var si = await ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/apri"), "{\"url\": \"https://microsoft.com/devicelogin\"}");
        Assert.Equal(200, si.Stato);
        Assert.Equal("https://microsoft.com/devicelogin", aperto);
    }

    [Theory]
    [InlineData("mail/812", true)]
    [InlineData("urgenti", true)]
    [InlineData("impostazioni", true)]
    [InlineData("notifiche", true)]
    [InlineData("mail/0", false)]
    [InlineData("mail/12'); alert(1); ('", false)]
    [InlineData("sconosciuta", false)]
    public void La_finestra_apre_solo_frammenti_controllati(string frammento, bool atteso)
    {
        Assert.Equal(atteso, FinestraSol.FrammentoValido(frammento));
    }
}

public class IstanzaTest
{
    [Fact]
    public void Una_sola_istanza_e_la_seconda_chiede_di_aprire_la_prima()
    {
        var nome = @"Local\Sol.Test." + Guid.NewGuid().ToString("N");
        using var prima = new Istanza(nome, nomeCompleto: true);
        using var aperta = new ManualResetEventSlim();
        prima.RichiestaApertura += aperta.Set;
        prima.Ascolta();
        Assert.True(prima.Prima);

        // Su un altro filo, come un altro processo: lo stesso filo possiede già il mutex (è rientrante).
        bool? secondaPrima = null;
        var filo = new Thread(() =>
        {
            using var seconda = new Istanza(nome, nomeCompleto: true);
            seconda.ChiediApertura();
            secondaPrima = seconda.Prima;
        });
        filo.Start();
        Assert.True(filo.Join(TimeSpan.FromSeconds(5)));
        Assert.False(secondaPrima);
        Assert.True(aperta.Wait(TimeSpan.FromSeconds(5)));
    }
}

public class OpzioniTest
{
    [Fact]
    public void Opzioni_della_riga_di_comando()
    {
        var o = Opzioni.Da(new[] { "--avvio", "--dati", Path.GetTempPath(), "--esci-dopo", "5" });
        Assert.True(o.Avvio);
        Assert.False(o.Apri);
        Assert.Equal(5, o.EsciDopoSecondi);
        Assert.NotEqual("", o.SuffissoIstanza);

        var apri = Opzioni.Da(new[] { "--apri", "--no-installa" });
        Assert.True(apri.Apri);
        Assert.True(apri.NonInstallare);

        Assert.Equal("", Opzioni.Da(Array.Empty<string>()).SuffissoIstanza);
        Assert.Throws<ArgumentException>(() => Opzioni.Da(new[] { "--dati" }));
    }

    [Theory]
    [InlineData(null, null)]
    [InlineData(0, null)]
    [InlineData(3, "3")]
    [InlineData(9, "9")]
    [InlineData(12, "9+")]
    public void Numero_sull_icona(int? urgenti, string? atteso)
    {
        Assert.Equal(atteso, IconaVassoio.TestoNumero(urgenti));
    }

    [Fact]
    public void Con_un_problema_di_collegamento_l_icona_e_il_suggerimento_lo_dicono()
    {
        Assert.Equal("!", IconaVassoio.TestoNumero(5, problema: true));
        Assert.Equal("Sol: questo computer non è più collegato", IconaVassoio.Suggerimento(5, nonRiconosciuto: true, problema: true));
        Assert.Equal("Sol: il server di casa non risponde", IconaVassoio.Suggerimento(5, nonRiconosciuto: false, problema: true));
        Assert.Equal("Sol: 5 mail urgenti", IconaVassoio.Suggerimento(5, false, false));
        Assert.Equal("Sol", IconaVassoio.Suggerimento(0, false, false));
    }

    [Theory]
    [InlineData("\"pulito\"", "pulito")]
    [InlineData("\"in_corso\"", "in_corso")]
    [InlineData("\"fallito\"", "fallito")]
    [InlineData("null", "fallito")]
    [InlineData("", "fallito")]
    public void Lo_stato_del_salvataggio_prima_di_chiudere(string json, string atteso)
    {
        Assert.Equal(atteso, FinestraSol.StatoSalvataggio(json));
    }
}
