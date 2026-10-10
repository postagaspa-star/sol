using Sol.Nucleo;

namespace Sol.Tests;

/// <summary>Le forme di docs/contratto-api.md lette come le manda il server.</summary>
public class ContrattoTest
{
    private const string MailEsempio = """
    {
      "id": 812, "casella_id": 1, "casella": "studio@esempio.it",
      "mittente": {"nome": "Mario Rossi", "indirizzo": "rossi@esempio.it"},
      "oggetto": "Offerta capannone Bassano",
      "anteprima": "Buongiorno, ci servirebbe l'offerta entro venerdì...",
      "riassunto": "Rossi chiede l'offerta per il capannone di Bassano entro venerdì.",
      "ricevuta_il": "2026-10-10T08:12:00+02:00",
      "letta": false, "risposta_data": false,
      "gruppo_id": 1, "gruppo": "Clienti",
      "da_rispondere": true, "urgenza": "alta",
      "sicurezza": 0.93,
      "smistamento": "smistata",
      "origine_smistamento": "decisore",
      "applicata_in_casella": false,
      "bozza": {"id": 55, "stato": "pronta"},
      "allegati": [{"nome": "pianta.pdf", "byte": 482113}]
    }
    """;

    [Fact]
    public void Elenco_mail_del_contratto()
    {
        var elenco = Json.Leggi<ElencoMail>("{\"mail\": [" + MailEsempio + "], \"altre\": true}");

        Assert.NotNull(elenco);
        Assert.True(elenco!.Altre);
        var m = Assert.Single(elenco.Mail);
        Assert.Equal(812, m.Id);
        Assert.Equal("Mario Rossi", m.Mittente!.Nome);
        Assert.Equal("rossi@esempio.it", m.Mittente.Indirizzo);
        Assert.Equal("Mario Rossi", m.Mittente.Mostrato);
        Assert.Equal("alta", m.Urgenza);
        Assert.True(m.DaRispondere);
        Assert.False(m.Letta);
        Assert.False(m.ApplicataInCasella);
        Assert.Equal(0.93, m.Sicurezza);
        Assert.Equal(55, m.Bozza!.Id);
        Assert.Equal("pronta", m.Bozza.Stato);
        Assert.Equal(new DateTimeOffset(2026, 10, 10, 8, 12, 0, TimeSpan.FromHours(2)), m.RicevutaIl);
    }

    [Fact]
    public void Mail_senza_bozza_e_campi_sconosciuti_si_legge_lo_stesso()
    {
        var m = Json.Leggi<Mail>("{\"id\": 9, \"bozza\": null, \"campo_futuro\": [1,2], \"mittente\": {\"indirizzo\": \"bianchi@esempio.it\"}}");
        Assert.NotNull(m);
        Assert.Null(m!.Bozza);
        Assert.Equal("bianchi@esempio.it", m.Mittente!.Mostrato);
    }

    [Fact]
    public void Bozza_del_contratto()
    {
        var b = Json.Leggi<Bozza>("""
        {"id": 55, "mail_id": 812, "tipo": "risposta", "stato": "pronta",
         "a": ["rossi@esempio.it"], "cc": [], "oggetto": "Re: Offerta capannone Bassano",
         "testo": "Buongiorno Mario,\n...", "istruzioni": null,
         "fornitore": "groq", "modificata": false,
         "creata_il": "2026-10-10T08:13:00+02:00", "inviata_il": null, "errore": null}
        """);
        Assert.NotNull(b);
        Assert.Equal(55, b!.Id);
        Assert.Equal(812, b.MailId);
        Assert.Equal("pronta", b.Stato);
        Assert.Equal(new[] { "rossi@esempio.it" }, b.A);
        Assert.Empty(b.Cc);
        Assert.Equal("Re: Offerta capannone Bassano", b.Oggetto);
        Assert.Null(b.InviataIl);
    }

