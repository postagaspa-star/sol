namespace Sol.Nucleo;

/// <summary>Una notifica di Windows: col clic apre la mail (<see cref="MailId"/>) oppure una sezione della finestra.</summary>
public sealed record Notifica(string Titolo, string Testo, long? MailId = null, string? Sezione = null)
{
    /// <summary>Il frammento della finestra da aprire: la mail, la sezione, oppure la posta.</summary>
    public string Destinazione => MailId is long id && id > 0
        ? "mail/" + id.ToString(System.Globalization.CultureInfo.InvariantCulture)
        : string.IsNullOrEmpty(Sezione) ? "posta" : Sezione!;
}

/// <summary><c>notifiche.json</c>: il cursore delle novità e cosa è già stato mostrato.</summary>
public sealed class StatoNotifiche
{
    public string? Cursore { get; set; }
    public List<long> UrgentiMostrate { get; set; } = new();
    public List<long> ScadenzeMostrate { get; set; } = new();
    public List<long> BozzeMostrate { get; set; } = new();
}

/// <summary>
/// Cosa notificare dalle novità (§12, §15.6): solo le cose nuove che chiedono attenzione. Mail urgenti,
/// bozze diventate pronte (<c>bozze_nuove</c>), scadenze di domani sì; le semplici "da rispondere" nuove no
/// (suonerebbe a ogni mail), e nemmeno <c>bozze_pronte</c>, che dalla v1.1 è il totale e non le nuove.
/// Il primo giro serve solo a prendere il cursore: niente arretrati.
/// </summary>
public static class Notificatore
{
    public const int MassimoSingole = 3;
    private const int Memoria = 500;

    public static IReadOnlyList<Notifica> DaMostrare(Novita novita, StatoNotifiche stato)
    {
        var esito = new List<Notifica>();
        bool primoGiro = stato.Cursore == null;
        if (!string.IsNullOrEmpty(novita.Cursore)) stato.Cursore = novita.Cursore;
        if (primoGiro)
        {
            // Quello che c'era già prima del primo giro conta come visto.
            stato.UrgentiMostrate.AddRange(novita.Urgenti.Select(m => m.Id));
            stato.ScadenzeMostrate.AddRange(novita.ScadenzeDomani.Select(s => s.Id));
            stato.BozzeMostrate.AddRange(novita.BozzeNuove.Select(b => b.Id));
            Pota(stato);
            return esito;
        }

        // Una possibile truffa non suona mai come "urgente" (§16.2): la sua urgenza è proprio l'esca.
        var urgenti = novita.Urgenti
            .Where(m => !m.Letta && !m.Sospetta && !stato.UrgentiMostrate.Contains(m.Id))
            .GroupBy(m => m.Id).Select(g => g.First())
            .ToList();
        if (urgenti.Count > MassimoSingole)
        {
            esito.Add(new Notifica($"{urgenti.Count} mail urgenti", "Sono arrivate mail urgenti. Fai clic per vederle.", Sezione: "urgenti"));
        }
        else
        {
            foreach (var m in urgenti)
            {
                var chi = m.Mittente?.Mostrato;
                var testo = string.IsNullOrWhiteSpace(m.Riassunto) ? m.Oggetto : $"{m.Oggetto}: {m.Riassunto}";
                esito.Add(new Notifica(
                    Frasi.Accorcia(string.IsNullOrWhiteSpace(chi) ? "Mail urgente" : $"Urgente da {chi}", 60),
                    Frasi.Accorcia(testo, 240),
                    MailId: m.Id));
            }
        }
        stato.UrgentiMostrate.AddRange(urgenti.Select(m => m.Id));

        var bozze = novita.BozzeNuove
            .Where(b => !stato.BozzeMostrate.Contains(b.Id))
            .GroupBy(b => b.Id).Select(g => g.First())
            .ToList();
        if (bozze.Count > MassimoSingole)
        {
            esito.Add(new Notifica($"{bozze.Count} bozze pronte", $"Sol ha preparato {bozze.Count} risposte da rileggere.", Sezione: "da-rispondere"));
        }
        else
        {
            foreach (var b in bozze)
            {
                // Una risposta apre la sua mail; un sollecito (senza mail) apre "Niente resta indietro".
                esito.Add(b.MailId is long mail && mail > 0
                    ? new Notifica("Bozza pronta da rileggere", Frasi.Accorcia(b.Oggetto, 240), MailId: mail)
                    : new Notifica("Sollecito pronto da rileggere", Frasi.Accorcia(b.Oggetto, 240), Sezione: "indietro"));
            }
        }
        stato.BozzeMostrate.AddRange(bozze.Select(b => b.Id));

        var scadenze = novita.ScadenzeDomani
            .Where(s => (s.Stato is null || s.Stato == "aperta") && !stato.ScadenzeMostrate.Contains(s.Id))
            .GroupBy(s => s.Id).Select(g => g.First())
            .ToList();
        if (scadenze.Count > MassimoSingole)
        {
            esito.Add(new Notifica($"{scadenze.Count} scadenze domani", "Domani scadono alcune cose. Fai clic per vederle.", Sezione: "scadenze"));
        }
        else
        {
            foreach (var s in scadenze)
            {
                var ora = string.IsNullOrWhiteSpace(s.Ora) ? "" : $" alle {s.Ora}";
                esito.Add(new Notifica("Scadenza domani" + ora, Frasi.Accorcia(s.Descrizione, 240), Sezione: "scadenze"));
            }
        }
        stato.ScadenzeMostrate.AddRange(scadenze.Select(s => s.Id));
        Pota(stato);
        return esito;
    }

