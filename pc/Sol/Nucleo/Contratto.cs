using System.Text.Json.Serialization;

namespace Sol.Nucleo;

// Le forme del contratto (docs/contratto-api.md, v1.1) che il programma usa davvero in C#.
// Tutto il resto passa dal ponte così com'è: la pagina lo legge direttamente.

public sealed record Persona
{
    public string? Nome { get; init; }
    public string? Indirizzo { get; init; }

    /// <summary>Il nome se c'è, altrimenti l'indirizzo.</summary>
    public string Mostrato => !string.IsNullOrWhiteSpace(Nome) ? Nome! : Indirizzo ?? "";
}

public sealed record RiferimentoBozza
{
    public long Id { get; init; }
    public string? Stato { get; init; }
}

/// <summary>La busta di una PEC vera (§15.8): chi l'ha spedita davvero e il gestore.</summary>
public sealed record Pec
{
    public string? MittenteBusta { get; init; }
    public string? Gestore { get; init; }
}

/// <summary><c>Mail</c> in lista (§6, §15.8).</summary>
public sealed record Mail
{
    public long Id { get; init; }
    public long? CasellaId { get; init; }
    public string? Casella { get; init; }
    public Persona? Mittente { get; init; }
    public string? Oggetto { get; init; }
    public string? Anteprima { get; init; }
    public string? Riassunto { get; init; }
    public DateTimeOffset? RicevutaIl { get; init; }
    public bool Letta { get; init; }
    public bool RispostaData { get; init; }
    public long? GruppoId { get; init; }
    public string? Gruppo { get; init; }
    public bool DaRispondere { get; init; }
    public string? Urgenza { get; init; }
    public double? Sicurezza { get; init; }
    public string? Smistamento { get; init; }
    public string? OrigineSmistamento { get; init; }
    public bool ApplicataInCasella { get; init; }
    public RiferimentoBozza? Bozza { get; init; }
    public Pec? Pec { get; init; }

    /// <summary>§16: possibile truffa (etichetta rossa, niente bozza automatica, niente notifica da urgente).</summary>
    public bool Sospetta { get; init; }
}

public sealed record ElencoMail
{
    public List<Mail> Mail { get; init; } = new();
    public bool Altre { get; init; }
}

/// <summary><c>Bozza</c> (§7, §15.11: <c>versione</c> cresce a ogni cambiamento).</summary>
public sealed record Bozza
{
    public long Id { get; init; }
    public long? MailId { get; init; }
    public long? InviataId { get; init; }
    public string? Tipo { get; init; }
    public string? Stato { get; init; }
    public List<string> A { get; init; } = new();
    public List<string> Cc { get; init; } = new();
    public string? Oggetto { get; init; }
    public string? Testo { get; init; }
    public string? Istruzioni { get; init; }
    public bool Modificata { get; init; }
    public DateTimeOffset? InviataIl { get; init; }
    public string? Errore { get; init; }
    public long? Versione { get; init; }
}

/// <summary><c>Scadenza</c> (§9). <c>quando</c> resta testo (<c>2026-10-16</c>): è una data senza ora.</summary>
public sealed record Scadenza
{
    public long Id { get; init; }
    public long? MailId { get; init; }
    public string? Descrizione { get; init; }
    public string? Quando { get; init; }
    public string? Ora { get; init; }
    public string? Stato { get; init; }
}

/// <summary>Una bozza diventata pronta dopo il cursore delle novità (§15.6).</summary>
public sealed record BozzaNuova
{
    public long Id { get; init; }
    public long? MailId { get; init; }
    public string? Oggetto { get; init; }
}

/// <summary><c>GET /api/novita</c> (§12, §15.6). <c>bozze_pronte</c> è il totale: per le notifiche contano <c>bozze_nuove</c>.</summary>
public sealed record Novita
{
    [JsonConverter(typeof(TestoONumero))]
    public string? Cursore { get; init; }
    public List<Mail> Urgenti { get; init; } = new();
    public int DaRispondereNuove { get; init; }
    public int BozzePronte { get; init; }
    public List<BozzaNuova> BozzeNuove { get; init; } = new();
    public List<Scadenza> ScadenzeDomani { get; init; } = new();
}

/// <summary>Una versione di un'app (§3, §15.10: impronta e dimensione del file da scaricare).</summary>
public sealed record VersioneApp
{
    public int Codice { get; init; }
    public string? Nome { get; init; }
    public string? Sha256 { get; init; }
    public long? Byte { get; init; }
}

/// <summary><c>GET /api/versione</c> (§3).</summary>
public sealed record InfoVersione
{
    public string? Server { get; init; }
    public VersioneApp? Android { get; init; }
    public VersioneApp? Pc { get; init; }
    public string? Nota { get; init; }
}

/// <summary>Risposta di <c>POST /api/abbina</c> (§2).</summary>
public sealed record RispostaAbbina
{
    public string? Token { get; init; }
    public long? DispositivoId { get; init; }
}

/// <summary>Il corpo degli errori del server (§1, §15.13: <c>campo</c> per i 422).</summary>
public sealed record ErroreServer
{
    public string? Errore { get; init; }
    public string? Codice { get; init; }
    public string? Campo { get; init; }
}
