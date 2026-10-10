using Sol.Nucleo;

namespace Sol.Tests;

/// <summary>Cosa diventa una notifica di Windows e cosa no.</summary>
public class NotificheTest
{
    private static Mail Urgente(long id, string chi = "Mario Rossi", bool letta = false) => new()
    {
        Id = id,
        Mittente = new Persona { Nome = chi, Indirizzo = "rossi@esempio.it" },
        Oggetto = "Offerta capannone Bassano",
        Riassunto = "Rossi chiede l'offerta entro venerdì.",
        Urgenza = "alta",
        Letta = letta,
    };

    private static Scadenza Domani(long id, string? stato = "aperta") => new()
    {
        Id = id, MailId = 812, Descrizione = "Offerta capannone Bassano a Rossi", Quando = "2026-10-11", Stato = stato,
    };

    private static StatoNotifiche GiaPartito() => new() { Cursore = "100" };

    [Fact]
    public void Primo_giro_prende_il_cursore_e_non_notifica_gli_arretrati()
    {
        var stato = new StatoNotifiche();
        var n = Notificatore.DaMostrare(new Novita { Cursore = "8131", Urgenti = { Urgente(1) }, BozzePronte = 3, ScadenzeDomani = { Domani(4) } }, stato);

        Assert.Empty(n);
        Assert.Equal("8131", stato.Cursore);
        // E al giro dopo, le stesse cose non diventano "nuove".
        var dopo = Notificatore.DaMostrare(new Novita { Cursore = "8140", Urgenti = { Urgente(1) }, ScadenzeDomani = { Domani(4) } }, stato);
        Assert.Empty(dopo);
    }

    [Fact]
    public void Una_mail_urgente_nuova_diventa_una_notifica_che_apre_la_mail()
    {
        var stato = GiaPartito();
        var n = Notificatore.DaMostrare(new Novita { Cursore = "101", Urgenti = { Urgente(812) } }, stato);

        var notifica = Assert.Single(n);
        Assert.Equal(812, notifica.MailId);
        Assert.Contains("Mario Rossi", notifica.Titolo);
        Assert.Contains("Offerta capannone Bassano", notifica.Testo);
        Assert.Equal("101", stato.Cursore);
    }

    [Fact]
    public void La_stessa_urgente_non_suona_due_volte()
    {
        var stato = GiaPartito();
        Notificatore.DaMostrare(new Novita { Cursore = "101", Urgenti = { Urgente(812) } }, stato);
        var seconda = Notificatore.DaMostrare(new Novita { Cursore = "102", Urgenti = { Urgente(812) } }, stato);
        Assert.Empty(seconda);
    }

    [Fact]
    public void Urgente_gia_letta_non_si_notifica()
    {
        var n = Notificatore.DaMostrare(new Novita { Cursore = "101", Urgenti = { Urgente(812, letta: true) } }, GiaPartito());
        Assert.Empty(n);
    }

    [Fact]
    public void Tante_urgenti_insieme_diventano_una_notifica_sola()
    {
        var novita = new Novita { Cursore = "101" };
        for (int i = 1; i <= 5; i++) novita.Urgenti.Add(Urgente(i));
        var n = Notificatore.DaMostrare(novita, GiaPartito());

        var notifica = Assert.Single(n);
        Assert.Equal("5 mail urgenti", notifica.Titolo);
        Assert.Equal("urgenti", notifica.Sezione);
        Assert.Null(notifica.MailId);
    }

    [Fact]
    public void Bozza_nuova_pronta_apre_la_sua_mail()
    {
        var n = Notificatore.DaMostrare(new Novita { Cursore = "101", BozzePronte = 9, BozzeNuove = { new BozzaNuova { Id = 55, MailId = 812, Oggetto = "Re: Offerta capannone Bassano" } } }, GiaPartito());
        var notifica = Assert.Single(n);
        Assert.Equal("Bozza pronta da rileggere", notifica.Titolo);
        Assert.Equal("Re: Offerta capannone Bassano", notifica.Testo);
        Assert.Equal(812, notifica.MailId);
        Assert.Equal("mail/812", notifica.Destinazione);
    }

