using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace Sol.Sistema;

/// <summary>
/// Cifratura DPAPI dell'utente di Windows: solo questo utente, su questo computer, può
/// decifrare. Serve per il token e per i dati delle mail tenuti per quando il server non risponde.
/// </summary>
public static class Dpapi
{
    private static readonly byte[] Entropia = Encoding.UTF8.GetBytes("Sol.Computer.v1");

    public static string Proteggi(string segreto) =>
        Convert.ToBase64String(Esegui(Encoding.UTF8.GetBytes(segreto), proteggi: true));

    public static string? Svela(string? protetto)
    {
        if (string.IsNullOrEmpty(protetto)) return null;
        try
        {
            return Encoding.UTF8.GetString(Esegui(Convert.FromBase64String(protetto), proteggi: false));
        }
        catch (Exception e) when (e is FormatException or Win32Exception)
        {
            return null;
        }
    }

    private static byte[] Esegui(byte[] dati, bool proteggi)
    {
        var ingresso = new DATA_BLOB();
        var entropia = new DATA_BLOB();
        var uscita = new DATA_BLOB();
        var hDati = GCHandle.Alloc(dati, GCHandleType.Pinned);
        var hEntropia = GCHandle.Alloc(Entropia, GCHandleType.Pinned);
        try
        {
            ingresso.cbData = dati.Length;
            ingresso.pbData = hDati.AddrOfPinnedObject();
            entropia.cbData = Entropia.Length;
            entropia.pbData = hEntropia.AddrOfPinnedObject();
            bool ok = proteggi
                ? CryptProtectData(ref ingresso, "Sol", ref entropia, IntPtr.Zero, IntPtr.Zero, CRYPTPROTECT_UI_FORBIDDEN, out uscita)
                : CryptUnprotectData(ref ingresso, IntPtr.Zero, ref entropia, IntPtr.Zero, IntPtr.Zero, CRYPTPROTECT_UI_FORBIDDEN, out uscita);
            if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error());
            var risultato = new byte[uscita.cbData];
            Marshal.Copy(uscita.pbData, risultato, 0, uscita.cbData);
            return risultato;
        }
        finally
        {
            hDati.Free();
            hEntropia.Free();
            if (uscita.pbData != IntPtr.Zero) LocalFree(uscita.pbData);
        }
    }

    private const int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct DATA_BLOB
    {
        public int cbData;
        public IntPtr pbData;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(ref DATA_BLOB pDataIn, string szDataDescr, ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved, IntPtr pPromptStruct, int dwFlags, out DATA_BLOB pDataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(ref DATA_BLOB pDataIn, IntPtr ppszDataDescr, ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved, IntPtr pPromptStruct, int dwFlags, out DATA_BLOB pDataOut);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr hMem);
}
