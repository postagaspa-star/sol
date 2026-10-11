using System.Drawing;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Sol.Interfaccia;
using Sol.Motore;
using Sol.Nucleo;
using Sol.Sistema;

namespace Sol.Tests;

/// <summary>
/// Sol 2.0 (docs/aspetto.md, contratto §17-18), dal lato del programma: versione 2.0.0, fondo della finestra
/// uguale a quello della pagina nei due temi e col tema scelto, i 12 colori dei gruppi uguali in pagina, logica e
/// contratto, il tutorial visto una volta sola, il tema scelto che resta, il microfono (Win+H).
/// Il comportamento della pagina (Oggi, Posta, Smista, tutorial) lo provano i test JS in pc/prove/js,
/// lanciati da <see cref="InterfacciaJsTest"/>.
/// </summary>
public class AspettoTest
{
    /// <summary>I nomi del contratto (§17), nell'ordine in cui il server li assegna ai gruppi nuovi.</summary>
    private static readonly string[] ColoriContratto =
        { "blu", "verde", "ambra", "viola", "grigio", "ardesia", "petrolio", "rosa", "indaco", "oliva", "marrone", "ciano" };

    private static string Ui(string file) => File.ReadAllText(Path.Combine(Sorgenti.CartellaPc(), "ui", file));

    [Fact]
    public void Versione_2_0_0_uguale_nel_codice_e_nel_progetto()
    {
        Assert.Equal("2.0.0", Versione.Nome);
        Assert.Equal(3, Versione.Codice);
        var progetto = File.ReadAllText(Path.Combine(Sorgenti.CartellaPc(), "Sol", "Sol.csproj"));
        Assert.Contains("<Version>" + Versione.Nome + "</Version>", progetto);
        Assert.Contains("<FileVersion>" + Versione.Nome + ".0</FileVersion>", progetto);
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(1, false)]
    [InlineData(null, false)]
    [InlineData("0", false)]
    public void Tema_scuro_solo_con_AppsUseLightTheme_a_zero(object? valore, bool scuro)
    {
        Assert.Equal(scuro, Tema.ScuroDa(valore));
    }

    [Theory]
    [InlineData(null, false, false)]
    [InlineData(null, true, true)]
    [InlineData("auto", true, true)]
    [InlineData("chiaro", true, false)]
    [InlineData("scuro", false, true)]
    public void Il_tema_scelto_vince_su_quello_di_Windows(string? scelta, bool sistemaScuro, bool scuro)
    {
        Assert.Equal(scuro, Tema.ScuroPer(scelta, sistemaScuro));
    }

    [Fact]
    public void Il_fondo_della_finestra_e_quello_della_pagina_nei_due_temi()
    {
        var css = Ui("stile.css");
        var chiaro = Regex.Match(css, @":root \{[^}]*--fondo: (#[0-9A-F]{6});").Groups[1].Value;
        var scuroAuto = Regex.Match(css, @"@media \(prefers-color-scheme: dark\) \{\s*:root:not\(\[data-tema=""chiaro""\]\) \{[^}]*--fondo: (#[0-9A-F]{6});").Groups[1].Value;
        var scuroScelto = Regex.Match(css, @":root\[data-tema=""scuro""\] \{[^}]*--fondo: (#[0-9A-F]{6});").Groups[1].Value;
        Assert.Equal("#F4F4F2", chiaro);
        Assert.Equal("#121214", scuroAuto);
        Assert.Equal(scuroAuto, scuroScelto);
        Assert.Equal(ColorTranslator.FromHtml(chiaro).ToArgb(), Tema.Fondo(false).ToArgb());
        Assert.Equal(ColorTranslator.FromHtml(scuroAuto).ToArgb(), Tema.Fondo(true).ToArgb());
    }