    [Fact]
    public void Sollecito_pronto_apre_niente_resta_indietro()
    {
        var notifica = Assert.Single(Notificatore.DaMostrare(new Novita { Cursore = "101", BozzeNuove = { new BozzaNuova { Id = 60, MailId = null, Oggetto = "Re: Offerta compressore" } } }, GiaPartito()));
        Assert.Equal("Sollecito pronto da rileggere", notifica.Titolo);
        Assert.Equal("indietro", notifica.Destinazione);
    }

    [Fact]
    public void Bozze_pronte_e_il_totale_da_solo_non_suona()
    {
        // §15.6/§15.13: bozze_pronte è il totale; per notificare contano solo le bozze_nuove.
        Assert.Empty(Notificatore.DaMostrare(new Novita { Cursore = "101", BozzePronte = 7 }, GiaPartito()));
    }

    [Fact]
    public void La_stessa_bozza_nuova_non_suona_due_volte()
    {
        var stato = GiaPartito();
        var bozza = new BozzaNuova { Id = 55, MailId = 812, Oggetto = "Re: Offerta" };
        Assert.Single(Notificatore.DaMostrare(new Novita { Cursore = "101", BozzeNuove = { bozza } }, stato));
        Assert.Empty(Notificatore.DaMostrare(new Novita { Cursore = "102", BozzeNuove = { bozza } }, stato));
    }

    [Fact]
    public void Un_giro_con_piu_novita_diventa_una_notifica_sola_che_apre_le_recenti()
    {
        var giro = Notificatore.DaMostrare(new Novita
        {
            Cursore = "101",
            Urgenti = { Urgente(812) },
            BozzeNuove = { new BozzaNuova { Id = 55, MailId = 811, Oggetto = "Re: Sopralluogo" } },
            ScadenzeDomani = { Domani(4) },
        }, GiaPartito());
        Assert.Equal(3, giro.Count);
        var unica = Notificatore.Raggruppa(giro)!;
        Assert.Equal("3 novità da Sol", unica.Titolo);
        Assert.Equal("notifiche", unica.Destinazione);
        Assert.Contains("Urgente da Mario Rossi", unica.Testo);
        Assert.True(unica.Testo.Length <= 250);
        Assert.Same(giro[0], Notificatore.Raggruppa(new[] { giro[0] }));
        Assert.Null(Notificatore.Raggruppa(Array.Empty<Notifica>()));
    }

    [Fact]
    public void Clic_mentre_la_notifica_e_a_schermo_apre_la_sua_mail_dopo_apre_le_recenti()
    {
        var clic = new ClicNotifiche();
        var t0 = new DateTimeOffset(2026, 10, 10, 9, 0, 0, TimeSpan.FromHours(2));
        var rossi = new Notifica("Urgente da Mario Rossi", "Offerta", MailId: 812);
        var bianchi = new Notifica("Urgente da Laura Bianchi", "Sopralluogo", MailId: 813);

        clic.Mostrata(rossi, t0);
        Assert.Equal("mail/812", clic.Cliccata());

        clic.Mostrata(rossi, t0);
        clic.Chiusa(t0.AddSeconds(7));
        // Clic dal Centro notifiche, su una notifica già sparita: non si sa quale, si apre l'elenco.
        Assert.Equal("notifiche", clic.Cliccata());

        // Il "chiusa" della precedente che arriva subito dopo aver mostrato la nuova non conta.
        clic.Mostrata(rossi, t0);
        clic.Mostrata(bianchi, t0.AddSeconds(7));
        clic.Chiusa(t0.AddSeconds(7.2));
        Assert.Equal("mail/813", clic.Cliccata());
    }