    /// <summary>
    /// Le notifiche di un giro diventano UNA notifica di Windows: così non ne restano in fila e un clic arrivato
    /// tardi non può aprire la mail di un'altra. Una sola resta com'è (col clic apre la sua mail); più di una
    /// diventano un riepilogo che apre "Notifiche recenti", dove ognuna porta alla sua mail.
    /// </summary>
    public static Notifica? Raggruppa(IReadOnlyList<Notifica> giro)
    {
        if (giro.Count == 0) return null;
        if (giro.Count == 1) return giro[0];
        var righe = giro.Select(n => Frasi.Accorcia(n.Titolo + ": " + n.Testo, 80));
        return new Notifica($"{giro.Count} novità da Sol", Frasi.AccorciaRighe(righe, 250), Sezione: "notifiche");
    }

    private static void Pota(StatoNotifiche stato)
    {
        foreach (var lista in new[] { stato.UrgentiMostrate, stato.ScadenzeMostrate, stato.BozzeMostrate })
        {
            if (lista.Count > Memoria) lista.RemoveRange(0, lista.Count - Memoria);
        }
    }
}

/// <summary>Una notifica già mostrata, con l'ora: l'elenco "Notifiche recenti" della finestra.</summary>
public sealed record NotificaMostrata(DateTimeOffset Quando, Notifica Notifica);

/// <summary>
/// Cosa apre il clic su una notifica di Windows. L'icona vicino all'orologio ha una sola notifica alla volta e il clic
/// non dice QUALE è stata cliccata: se arriva mentre è ancora a schermo quella che abbiamo mostrato, si apre la sua
/// destinazione; se arriva dopo (dal Centro notifiche, magari su una vecchia) si apre "Notifiche recenti", dove
/// ognuna ricorda la sua mail. Così un clic in ritardo non apre mai la mail sbagliata.
/// </summary>
public sealed class ClicNotifiche
{
    private const int Massimo = 30;
    private static readonly TimeSpan Assestamento = TimeSpan.FromSeconds(1);
    private readonly List<NotificaMostrata> recenti = new();
    private string? aSchermo;
    private DateTimeOffset mostrataAlle;

    public IReadOnlyList<NotificaMostrata> Recenti
    {
        get { lock (recenti) return recenti.ToList(); }
    }

    /// <summary>Le notifiche singole del giro, per l'elenco (anche quando a schermo va il riepilogo).</summary>
    public void Registra(IEnumerable<Notifica> singole, DateTimeOffset quando)
    {
        lock (recenti)
        {
            foreach (var n in singole) recenti.Insert(0, new NotificaMostrata(quando, n));
            if (recenti.Count > Massimo) recenti.RemoveRange(Massimo, recenti.Count - Massimo);
        }
    }

    public void Mostrata(Notifica aSchermoAdesso, DateTimeOffset adesso)
    {
        aSchermo = aSchermoAdesso.Destinazione;
        mostrataAlle = adesso;
    }

    /// <summary>
    /// La notifica è sparita dallo schermo (scaduta o chiusa): un clic dopo è ambiguo. Un "chiusa" che arriva subito
    /// dopo averne mostrata una nuova è quello della precedente, sostituita: non conta.
    /// </summary>
    public void Chiusa(DateTimeOffset adesso)
    {
        if (adesso - mostrataAlle < Assestamento) return;
        aSchermo = null;
    }

    public string Cliccata()
    {
        var destinazione = aSchermo ?? "notifiche";
        aSchermo = null;
        return destinazione;
    }
}
