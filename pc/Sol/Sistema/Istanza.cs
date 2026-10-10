namespace Sol.Sistema;

/// <summary>
/// Un solo Sol per utente: mutex con nome nella sessione (<c>Local\</c>). Un secondo avvio chiede
/// alla prima istanza di mostrare la finestra (evento con nome <c>.Apri</c>) ed esce.
/// </summary>
public sealed class Istanza : IDisposable
{
    private readonly Mutex? mutex;
    private readonly EventWaitHandle? apri;
    private RegisteredWaitHandle? attesa;
    private bool posseduto;
    private volatile bool aperturaInSospeso;

    public Istanza(string suffisso) : this(@"Local\Sol.Computer" + suffisso, nomeCompleto: true)
    {
    }

    /// <summary>Per i test: un nome completo, mai quello vero.</summary>
    internal Istanza(string nome, bool nomeCompleto)
    {
        _ = nomeCompleto;
        try
        {
            mutex = new Mutex(false, nome);
            try
            {
                posseduto = mutex.WaitOne(0);
            }
            catch (AbandonedMutexException)
            {
                posseduto = true; // il Sol di prima è morto senza chiudere: il mutex è nostro
            }
            apri = new EventWaitHandle(false, EventResetMode.AutoReset, nome + ".Apri");
        }
        catch (Exception e) when (e is WaitHandleCannotBeOpenedException or UnauthorizedAccessException or IOException)
        {
            // Un oggetto con lo stesso nome che non si apre non è di Sol: si parte comunque.
            if (mutex == null) posseduto = true;
        }
    }

    public bool Prima => posseduto;

    public event Action? RichiestaApertura;

    public void Ascolta()
    {
        if (apri == null || attesa != null) return;
        attesa = ThreadPool.RegisterWaitForSingleObject(apri, (_, _) =>
        {
            var h = RichiestaApertura;
            if (h != null) h.Invoke();
            else aperturaInSospeso = true;
        }, null, Timeout.Infinite, executeOnlyOnce: false);
    }

    public void SvuotaAperturaInSospeso()
    {
        if (!aperturaInSospeso) return;
        aperturaInSospeso = false;
        RichiestaApertura?.Invoke();
    }

    public void ChiediApertura() => apri?.Set();

    public void Dispose()
    {
        attesa?.Unregister(null);
        if (posseduto && mutex != null)
        {
            try { mutex.ReleaseMutex(); } catch (ApplicationException) { }
            posseduto = false;
        }
        mutex?.Dispose();
        apri?.Dispose();
    }
}
