package eu.stgm.sol.ui.scadenze

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.NuovaScadenza
import eu.stgm.sol.dati.Scadenza
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate

data class StatoScadenze(
    val scadenze: List<Scadenza> = emptyList(),
    val caricamento: Boolean = false,
    val inCorso: Boolean = false,
    val errore: String? = null,
    val daCacheDel: Long? = null,
    val messaggio: String? = null,
    /** Un gesto non riuscito, o un dato da correggere nella nuova scadenza. */
    val erroreAzione: String? = null,
    /** Dopo un'aggiunta riuscita la finestra si chiude. */
    val aggiunta: Boolean = false,
)

/** Le aperte, dalla più vicina; senza ora prima di quelle con l'ora nello stesso giorno. */
fun ordinaScadenze(scadenze: List<Scadenza>): List<Scadenza> =
    scadenze.filter { it.stato == Scadenza.APERTA }
        .sortedWith(compareBy<Scadenza> { it.quando }.thenBy { it.ora ?: "" }.thenBy { it.id })

private val ORA_VALIDA = Regex("""^([01]?\d|2[0-3])[:.]([0-5]\d)$""")

/** "9:30" o "9.30" → "09:30"; vuoto → null; altro → errore. */
fun leggiOra(testo: String): Result<String?> {
    val t = testo.trim()
    if (t.isEmpty()) return Result.success(null)
    val m = ORA_VALIDA.matchEntire(t) ?: return Result.failure(IllegalArgumentException(t))
    return Result.success("%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2]))
}

class ScadenzeViewModel(private val client: SolClient, private val cache: Cache) : ViewModel() {

    private val _stato = MutableStateFlow(StatoScadenze())
    val stato: StateFlow<StatoScadenze> = _stato.asStateFlow()

    fun carica() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val l = leggiConCache(cache, "scadenze", ListSerializer(Scadenza.serializer())) { client.scadenzeAperte() }
            _stato.update {
                it.copy(
                    scadenze = l.dato?.let(::ordinaScadenze) ?: it.scadenze,
                    caricamento = false,
                    errore = l.errore,
                    daCacheDel = l.daCacheDel,
                )
            }
        }
    }

    fun segnaFatta(id: Long) = cambiaStato(id, Scadenza.FATTA, "Segnata come fatta.")

    fun scarta(id: Long) = cambiaStato(id, Scadenza.SCARTATA, "Scadenza tolta.")

    private fun cambiaStato(id: Long, stato: String, frase: String) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.cambiaStatoScadenza(id, stato)) {
                is Esito.Ok -> _stato.update { s ->
                    s.copy(inCorso = false, scadenze = s.scadenze.filterNot { it.id == id }, messaggio = frase)
                }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    fun aggiungi(descrizione: String, quando: LocalDate?, ora: String) {
        val testo = descrizione.trim()
        val oraLetta = leggiOra(ora)
        val problema = when {
            testo.isEmpty() -> "Scrivi cosa scade."
            quando == null -> "Scegli il giorno."
            oraLetta.isFailure -> "L'ora va scritta così: 9:30. Oppure lasciala vuota."
            else -> null
        }
        if (problema != null) {
            _stato.update { it.copy(erroreAzione = problema) }
            return
        }
        val giorno = requireNotNull(quando).toString()
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.creaScadenza(NuovaScadenza(testo, giorno, oraLetta.getOrNull()))) {
                is Esito.Ok -> {
                    _stato.update { it.copy(inCorso = false, aggiunta = true, messaggio = "Scadenza aggiunta.") }
                    carica()
                }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    fun aggiuntaVista() = _stato.update { it.copy(aggiunta = false) }
    fun consumaMessaggio() = _stato.update { it.copy(messaggio = null) }
    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }
}