    [Fact]
    public void Le_notifiche_recenti_ricordano_ognuna_la_sua_mail()
    {
        var clic = new ClicNotifiche();
        var t = new DateTimeOffset(2026, 10, 10, 9, 0, 0, TimeSpan.FromHours(2));
        clic.Registra(new[] { new Notifica("A", "a", MailId: 1), new Notifica("B", "b", MailId: 2) }, t);
        clic.Registra(new[] { new Notifica("C", "c", Sezione: "scadenze") }, t.AddMinutes(2));
        var recenti = clic.Recenti;
        Assert.Equal(new[] { "scadenze", "mail/2", "mail/1" }, recenti.Select(r => r.Notifica.Destinazione));
        for (int i = 0; i < 40; i++) clic.Registra(new[] { new Notifica("X", "x", MailId: 100 + i) }, t);
        Assert.Equal(30, clic.Recenti.Count);
    }

    [Fact]
    public void Le_sole_da_rispondere_nuove_non_suonano()
    {
        var n = Notificatore.DaMostrare(new Novita { Cursore = "101", DaRispondereNuove = 4 }, GiaPartito());
        Assert.Empty(n);
    }

    [Fact]
    public void Scadenza_di_domani_una_volta_sola_e_solo_se_aperta()
    {
        var stato = GiaPartito();
        var prima = Notificatore.DaMostrare(new Novita { Cursore = "101", ScadenzeDomani = { Domani(4), Domani(5, "fatta") } }, stato);
        var notifica = Assert.Single(prima);
        Assert.Equal("Scadenza domani", notifica.Titolo);
        Assert.Equal("scadenze", notifica.Sezione);
        Assert.Contains("Offerta capannone", notifica.Testo);

        var dopo = Notificatore.DaMostrare(new Novita { Cursore = "102", ScadenzeDomani = { Domani(4) } }, stato);
        Assert.Empty(dopo);
    }

    [Fact]
    public void Niente_di_nuovo_niente_notifiche()
    {
        Assert.Empty(Notificatore.DaMostrare(new Novita { Cursore = "101" }, GiaPartito()));
    }

    [Fact]
    public void I_testi_lunghi_si_accorciano_per_windows()
    {
        var m = Urgente(9);
        var lunga = m with { Riassunto = new string('x', 600) };
        var n = Assert.Single(Notificatore.DaMostrare(new Novita { Cursore = "101", Urgenti = { lunga } }, GiaPartito()));
        Assert.True(n.Testo.Length <= 240);
        Assert.EndsWith("…", n.Testo);
    }

    [Fact]
    public void La_memoria_di_cosa_e_stato_mostrato_non_cresce_senza_fine()
    {
        var stato = GiaPartito();
        for (int giro = 0; giro < 200; giro++)
        {
            var novita = new Novita { Cursore = giro.ToString() };
            for (int i = 0; i < 3; i++) novita.Urgenti.Add(Urgente(giro * 10 + i));
            Notificatore.DaMostrare(novita, stato);
        }
        Assert.True(stato.UrgentiMostrate.Count <= 500);
    }

    [Fact]
    public async Task Il_motore_chiede_le_novita_col_cursore_e_aggiorna_le_urgenti()
    {
        int giro = 0;
        using var banco = new Banco(r =>
        {
            if (r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal))
            {
                giro++;
                return giro == 1
                    ? (200, "{\"cursore\": \"500\"}")
                    : (200, "{\"cursore\": \"510\", \"urgenti\": [{\"id\": 812, \"oggetto\": \"Offerta capannone Bassano\", \"urgenza\": \"alta\", \"letta\": false, \"mittente\": {\"nome\": \"Mario Rossi\"}}], \"bozze_pronte\": 0, \"da_rispondere_nuove\": 1, \"scadenze_domani\": []}");
            }
            if (r.Percorso.StartsWith("/api/mail?", StringComparison.Ordinal))
            {
                return (200, "{\"mail\": [{\"id\": 812, \"urgenza\": \"alta\", \"letta\": false}, {\"id\": 813, \"urgenza\": \"alta\", \"letta\": false}], \"altre\": false}");
            }
            return (404, "{}");
        });
        var notifiche = new List<Notifica>();
        var urgenti = new List<int?>();
        banco.Motore.NuovaNotifica += notifiche.Add;
        banco.Motore.UrgentiCambiati += urgenti.Add;