    [Fact]
    public void I_12_colori_dei_gruppi_sono_quelli_del_contratto_in_pagina_e_nella_logica()
    {
        var css = Ui("stile.css");
        var classi = Regex.Matches(css, @"^\.col-(\w+) \{ --g: #[0-9A-F]{6}; --g-tenue: #[0-9A-F]{6}; --g-ink: #[0-9A-F]{6}; --g-punto: #[0-9A-F]{6}; \}", RegexOptions.Multiline)
            .Select(m => m.Groups[1].Value).ToArray();
        Assert.Equal(ColoriContratto.OrderBy(x => x), classi.OrderBy(x => x));
        foreach (var c in ColoriContratto)
        {
            // Tinte del tema scuro: uguali in automatico e scelto nelle impostazioni.
            var auto = Regex.Match(css, @"\n  :root:not\(\[data-tema=""chiaro""\]\) \.col-" + c + @" \{ (--g-tenue: #[0-9A-F]{6}; --g-ink: #[0-9A-F]{6};[^}]*)\}");
            var scelto = Regex.Match(css, @"\n:root\[data-tema=""scuro""\] \.col-" + c + @" \{ (--g-tenue: #[0-9A-F]{6}; --g-ink: #[0-9A-F]{6};[^}]*)\}");
            Assert.True(auto.Success, c);
            Assert.Equal(auto.Groups[1].Value, scelto.Groups[1].Value);
        }

        var logica = Ui("logica.js");
        var elenco = Regex.Match(logica, @"const COLORI_GRUPPI = \[([^\]]*)\];").Groups[1].Value;
        Assert.Equal(ColoriContratto, Regex.Matches(elenco, @"'(\w+)'").Select(m => m.Groups[1].Value).ToArray());
        Assert.Contains("COLORI_GRUPPI.includes(n) ? n : 'ardesia'", logica); // nome sconosciuto → ardesia
    }

    [Fact]
    public void Meno_movimenti_se_Windows_lo_chiede_e_in_loop_solo_lo_scheletro()
    {
        var css = Ui("stile.css");
        Assert.Matches(@"@media \(prefers-reduced-motion: reduce\) \{\s*\*, \*::before, \*::after \{ animation: none !important; transition: none !important; \}", css);
        var infiniti = Regex.Matches(css, @"animation:\s*(\w+)[^;]*infinite").Select(m => m.Groups[1].Value).ToArray();
        Assert.Equal(new[] { "pulsa" }, infiniti);
    }

    [Fact]
    public void Figtree_si_carica_solo_dal_pacchetto()
    {
        var html = Ui("index.html");
        Assert.Contains("font-src 'self'", html);
        Assert.DoesNotContain("fonts.googleapis", html);
        Assert.Equal("font/woff2", Ponte.TipoContenuto("Figtree.woff2"));
        Assert.Equal("font/ttf", Ponte.TipoContenuto("Figtree.ttf"));
    }

    [Theory]
    [InlineData("oggi", true)]
    [InlineData("posta", true)]
    [InlineData("smista", true)]
    [InlineData("aspetti", true)]
    [InlineData("da-controllare", true)]
    [InlineData("tutorial", false)]
    public void Le_viste_nuove_si_aprono_dalle_notifiche(string frammento, bool atteso)
    {
        Assert.Equal(atteso, FinestraSol.FrammentoValido(frammento));
    }

    [Fact]
    public void Il_tutorial_si_segna_visto_una_volta_e_resta_anche_ricollegando()
    {
        var token = new string('f', 64);
        using var banco = new Banco(r => r.Percorso == "/api/abbina" ? (200, "{\"token\": \"" + token + "\", \"dispositivo_id\": 3}") : (200, "{}"));
        Assert.False(banco.Motore.Stato()["tutorial_visto"]!.GetValue<bool>());

        Assert.True(banco.Motore.SegnaTutorialVisto()["ok"]!.GetValue<bool>());
        Assert.True(banco.Motore.Stato()["tutorial_visto"]!.GetValue<bool>());
        Assert.True(Archivio.LeggiJson<Configurazione>(banco.Percorsi.Config)!.TutorialVisto);

        // Riaperto il programma, non riparte.
        using var altro = new Motore.Motore(banco.Percorsi, new Rete.ClientSol(new HandlerFinto(_ => (200, "{}"))));
        Assert.True(altro.Stato()["tutorial_visto"]!.GetValue<bool>());
    }

