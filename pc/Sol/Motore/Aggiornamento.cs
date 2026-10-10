using System.Security.Cryptography;
using Sol.Nucleo;
using Sol.Rete;

namespace Sol.Motore;

/// <summary>Una versione del programma più nuova di questa, annunciata dal server (§3, §15.10).</summary>
public sealed record VersioneNuova(int Codice, string Nome, string IndirizzoZip, string? Sha256, long? Byte);

/// <summary>Com'è andato lo scaricamento della versione nuova.</summary>
public sealed record EsitoScaricamento(bool Ok, string? File, string? Errore);

/// <summary>
/// L'aggiornamento, come in Pactum: ogni 12 ore si legge <c>GET /api/versione</c>; se <c>pc.codice</c> è più alto
/// del nostro, Sol lo dice (notifica, striscia, Impostazioni). Col clic scarica lo zip servito dal server
/// (<c>GET /scarica/sol-pc.zip</c>) nella cartella Download, ne controlla impronta e dimensione (§15.10) e lo
/// scarta se non coincidono; se va bene, apre la cartella col file selezionato: lo scompatta Manuel.
/// Il programma non scompatta e non lancia eseguibili da solo: con la prima versione, che lo faceva,
/// l'antivirus di casa (Bitdefender) teneva bloccata la Sol.dll appena compilata; senza quella parte no.
/// </summary>
public sealed class Aggiornatore
{
    public const string FileZip = "sol-pc.zip";
    private readonly ClientSol client;
    private readonly int codiceMio;
    private int? giaSegnalata;

    public Aggiornatore(ClientSol client, int codiceMio)
    {
        this.client = client;
        this.codiceMio = codiceMio;
    }

    /// <summary>L'ultima versione nuova vista (null se siamo già all'ultima).</summary>
    public VersioneNuova? Disponibile { get; private set; }

    /// <summary>L'ultima versione annunciata dal server, anche se uguale alla nostra (per le Impostazioni).</summary>
    public VersioneApp? Annunciata { get; private set; }

    public static bool ÈPiùNuova(InfoVersione? v, int codiceMio) => v?.Pc != null && v.Pc.Codice > codiceMio;

    public static string IndirizzoDelloZip(string server) => server.TrimEnd('/') + "/scarica/" + FileZip;

    /// <summary>
    /// Chiede la versione al server. Restituisce la versione nuova solo la prima volta che la si vede (per non
    /// ripetere la notifica a ogni giro); <see cref="Disponibile"/> resta impostata per la pagina.
    /// </summary>
    public async Task<VersioneNuova?> ControllaAsync(string server, CancellationToken annulla = default)
    {
        var r = await client.InviaAsync("GET", server, "api/versione", null, null, annulla: annulla).ConfigureAwait(false);
        if (!r.Ok) return null;
        var info = Json.Leggi<InfoVersione>(r.Corpo);
        Annunciata = info?.Pc;
        if (!ÈPiùNuova(info, codiceMio))
        {
            Disponibile = null;
            return null;
        }
        var pc = info!.Pc!;
        Disponibile = new VersioneNuova(pc.Codice, pc.Nome ?? pc.Codice.ToString(System.Globalization.CultureInfo.InvariantCulture),
            IndirizzoDelloZip(server), string.IsNullOrWhiteSpace(pc.Sha256) ? null : pc.Sha256.Trim(), pc.Byte);
        if (giaSegnalata == pc.Codice) return null;
        giaSegnalata = pc.Codice;
        Log.Info($"versione nuova disponibile: {Disponibile.Nome} (codice {pc.Codice})");
        return Disponibile;
    }

    /// <summary>
    /// Scarica lo zip della versione nuova in <paramref name="cartella"/> e lo tiene solo se impronta SHA-256 e
    /// dimensione sono quelle annunciate dal server. Senza impronta non scarica: non si potrebbe controllare.
    /// </summary>
    public async Task<EsitoScaricamento> ScaricaAsync(string cartella, CancellationToken annulla = default)
    {
        var v = Disponibile;
        if (v == null) return new EsitoScaricamento(false, null, "Non c'è una versione nuova da scaricare.");
        if (v.Sha256 == null || v.Sha256.Length != 64)
        {
            return new EsitoScaricamento(false, null, "Il server non indica l'impronta della versione nuova, quindi Sol non può controllare il file. Va aggiornato prima il server.");
        }
        var nome = "sol-pc-" + string.Concat(v.Nome.Where(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-')) + ".zip";
        var file = Path.Combine(cartella, nome);
        var provvisorio = file + ".scaricamento";
        try
        {
            Directory.CreateDirectory(cartella);
            if (!await client.ScaricaAsync(v.IndirizzoZip, provvisorio, annulla).ConfigureAwait(false))
            {
                return new EsitoScaricamento(false, null, "Sol non è riuscito a scaricare la versione nuova dal server di casa. Riprova più tardi.");
            }
            var controllo = Controlla(provvisorio, v.Sha256, v.Byte);
            if (controllo != null)
            {
                File.Delete(provvisorio);
                Log.Avviso("zip della versione nuova scartato: " + controllo);
                return new EsitoScaricamento(false, null, "Il file scaricato non è quello giusto (" + controllo + "): Sol l'ha scartato. Riprova più tardi.");
            }
            File.Move(provvisorio, file, overwrite: true);
            Log.Info($"versione {v.Nome} scaricata e controllata");
            return new EsitoScaricamento(true, file, null);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            try { File.Delete(provvisorio); } catch (Exception) { }
            Log.Errore("zip della versione nuova non salvato", e);
            return new EsitoScaricamento(false, null, "Sol non è riuscito a salvare il file nella cartella Download.");
        }
    }

    /// <summary>null se il file è quello annunciato, altrimenti cosa non torna (in parole semplici).</summary>
    public static string? Controlla(string file, string sha256Atteso, long? byteAttesi)
    {
        var info = new FileInfo(file);
        if (byteAttesi is long attesi && info.Length != attesi) return "la dimensione non coincide";
        using var flusso = File.OpenRead(file);
        var impronta = Convert.ToHexString(SHA256.HashData(flusso));
        return string.Equals(impronta, sha256Atteso, StringComparison.OrdinalIgnoreCase) ? null : "l'impronta non coincide";
    }
}
