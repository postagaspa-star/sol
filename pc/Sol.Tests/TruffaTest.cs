using Sol.Motore;
using Sol.Nucleo;

namespace Sol.Tests;

/// <summary>§16: possibile truffa, la parte che tocca il programma (conferma d'invio, notifiche, forme).</summary>
public class TruffaTest
{
    private const string Bozza = """
    {"id": 70, "mail_id": 820, "tipo": "risposta", "stato": "pronta", "a": ["amministrazione@neri-pagamenti.esempio.it"], "cc": [],
     "oggetto": "Re: Nuovo IBAN per i pagamenti", "testo": "Buongiorno, ricevuto.", "versione": 2}
    """;

    private static (int, string) Server(HandlerFinto.Richiesta r, bool sospetta) => r.Percorso switch
    {
        "/api/bozze/70" => (200, Bozza),
        "/api/mail/820" => (200, "{\"id\": 820, \"risposta_data\": false, \"sospetta\": " + (sospetta ? "true" : "false") + "}"),
        _ => (404, "{}"),
    };

    [Fact]
    public async Task Risposta_a_una_mail_sospetta_la_conferma_avvisa_in_rosso_e_dice_invia_lo_stesso()
    {
        using var banco = new Banco(r => Server(r, sospetta: true));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a => { vista = a; return Task.FromResult(false); };

        await banco.Motore.InviaBozzaAsync(70);

        Assert.True(vista!.Sospetta);
        Assert.Equal(ConfermaInvio.AvvisoTruffa, ConfermaInvio.TitoloPer(vista));
        Assert.Equal("Invia lo stesso", ConfermaInvio.PulsantePer(vista));
        var testo = ConfermaInvio.Testo(vista);
        Assert.StartsWith("Stai rispondendo a una mail segnalata come possibile truffa", testo);
        Assert.Contains("A: amministrazione@neri-pagamenti.esempio.it", testo);
        Assert.Empty(banco.Richieste("POST", "/api/bozze/70/invia"));
    }

    [Fact]
    public async Task Risposta_a_una_mail_normale_nessun_avviso_e_il_pulsante_e_invia()
    {
        using var banco = new Banco(r => Server(r, sospetta: false));
        AnteprimaInvio? vista = null;
        banco.Motore.ChiediConferma = a => { vista = a; return Task.FromResult(false); };

        await banco.Motore.InviaBozzaAsync(70);

        Assert.False(vista!.Sospetta);
        Assert.Equal(ConfermaInvio.Titolo, ConfermaInvio.TitoloPer(vista));
        Assert.Equal("Invia", ConfermaInvio.PulsantePer(vista));
        Assert.DoesNotContain("truffa", ConfermaInvio.Testo(vista));
    }

    [Fact]
    public async Task Con_invia_lo_stesso_la_mail_parte_come_le_altre()
    {
        using var banco = new Banco(r => r.Metodo == "POST" && r.Percorso == "/api/bozze/70/invia"
            ? (200, Bozza.Replace("\"pronta\"", "\"inviata\"").TrimEnd().TrimEnd('}') + ", \"gia_inviata\": false}")
            : Server(r, sospetta: true));
        banco.Motore.ChiediConferma = _ => Task.FromResult(true);

        var esito = await banco.Motore.InviaBozzaAsync(70);

        Assert.True(esito["inviata"]!.GetValue<bool>());
        Assert.Single(banco.Richieste("POST", "/api/bozze/70/invia"));
    }

    [Fact]
    public void Una_possibile_truffa_non_suona_come_urgente()
    {
        var stato = new StatoNotifiche { Cursore = "100" };
        var sospetta = new Mail { Id = 820, Urgenza = "alta", Oggetto = "Pagamento urgente", Sospetta = true, Mittente = new Persona { Nome = "Ferramenta Neri" } };
        var normale = new Mail { Id = 821, Urgenza = "alta", Oggetto = "Offerta", Mittente = new Persona { Nome = "Mario Rossi" } };

        var n = Notificatore.DaMostrare(new Novita { Cursore = "101", Urgenti = { sospetta, normale } }, stato);

        Assert.Equal(821, Assert.Single(n).MailId);
    }

    [Fact]
    public void La_mail_dice_se_e_sospetta()
    {
        Assert.True(Json.Leggi<Mail>("{\"id\": 820, \"sospetta\": true}")!.Sospetta);
        Assert.False(Json.Leggi<Mail>("{\"id\": 821, \"sospetta\": false}")!.Sospetta);
        Assert.False(Json.Leggi<Mail>("{\"id\": 822}")!.Sospetta);
    }
}
