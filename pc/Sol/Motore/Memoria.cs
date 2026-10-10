using System.Security.Cryptography;
using System.Text;
using Sol.Nucleo;
using Sol.Sistema;

namespace Sol.Motore;

/// <summary>
/// Gli ultimi dati visti, per quando il server non risponde: ogni lettura riuscita (GET) si
/// tiene, cifrata con DPAPI (dentro ci sono mail), un file per richiesta. Se il server non
/// risponde, la pagina riceve l'ultima copia con la sua data e lo dice chiaramente.
/// </summary>
public sealed class Memoria
{
    private const int MassimoFile = 400;
    private readonly string cartella;
    private readonly object blocco = new();
    private int scrittureDallaPotatura;

    public Memoria(string cartella)
    {
        this.cartella = cartella;
    }

    public sealed record Voce(string Chiave, DateTimeOffset SalvataIl, string Corpo);

    public void Salva(string chiave, string corpo, DateTimeOffset adesso)
    {
        try
        {
            var dati = Json.Scrivi(new Voce(chiave, adesso, corpo));
            lock (blocco)
            {
                Archivio.ScriviTesto(File(chiave), Dpapi.Proteggi(dati));
                if (++scrittureDallaPotatura >= 50)
                {
                    scrittureDallaPotatura = 0;
                    Pota();
                }
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            Log.Errore("memoria non scritta", e);
        }
    }

    public Voce? Leggi(string chiave)
    {
        try
        {
            var f = File(chiave);
            if (!System.IO.File.Exists(f)) return null;
            var voce = Json.Leggi<Voce>(Dpapi.Svela(System.IO.File.ReadAllText(f)));
            // La chiave dentro il file deve essere la stessa: un'impronta uguale per caso non restituisce altro.
            return voce != null && voce.Chiave == chiave ? voce : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Scollegando il computer i dati delle mail non restano sul disco.</summary>
    public void Svuota()
    {
        lock (blocco)
        {
            try
            {
                if (Directory.Exists(cartella)) Directory.Delete(cartella, recursive: true);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                Log.Errore("memoria non svuotata", e);
            }
        }
    }

    private string File(string chiave)
    {
        var impronta = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(chiave)))[..32].ToLowerInvariant();
        return Path.Combine(cartella, impronta + ".dat");
    }

    private void Pota()
    {
        if (!Directory.Exists(cartella)) return;
        var file = new DirectoryInfo(cartella).GetFiles("*.dat").OrderByDescending(f => f.LastWriteTimeUtc).Skip(MassimoFile);
        foreach (var f in file)
        {
            try { f.Delete(); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        }
    }
}
