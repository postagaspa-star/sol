using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Sol.Sistema;

/// <summary>Le cartelle di Windows che servono al programma.</summary>
public static class Cartelle
{
    private static readonly Guid CartellaDownload = new("374DE290-123F-4565-9164-39C4925E467B");

    /// <summary>La cartella Download dell'utente (anche se spostata altrove); in mancanza, quella classica nel profilo.</summary>
    public static string Download()
    {
        try
        {
            if (SHGetKnownFolderPath(CartellaDownload, 0, IntPtr.Zero, out var percorso) == 0 && percorso != IntPtr.Zero)
            {
                try
                {
                    var testo = Marshal.PtrToStringUni(percorso);
                    if (!string.IsNullOrWhiteSpace(testo)) return testo;
                }
                finally
                {
                    Marshal.FreeCoTaskMem(percorso);
                }
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
        }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    /// <summary>Apre Esplora file con il file selezionato (lo zip appena scaricato): non avvia niente.</summary>
    public static void MostraFile(string file)
    {
        try
        {
            if (file.Contains('"')) return;
            // Explorer vuole proprio questa forma: /select,"percorso" (con le virgolette solo attorno al percorso).
            Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + file + "\"") { UseShellExecute = false })?.Dispose();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Motore.Log.Errore("cartella dello zip non aperta", e);
        }
    }

    [DllImport("shell32.dll")]
    private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr ppszPath);
}