    [Fact]
    public async Task Tema_e_tutorial_sono_di_questo_computer_scollegando_e_ricollegando_restano()
    {
        var token = new string('f', 64);
        using var banco = new Banco(r => r.Percorso == "/api/abbina" ? (200, "{\"token\": \"" + token + "\", \"dispositivo_id\": 3}") : (200, "{}"));
        banco.Motore.SegnaTutorialVisto();
        banco.Motore.ImpostaTema("scuro");

        await banco.Motore.ScollegaAsync();
        Assert.Equal("scuro", banco.Motore.Stato()["tema"]!.GetValue<string>());
        Assert.True(banco.Motore.Stato()["tutorial_visto"]!.GetValue<bool>());

        var esito = await banco.Motore.AbbinaAsync(Banco.Server, "48213377", "Computer dello studio", false);
        Assert.True(esito["ok"]?.GetValue<bool>());
        Assert.Equal("scuro", banco.Motore.TemaScelto);
        Assert.True(Archivio.LeggiJson<Configurazione>(banco.Percorsi.Config)!.TutorialVisto);
    }

    [Fact]
    public void Il_tema_si_sceglie_tra_automatico_chiaro_e_scuro_e_la_finestra_lo_sa()
    {
        using var banco = new Banco(_ => (200, "{}"));
        var avvisi = 0;
        banco.Motore.TemaCambiato += () => avvisi++;
        Assert.Equal("auto", banco.Motore.Stato()["tema"]!.GetValue<string>());
        Assert.Null(banco.Motore.TemaScelto);

        Assert.True(banco.Motore.ImpostaTema("chiaro")["ok"]!.GetValue<bool>());
        Assert.Equal("chiaro", banco.Motore.TemaScelto);
        Assert.Equal("chiaro", Archivio.LeggiJson<Configurazione>(banco.Percorsi.Config)!.Tema);

        banco.Motore.ImpostaTema("auto");
        Assert.Null(banco.Motore.TemaScelto);
        Assert.Equal(2, avvisi);

        var no = banco.Motore.ImpostaTema("viola");
        Assert.Equal("non_valido", no["codice"]!.GetValue<string>());
        Assert.Equal("tema", no["campo"]!.GetValue<string>());
        Assert.Equal(2, avvisi);
        Assert.Null(banco.Motore.TemaScelto);
    }

    [Fact]
    public async Task Rotte_del_ponte_per_tutorial_tema_e_microfono()
    {
        using var banco = new Banco(_ => (200, "{}"));
        var ponte = new Ponte(banco.Motore);
        var temi = 0;
        ponte.TemaCambiato += () => temi++;
        var locale = (string nome, string? corpo) => ponte.GestisciAsync("POST", new Uri(Ponte.Origine + "/locale/" + nome), corpo);

        Assert.Equal(200, (await locale("tutorial-visto", "{}")).Stato);
        Assert.True(banco.Motore.Stato()["tutorial_visto"]!.GetValue<bool>());

        Assert.Equal(200, (await locale("tema", "{\"tema\": \"scuro\"}")).Stato);
        Assert.Equal("scuro", ponte.TemaScelto);
        Assert.Equal(1, temi);
        var sbagliato = await locale("tema", "{\"tema\": 3}");
        Assert.Equal(422, sbagliato.Stato);
        Assert.Equal("scuro", ponte.TemaScelto);

        // Senza la finestra aperta il microfono non c'è; con la finestra parte Win+H (qui una prova finta).
        var senza = await locale("detta", "{}");
        Assert.Equal(422, senza.Stato);
        Assert.Equal("non_disponibile", JsonNode.Parse(senza.Json)!["codice"]!.GetValue<string>());
        var dettature = 0;
        ponte.Detta = () => dettature++;
        Assert.Equal(200, (await locale("detta", "{}")).Stato);
        Assert.Equal(1, dettature);
        Assert.Equal(405, (await ponte.GestisciAsync("GET", new Uri(Ponte.Origine + "/locale/detta"), null)).Stato);
    }

    [Fact]
    public void Il_microfono_manda_solo_Win_H_premuti_e_rilasciati_in_ordine()
    {
        var sequenza = Dettatura.Sequenza();
        Assert.Equal(new (ushort, bool)[] { (0x5B, false), (0x48, false), (0x48, true), (0x5B, true) }, sequenza);
    }
}
