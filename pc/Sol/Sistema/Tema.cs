using System.Drawing;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Sol.Sistema;

/// <summary>
/// Il tema di Windows (chiaro o scuro). La pagina lo segue da sola (<c>prefers-color-scheme</c>); qui serve
/// per lo sfondo della finestra prima che la pagina sia pronta e per la barra del titolo, così niente lampo
/// bianco col tema scuro. I due fondi sono quelli della guida (docs/aspetto.md): #FFFBF7 e #14110F.
/// </summary>
public static class Tema
{
    public static readonly Color FondoChiaro = Color.FromArgb(0xFF, 0xFB, 0xF7);
    public static readonly Color FondoScuro = Color.FromArgb(0x14, 0x11, 0x0F);

    private const string ChiaveTema = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";

    /// <summary>true se Windows usa il tema scuro per le app (AppsUseLightTheme = 0). Nel dubbio: chiaro.</summary>
    public static bool Scuro()
    {
        try
        {
            using var chiave = Registry.CurrentUser.OpenSubKey(ChiaveTema);
            return ScuroDa(chiave?.GetValue("AppsUseLightTheme"));
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Il valore di AppsUseLightTheme: 0 = scuro, qualunque altra cosa (o niente) = chiaro.</summary>
    public static bool ScuroDa(object? valore) => valore is int numero && numero == 0;

    public static Color Fondo(bool scuro) => scuro ? FondoScuro : FondoChiaro;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr finestra, int attributo, ref int valore, int dimensione);

    private const int UsaModoScuro = 20;        // DWMWA_USE_IMMERSIVE_DARK_MODE (Windows 10 20H1 e successivi)
    private const int UsaModoScuroVecchio = 19; // lo stesso, sulle versioni di Windows 10 prima della 20H1

    /// <summary>Barra del titolo scura col tema scuro. Dove Windows non lo permette non fa niente.</summary>
    public static void BarraDelTitolo(IntPtr finestra, bool scuro)
    {
        if (finestra == IntPtr.Zero) return;
        var valore = scuro ? 1 : 0;
        try
        {
            if (DwmSetWindowAttribute(finestra, UsaModoScuro, ref valore, sizeof(int)) != 0)
                DwmSetWindowAttribute(finestra, UsaModoScuroVecchio, ref valore, sizeof(int));
        }
        catch (DllNotFoundException)
        {
        }
        catch (EntryPointNotFoundException)
        {
        }
    }
}
