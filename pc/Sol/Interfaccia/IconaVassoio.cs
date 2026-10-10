using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Sol.Interfaccia;

/// <summary>
/// L'icona vicino all'orologio: col numero delle mail urgenti in un tondo in basso a destra, oppure, se c'è un
/// problema di collegamento (computer non più riconosciuto, server irraggiungibile da mezz'ora), sbiadita e con
/// un punto esclamativo: si capisce a colpo d'occhio che Sol in questo momento non sta lavorando.
/// </summary>
public static class IconaVassoio
{
    public static Icon Base(Size? dimensione = null)
    {
        using var flusso = typeof(IconaVassoio).Assembly.GetManifestResourceStream("Sol.Risorse.sol.ico");
        if (flusso == null) return (Icon)SystemIcons.Application.Clone();
        return dimensione is Size d ? new Icon(flusso, d) : new Icon(flusso);
    }

    /// <summary>Il testo nel tondo: niente per zero, "9+" oltre nove (non c'è spazio per due cifre leggibili); "!" se c'è un problema.</summary>
    public static string? TestoNumero(int? urgenti, bool problema = false) => problema ? "!" : urgenti switch
    {
        null or <= 0 => null,
        > 9 => "9+",
        _ => urgenti.Value.ToString(System.Globalization.CultureInfo.InvariantCulture),
    };

    public static Icon ConNumero(int? urgenti, bool problema = false)
    {
        var dimensione = SystemInformation.SmallIconSize;
        var testo = TestoNumero(urgenti, problema);
        if (testo == null) return Base(dimensione);
        using var base_ = Base(new Size(32, 32));
        using var bmp = new Bitmap(dimensione.Width, dimensione.Height);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            g.Clear(Color.Transparent);
            var area = new Rectangle(0, 0, dimensione.Width, dimensione.Height);
            if (problema)
            {
                // Sbiadita: Sol c'è ma non sta lavorando.
                using var sbiadita = new ImageAttributes();
                sbiadita.SetColorMatrix(new ColorMatrix(new[]
                {
                    new[] { 0.3f, 0.3f, 0.3f, 0f, 0f },
                    new[] { 0.59f, 0.59f, 0.59f, 0f, 0f },
                    new[] { 0.11f, 0.11f, 0.11f, 0f, 0f },
                    new[] { 0f, 0f, 0f, 0.55f, 0f },
                    new[] { 0f, 0f, 0f, 0f, 1f },
                }));
                using var originale = base_.ToBitmap();
                g.DrawImage(originale, area, 0, 0, originale.Width, originale.Height, GraphicsUnit.Pixel, sbiadita);
            }
            else
            {
                g.DrawIcon(base_, area);
            }
            float lato = dimensione.Width * (testo.Length > 1 ? 0.78f : 0.62f);
            var tondo = new RectangleF(dimensione.Width - lato, dimensione.Height - dimensione.Height * 0.62f, lato, dimensione.Height * 0.62f);
            using var fondo = new SolidBrush(problema ? Color.FromArgb(0xB7, 0x79, 0x1F) : Color.FromArgb(0xB4, 0x3C, 0x12));
            using var bordo = new Pen(Color.White, Math.Max(1f, dimensione.Width / 16f));
            g.FillEllipse(fondo, tondo);
            g.DrawEllipse(bordo, tondo);
            using var carattere = new Font("Segoe UI", dimensione.Height * 0.42f, FontStyle.Bold, GraphicsUnit.Pixel);
            using var formato = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(testo, carattere, Brushes.White, tondo, formato);
        }
        var maniglia = bmp.GetHicon();
        try
        {
            using var temporanea = Icon.FromHandle(maniglia);
            return (Icon)temporanea.Clone();
        }
        finally
        {
            DestroyIcon(maniglia);
        }
    }

    /// <summary>Il testo che compare passando col mouse sull'icona.</summary>
    public static string Suggerimento(int? urgenti, bool nonRiconosciuto, bool problema) =>
        nonRiconosciuto ? "Sol: questo computer non è più collegato"
        : problema ? "Sol: il server di casa non risponde"
        : urgenti switch
        {
            null or 0 => "Sol",
            1 => "Sol: 1 mail urgente",
            _ => $"Sol: {urgenti} mail urgenti",
        };

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr hIcon);
}
