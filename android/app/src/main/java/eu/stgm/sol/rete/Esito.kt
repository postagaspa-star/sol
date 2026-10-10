package eu.stgm.sol.rete

/**
 * Com'è andata una chiamata al server. Ogni fallimento porta già la frase da
 * mostrare, in italiano semplice: le schermate non traducono niente.
 */
sealed interface Esito<out T> {
    data class Ok<T>(val dato: T) : Esito<T>

    /**
     * Il server ha risposto di no. [messaggio] è il suo `errore` o una frase nostra;
     * [campo] il dato sbagliato di un 422 (15.13); [dettagli] il corpo intero, per i
     * dati in più (la bozza nuova in un 409 d'invio, 15.11).
     */
    data class Errore(
        val messaggio: String,
        val codice: String? = null,
        val stato: Int = 0,
        val campo: String? = null,
        val dettagli: kotlinx.serialization.json.JsonObject? = null,
    ) : Esito<Nothing>

    /** Rete assente, server spento o tunnel che non trova il server. */
    data object Irraggiungibile : Esito<Nothing>
}

val Esito<*>.messaggio: String?
    get() = when (this) {
        is Esito.Ok -> null
        is Esito.Errore -> messaggio
        Esito.Irraggiungibile -> Testi.SERVER_IRRAGGIUNGIBILE
    }

/** Le frasi di errore di tutta l'app, in un posto solo. */
object Testi {
    const val SERVER_IRRAGGIUNGIBILE =
        "Sol non riesce a raggiungere il server di casa. Controlla la connessione o che il server sia acceso."
    const val NON_ABBINATO = "Questo telefono non è ancora abbinato a Sol."
    const val NON_AUTORIZZATO =
        "Questo telefono non è più abbinato a Sol. Fai preparare un codice nuovo sul server e rifai l'abbinamento."
    const val NON_TROVATO = "Non trovo più questo elemento: forse è stato tolto. Aggiorna la pagina."
    const val CONFLITTO = "Questa operazione non si può più fare: per esempio la bozza è già partita."
    const val NON_VALIDO = "Sol non ha accettato i dati inseriti. Controllali e riprova."
    const val TROPPI_TENTATIVI = "Troppi tentativi. Aspetta qualche minuto e riprova."
    const val CASELLA_NON_RAGGIUNGIBILE = "Sol non riesce a parlare con la casella di posta. Riprova tra poco."
    const val SCRITTORE_NON_DISPONIBILE =
        "Il servizio che scrive i testi non è disponibile in questo momento. Riprova tra poco."
    const val PROBLEMA_SERVER = "Il server di casa ha avuto un problema. Riprova tra poco."
    const val GENERICO = "Qualcosa non è andato. Riprova."
    const val RISPOSTA_STRANA = "Il server ha risposto in un modo che l'app non capisce. Forse va aggiornata."

    const val INDIRIZZO_NON_PROTETTO =
        "Questo indirizzo non è protetto e non è della rete di casa: rifai l'abbinamento con l'indirizzo https di Tailscale."
    const val PASSWORD_SU_HTTP =
        "Per mandare una password serve il collegamento protetto: rifai l'abbinamento con l'indirizzo https " +
            "di Tailscale (quello che comincia con https://) e poi riprova."

    const val CODICE_NON_AUTORIZZATO = "non_autorizzato"
    const val CODICE_NON_ABBINATO = "non_abbinato"
}
