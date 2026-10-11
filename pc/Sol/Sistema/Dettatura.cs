using System.Runtime.InteropServices;

namespace Sol.Sistema;

/// <summary>
/// Il microfono della pagina: apre la dettatura di Windows (la stessa di Win+H) nel campo dove c'è il cursore.
/// Manda solo i tasti Win+H alla finestra di Sol, niente altro. Dove Windows non lo permette non succede niente.
/// </summary>
public static class Dettatura
{
    private const ushort TastoWin = 0x5B; // VK_LWIN
    private const ushort TastoH = 0x48;   // 'H'
    private const uint Rilascio = 0x0002; // KEYEVENTF_KEYUP
    private const uint Tastiera = 1;      // INPUT_KEYBOARD

    [StructLayout(LayoutKind.Sequential)]
    private struct Ingresso
    {
        public uint Tipo;
        public DatiTasto Tasto;
    }

    // La parte "tastiera" di INPUT; lo spazio in più copre l'unione con il mouse (la più grande).
    [StructLayout(LayoutKind.Sequential)]
    private struct DatiTasto
    {
        public ushort CodiceVirtuale;
        public ushort CodiceScansione;
        public uint Opzioni;
        public uint Tempo;
        public IntPtr Extra;
        public uint Riempimento1;
        public uint Riempimento2;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint quanti, Ingresso[] ingressi, int dimensione);

    /// <summary>I quattro eventi di Win+H: Win giù, H giù, H su, Win su.</summary>
    internal static (ushort Tasto, bool Su)[] Sequenza() =>
        new[] { (TastoWin, false), (TastoH, false), (TastoH, true), (TastoWin, true) };

    public static bool Apri()
    {
        var eventi = Sequenza().Select(e => new Ingresso
        {
            Tipo = Tastiera,
            Tasto = new DatiTasto { CodiceVirtuale = e.Tasto, Opzioni = e.Su ? Rilascio : 0 },
        }).ToArray();
        try
        {
            return SendInput((uint)eventi.Length, eventi, Marshal.SizeOf<Ingresso>()) == eventi.Length;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
    }
}
