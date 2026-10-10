namespace Sol;

/// <summary>
/// La versione del programma per il computer. <see cref="Codice"/> si confronta con
/// <c>pc.codice</c> di <c>GET /api/versione</c>: se quello del server è più alto, c'è un aggiornamento.
/// </summary>
public static class Versione
{
    public const string Nome = "1.1.0";
    public const int Codice = 2;
}
