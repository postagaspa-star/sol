namespace Sol.Nucleo;

/// <summary>
/// Tiene d'occhio il collegamento col server, per non lasciare Manuel all'oscuro: se il server non riconosce più
/// questo computer (token revocato) o non risponde da più di mezz'ora, l'icona cambia e parte UNA notifica
/// (una per volta che succede, non una ogni due minuti). Logica pura: il motore le passa gli esiti e l'ora.
/// </summary>
public sealed class SorveglianzaCollegamento
{
    public static readonly TimeSpan Soglia = TimeSpan.FromMinutes(30);

    private readonly object blocco = new();
    private DateTimeOffset? irraggiungibileDal;
    private bool avvisatoIrraggiungibile;
    private bool avvisatoNonRiconosciuto;

    public DateTimeOffset? IrraggiungibileDal
    {
        get { lock (blocco) return irraggiungibileDal; }
    }

    /// <summary>Dopo ogni richiesta: <paramref name="senzaRisposta"/> = il server non ha risposto per niente.</summary>
    public void Esito(bool senzaRisposta, DateTimeOffset adesso)
    {
        lock (blocco)
        {
            if (senzaRisposta)
            {
                irraggiungibileDal ??= adesso;
            }
            else
            {
                irraggiungibileDal = null;
                avvisatoIrraggiungibile = false;
            }
        }
    }

    /// <summary>C'è un problema da far vedere (icona diversa)?</summary>
    public bool Problema(bool nonRiconosciuto, DateTimeOffset adesso)
    {
        lock (blocco) return nonRiconosciuto || (irraggiungibileDal is DateTimeOffset dal && adesso - dal >= Soglia);
    }

    /// <summary>La notifica da mostrare adesso, se c'è (al massimo una per ogni volta che il problema comincia).</summary>
    public Notifica? Controlla(bool nonRiconosciuto, DateTimeOffset adesso)
    {
        lock (blocco)
        {
            if (nonRiconosciuto)
            {
                if (avvisatoNonRiconosciuto) return null;
                avvisatoNonRiconosciuto = true;
                return new Notifica("Sol non è più collegato",
                    "Il server di casa non riconosce più questo computer: le mail e le notifiche non arrivano. Apri Sol per collegarlo di nuovo.",
                    Sezione: "posta");
            }
            avvisatoNonRiconosciuto = false;
            if (irraggiungibileDal is DateTimeOffset dal && adesso - dal >= Soglia && !avvisatoIrraggiungibile)
            {
                avvisatoIrraggiungibile = true;
                return new Notifica("Sol non raggiunge il server",
                    "Da più di mezz'ora Sol non riesce a raggiungere il server di casa: finché non torna, le notifiche non arrivano.",
                    Sezione: "posta");
            }
            return null;
        }
    }
}
