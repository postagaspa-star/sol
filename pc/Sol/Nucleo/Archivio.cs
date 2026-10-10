using System.Text;
using System.Text.Json;

namespace Sol.Nucleo;

/// <summary>
/// Scritture su disco senza file a metà: si scrive un temporaneo fino in fondo, poi lo si
/// rinomina sopra quello vecchio. Se la rinomina è vietata (antivirus, filtri), si scrive diretto.
/// </summary>
public static class Archivio
{
    public static void ScriviTesto(string percorso, string testo) => ScriviByte(percorso, new UTF8Encoding(false).GetBytes(testo));

    public static void ScriviByte(string percorso, byte[] dati)
    {
        var cartella = Path.GetDirectoryName(percorso);
        if (!string.IsNullOrEmpty(cartella)) Directory.CreateDirectory(cartella);
        var temporaneo = percorso + ".tmp";
        try
        {
            using (var fs = new FileStream(temporaneo, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                fs.Write(dati, 0, dati.Length);
                fs.Flush(flushToDisk: true);
            }
            for (int tentativo = 1; ; tentativo++)
            {
                try
                {
                    File.Move(temporaneo, percorso, overwrite: true);
                    return;
                }
                catch (Exception e) when ((e is IOException or UnauthorizedAccessException) && tentativo < 5)
                {
                    // Un antivirus tiene a volte aperto per un attimo il file appena scritto.
                    Thread.Sleep(40 * tentativo);
                }
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            try { File.Delete(temporaneo); } catch (Exception) { }
            File.WriteAllBytes(percorso, dati);
        }
    }

    public static void ScriviJson<T>(string percorso, T valore) => ScriviTesto(percorso, JsonSerializer.Serialize(valore, Json.File));

    /// <summary>Il file come oggetto, oppure null se manca o è rovinato (si riparte da zero, senza fermarsi).</summary>
    public static T? LeggiJson<T>(string percorso) where T : class
    {
        try
        {
            if (!File.Exists(percorso)) return null;
            return JsonSerializer.Deserialize<T>(File.ReadAllText(percorso, Encoding.UTF8), Json.File);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }
}