    [Fact]
    public void Novita_col_cursore_testo_o_numero()
    {
        const string corpo = "{\"cursore\": %C%, \"urgenti\": [" + MailEsempio + "], \"da_rispondere_nuove\": 2, \"bozze_pronte\": 1, \"scadenze_domani\": [{\"id\": 4, \"mail_id\": 812, \"descrizione\": \"Offerta capannone Bassano a Rossi\", \"quando\": \"2026-10-11\", \"ora\": null, \"stato\": \"aperta\"}]}";
        var conTesto = Json.Leggi<Novita>(corpo.Replace("%C%", "\"8131\""));
        var conNumero = Json.Leggi<Novita>(corpo.Replace("%C%", "8131"));

        foreach (var n in new[] { conTesto, conNumero })
        {
            Assert.NotNull(n);
            Assert.Equal("8131", n!.Cursore);
            Assert.Equal(2, n.DaRispondereNuove);
            Assert.Equal(1, n.BozzePronte);
            Assert.Equal(812, Assert.Single(n.Urgenti).Id);
            var s = Assert.Single(n.ScadenzeDomani);
            Assert.Equal("2026-10-11", s.Quando);
            Assert.Equal("Offerta capannone Bassano a Rossi", s.Descrizione);
        }
    }

    [Fact]
    public void Primo_giro_delle_novita_solo_cursore()
    {
        var n = Json.Leggi<Novita>("{\"cursore\": \"10\"}");
        Assert.NotNull(n);
        Assert.Empty(n!.Urgenti);
        Assert.Empty(n.ScadenzeDomani);
        Assert.Equal(0, n.BozzePronte);
    }

    [Fact]
    public void Versione_abbinamento_ed_errore()
    {
        var v = Json.Leggi<InfoVersione>("{\"server\": \"1.0.0\", \"android\": {\"codice\": 1, \"nome\": \"1.0.0\"}, \"pc\": {\"codice\": 2, \"nome\": \"1.1.0\"}, \"nota\": \"...\"}");
        Assert.Equal(2, v!.Pc!.Codice);
        Assert.Equal("1.1.0", v.Pc.Nome);

        var a = Json.Leggi<RispostaAbbina>("{\"token\": \"" + new string('a', 64) + "\", \"dispositivo_id\": 3}");
        Assert.Equal(64, a!.Token!.Length);
        Assert.Equal(3, a.DispositivoId);

        var e = Json.Leggi<ErroreServer>("{\"errore\": \"La casella non risponde.\", \"codice\": \"casella_non_raggiungibile\"}");
        Assert.Equal("La casella non risponde.", e!.Errore);
        Assert.Equal("casella_non_raggiungibile", e.Codice);
    }

    [Fact]
    public void Novita_della_v1_1_bozza_con_versione_bozze_nuove_impronta_pec_campo()
    {
        var b = Json.Leggi<Bozza>("{\"id\": 60, \"mail_id\": null, \"inviata_id\": 77, \"tipo\": \"sollecito\", \"stato\": \"pronta\", \"versione\": 3}");
        Assert.Equal(3, b!.Versione);
        Assert.Equal(77, b.InviataId);
        Assert.Null(b.MailId);

        var n = Json.Leggi<Novita>("{\"cursore\": \"9\", \"bozze_pronte\": 4, \"bozze_nuove\": [{\"id\": 55, \"mail_id\": 812, \"oggetto\": \"Re: Offerta capannone Bassano\"}]}");
        var nuova = Assert.Single(n!.BozzeNuove);
        Assert.Equal(55, nuova.Id);
        Assert.Equal(812, nuova.MailId);
        Assert.Equal(4, n.BozzePronte);

        var v = Json.Leggi<InfoVersione>("{\"pc\": {\"codice\": 2, \"nome\": \"1.1.0\", \"sha256\": \"" + new string('a', 64) + "\", \"byte\": 65000000}}");
        Assert.Equal(64, v!.Pc!.Sha256!.Length);
        Assert.Equal(65000000, v.Pc.Byte);

        var m = Json.Leggi<Mail>("{\"id\": 815, \"pec\": {\"mittente_busta\": \"posta-certificata@pec.esempio.it\", \"gestore\": \"pec.esempio.it\"}}");
        Assert.Equal("posta-certificata@pec.esempio.it", m!.Pec!.MittenteBusta);
        Assert.Equal("pec.esempio.it", m.Pec.Gestore);
        Assert.Null(Json.Leggi<Mail>("{\"id\": 1, \"pec\": null}")!.Pec);

        var e = Json.Leggi<ErroreServer>("{\"errore\": \"I giorni vanno da 1 a 60.\", \"codice\": \"non_valido\", \"campo\": \"giorni_risposta\"}");
        Assert.Equal("giorni_risposta", e!.Campo);
    }

    [Fact]
    public void Testo_non_json_diventa_null()
    {
        Assert.Null(Json.Leggi<ElencoMail>("<html>502 Bad Gateway</html>"));
        Assert.Null(Json.Leggi<ElencoMail>(""));
        Assert.Null(Json.Leggi<ElencoMail>(null));
    }
}