        await banco.Motore.GiroNovitaAsync();
        await banco.Motore.GiroNovitaAsync();

        var chiamate = banco.Richieste("GET", "/api/novita").ToList();
        Assert.Equal("/api/novita", chiamate[0].Percorso);
        Assert.Equal("/api/novita?dal=500", chiamate[1].Percorso);
        Assert.Equal(812, Assert.Single(notifiche).MailId);
        Assert.Equal(new int?[] { 2 }, urgenti);
        // Server di prima della v1.1 (niente /api/conteggi): si contano le urgenti di una pagina.
        Assert.Contains(banco.Finto.Ricevute, r => r.Percorso == "/api/mail?urgenza=alta&letta=false&limite=50");
        // Il cursore resta anche dopo un riavvio del programma.
        Assert.Contains("510", File.ReadAllText(banco.Percorsi.Notifiche));
    }

    [Fact]
    public async Task Le_urgenti_dell_icona_vengono_da_api_conteggi()
    {
        using var banco = new Banco(r => r.Percorso switch
        {
            "/api/novita" => (200, "{\"cursore\": \"5\"}"),
            "/api/conteggi" => (200, "{\"da_rispondere\": 5, \"urgenti\": 3, \"da_controllare\": 1, \"da_leggere\": 12, \"bozze_pronte\": 4, \"gruppi\": {}}"),
            _ => (404, "{}"),
        });
        var urgenti = new List<int?>();
        banco.Motore.UrgentiCambiati += urgenti.Add;

        await banco.Motore.GiroNovitaAsync();

        Assert.Equal(new int?[] { 3 }, urgenti);
        Assert.DoesNotContain(banco.Finto.Ricevute, r => r.Percorso.StartsWith("/api/mail", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Un_giro_mostra_una_notifica_sola_e_le_tiene_tutte_tra_le_recenti()
    {
        int giro = 0;
        using var banco = new Banco(r =>
        {
            if (!r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal)) return (404, "{}");
            return ++giro == 1
                ? (200, "{\"cursore\": \"1\"}")
                : (200, "{\"cursore\": \"2\", \"urgenti\": [{\"id\": 812, \"oggetto\": \"Offerta\", \"urgenza\": \"alta\", \"mittente\": {\"nome\": \"Mario Rossi\"}}, {\"id\": 813, \"oggetto\": \"Sopralluogo\", \"urgenza\": \"alta\", \"mittente\": {\"nome\": \"Laura Bianchi\"}}], \"bozze_nuove\": [{\"id\": 55, \"mail_id\": 812, \"oggetto\": \"Re: Offerta\"}]}");
        });
        var aSchermo = new List<Notifica>();
        banco.Motore.NuovaNotifica += aSchermo.Add;

        await banco.Motore.GiroNovitaAsync();
        await banco.Motore.GiroNovitaAsync();

        var unica = Assert.Single(aSchermo);
        Assert.Equal("3 novità da Sol", unica.Titolo);
        Assert.Equal(new[] { "mail/812", "mail/812", "mail/813" }, banco.Motore.Notifiche.Recenti.Select(n => n.Notifica.Destinazione).OrderBy(x => x, StringComparer.Ordinal));
        var recenti = banco.Motore.NotificheRecenti()["notifiche"]!.AsArray();
        Assert.Equal(3, recenti.Count);
    }

    [Fact]
    public async Task Senza_abbinamento_il_motore_non_chiede_niente()
    {
        using var banco = new Banco(_ => (200, "{}"), abbinato: false);
        await banco.Motore.GiroNovitaAsync();
        Assert.Empty(banco.Finto.Ricevute);
    }
}
