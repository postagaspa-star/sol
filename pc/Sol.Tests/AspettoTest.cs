using System.Drawing;
using System.Text.RegularExpressions;
using Sol.Sistema;

namespace Sol.Tests;

/// <summary>
/// Aspetto 1.3 (docs/aspetto.md, contratto §17), dal lato del programma: versione 1.1.0, fondo della finestra
/// uguale a quello della pagina nei due temi, e i 12 colori dei gruppi uguali in pagina, logica e contratto.
/// Il comportamento della pagina (Da fare, colori dai nomi, barra in basso) lo provano i test JS in
/// pc/prove/js, lanciati da <see cref="InterfacciaJsTest"/>.
/// </summary>
public class AspettoTest
{
    /// <summary>I nomi del contratto (§17), nell'ordine in cui il server li assegna ai gruppi nuovi.</summary>
    private static readonly string[] ColoriContratto =
        { "blu", "verde", "ambra", "viola", "grigio", "ardesia", "petrolio", "rosa", "indaco", "oliva", "marrone", "ciano" };

    private static string Ui(string file) => File.ReadAllText(Path.Combine(Sorgenti.CartellaPc(), "ui", file));

    [Fact]
    public void Versione_1_1_0_uguale_nel_codice_e_nel_progetto()
    {
        Assert.Equal("1.1.0", Versione.Nome);
        Assert.Equal(2, Versione.Codice);
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

    [Fact]
    public void Il_fondo_della_finestra_e_quello_della_pagina_nei_due_temi()
    {
        var css = Ui("stile.css");
        var chiaro = Regex.Match(css, @":root \{[^}]*--fondo: (#[0-9A-F]{6});").Groups[1].Value;
        var scuro = Regex.Match(css, @"@media \(prefers-color-scheme: dark\) \{\s*:root \{[^}]*--fondo: (#[0-9A-F]{6});").Groups[1].Value;
        Assert.Equal("#FFFBF7", chiaro);
        Assert.Equal("#14110F", scuro);
        Assert.Equal(ColorTranslator.FromHtml(chiaro).ToArgb(), Tema.Fondo(false).ToArgb());
        Assert.Equal(ColorTranslator.FromHtml(scuro).ToArgb(), Tema.Fondo(true).ToArgb());
    }

    [Fact]
    public void I_12_colori_dei_gruppi_sono_quelli_del_contratto_in_pagina_e_nella_logica()
    {
        var css = Ui("stile.css");
        var classi = Regex.Matches(css, @"^\.col-(\w+) \{ --g: #[0-9A-F]{6}; --g-sf: #[0-9A-F]{6}; --g-tx: #[0-9A-F]{6}; \}", RegexOptions.Multiline)
            .Select(m => m.Groups[1].Value).ToArray();
        Assert.Equal(ColoriContratto.OrderBy(x => x), classi.OrderBy(x => x));
        foreach (var c in ColoriContratto)
            Assert.Matches(@"\n  \.col-" + c + @" \{ --g-sf: #[0-9A-F]{6}; --g-tx: #[0-9A-F]{6}; \}", css); // tinte del tema scuro

        var logica = Ui("logica.js");
        var elenco = Regex.Match(logica, @"const COLORI_GRUPPI = \[([^\]]*)\];").Groups[1].Value;
        Assert.Equal(ColoriContratto, Regex.Matches(elenco, @"'(\w+)'").Select(m => m.Groups[1].Value).ToArray());
        Assert.Contains("COLORI_GRUPPI.includes(n) ? n : 'ardesia'", logica); // nome sconosciuto → ardesia
    }

    [Fact]
    public void Meno_movimenti_se_Windows_lo_chiede()
    {
        var css = Ui("stile.css");
        Assert.Matches(@"@media \(prefers-reduced-motion: reduce\) \{\s*\*, \*::before, \*::after \{ animation: none !important; transition: none !important; \}", css);
        Assert.DoesNotContain("infinite", css);
    }
}
