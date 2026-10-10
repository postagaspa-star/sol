using System.Text;

namespace Sol.Motore;

/// <summary>
/// I testi della finestra di conferma prima dell'invio (§15.11): destinatari, Cc, oggetto e la prima riga del testo
/// DELLA VERSIONE che parte; per un sollecito anche a quale mail inviata si riferisce.
/// </summary>
public static class ConfermaInvio
{
    public const string Titolo = "Inviare questa mail?";
    public const string TitoloSollecito = "Inviare questo sollecito?";
    public const string PulsanteInvia = "Invia";
    public const string PulsanteAnnulla = "Non inviare";
    public const string AvvisoGiaRisposto = "Hai già risposto a questa mail da un altro programma: vuoi mandare anche questa?";
    public const string AvvisoTruffa = "Stai rispondendo a una mail segnalata come possibile truffa";
    public const string PulsanteInviaLoStesso = "Invia lo stesso";

    /// <summary>Il titolo: per una risposta a una possibile truffa è l'avviso stesso (in rosso nella finestra).</summary>
    public static string TitoloPer(AnteprimaInvio a) => a.Sospetta ? AvvisoTruffa : a.Tipo == "sollecito" ? TitoloSollecito : Titolo;

    /// <summary>"Invia lo stesso" se si risponde a una possibile truffa (§16.3), altrimenti "Invia".</summary>
    public static string PulsantePer(AnteprimaInvio a) => a.Sospetta ? PulsanteInviaLoStesso : PulsanteInvia;

    public static string Testo(AnteprimaInvio a)
    {
        var t = new StringBuilder();
        if (a.Sospetta)
        {
            // L'avviso c'è anche nel testo, non solo nel colore: si legge comunque.
            t.AppendLine(AvvisoTruffa + ". Se ti chiede soldi, coordinate bancarie o documenti, prima telefona al mittente a un numero che conosci già.")
                .AppendLine();
        }
        if (a.GiaRisposto) t.AppendLine(AvvisoGiaRisposto).AppendLine();
        if (a.Tipo == "sollecito" && !string.IsNullOrWhiteSpace(a.SollecitoPer)) t.Append("Sollecito per: ").AppendLine(a.SollecitoPer.Trim()).AppendLine();
        t.Append("A: ").AppendLine(Elenco(a.A));
        if (a.Cc.Count > 0) t.Append("Cc: ").AppendLine(Elenco(a.Cc));
        t.Append("Oggetto: ").AppendLine(string.IsNullOrWhiteSpace(a.Oggetto) ? "(senza oggetto)" : a.Oggetto.Trim());
        t.Append("Comincia con: «").Append(a.PrimaRiga).AppendLine("»");
        t.AppendLine();
        t.Append(a.Tipo == "sollecito"
            ? "Il sollecito parte adesso dalla casella della mail originale."
            : "La risposta parte adesso dalla casella in cui è arrivata la mail.");
        return t.ToString();
    }

    /// <summary>Fino a 8 indirizzi per intero; oltre, quanti altri ce ne sono. Nessuno resta nascosto senza dirlo.</summary>
    public static string Elenco(IReadOnlyList<string> indirizzi)
    {
        const int massimo = 8;
        var mostrati = string.Join(", ", indirizzi.Take(massimo).Select(x => x.Trim()));
        return indirizzi.Count > massimo ? $"{mostrati} e altri {indirizzi.Count - massimo}" : mostrati;
    }

    /// <summary>La prima riga non vuota del testo, accorciata: basta a riconoscere quale risposta sta partendo.</summary>
    public static string PrimaRiga(string? testo)
    {
        var riga = (testo ?? "").Split('\n').Select(r => r.Trim()).FirstOrDefault(r => r.Length > 0) ?? "";
        return Nucleo.Frasi.Accorcia(riga, 120);
    }
}
