using System.Diagnostics;

namespace Sol.Tests;

/// <summary>
/// I test della logica della pagina (scorciatoie di smistamento, selezione, riferimenti [#id]...) sono in
/// pc/prove/js e girano con Node. Qui li si lancia, così `dotnet test` copre anche quelli.
/// Senza Node sul computer il test lo dice e passa (non si può provare quello che non c'è).
/// </summary>
public class InterfacciaJsTest
{
    [Fact]
    public async Task Test_della_pagina_con_node()
    {
        var cartella = Path.Combine(Sorgenti.CartellaPc(), "prove", "js");
        Assert.True(Directory.Exists(cartella), "manca " + cartella);
        var file = Directory.GetFiles(cartella, "*.test.js");
        Assert.NotEmpty(file);

        var avvio = new ProcessStartInfo("node") { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, WorkingDirectory = cartella };
        avvio.ArgumentList.Add("--test");
        foreach (var f in file) avvio.ArgumentList.Add(f);
        Process? processo;
        try
        {
            processo = Process.Start(avvio);
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return; // Node non c'è: i test JS si lanciano a mano con `node --test pc/prove/js`.
        }
        using var p = processo!;
        var uscita = p.StandardOutput.ReadToEndAsync();
        var errori = p.StandardError.ReadToEndAsync();
        using var limite = new CancellationTokenSource(TimeSpan.FromSeconds(180));
        await p.WaitForExitAsync(limite.Token);
        Assert.True(p.ExitCode == 0, await uscita + await errori);
    }
}
