package eu.stgm.sol.ui.indietro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.Indietro
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

data class StatoIndietro(
    val dati: Indietro? = null,
    /** Per i colori delle righe (docs/aspetto.md): il cerchio ha il colore del gruppo. */
    val gruppi: List<Gruppo> = emptyList(),
    val caricamento: Boolean = false,
    val inCorso: Boolean = false,
    val errore: String? = null,
    val daCacheDel: Long? = null,
    val messaggio: String? = null,
    val erroreAzione: String? = null,
    /** Il sollecito appena chiesto: la schermata lo apre. */
    val sollecitoDaAprire: Bozza? = null,
)

class IndietroViewModel(private val client: SolClient, private val cache: Cache) : ViewModel() {

    private val _stato = MutableStateFlow(StatoIndietro())
    val stato: StateFlow<StatoIndietro> = _stato.asStateFlow()

    fun carica() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val gruppi = async { leggiConCache(cache, "gruppi", ListSerializer(Gruppo.serializer())) { client.gruppi() } }
            val l = leggiConCache(cache, "indietro", Indietro.serializer()) { client.indietro() }
            val g = gruppi.await()
            _stato.update {
                it.copy(
                    dati = l.dato ?: it.dati,
                    gruppi = g.dato ?: it.gruppi,
                    caricamento = false,
                    errore = l.errore,
                    daCacheDel = l.daCacheDel,
                )
            }
        }
    }

    fun preparaSollecito(inviataId: Long) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.preparaSollecito(inviataId)) {
                is Esito.Ok -> _stato.update { it.copy(inCorso = false, sollecitoDaAprire = esito.dato) }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    fun sollecitoAperto() = _stato.update { it.copy(sollecitoDaAprire = null) }

    /** Toglie una mail inviata dalla lista "Aspetti tu una risposta". */
    fun ignora(inviataId: Long) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.ignora(inviataId)) {
                is Esito.Ok -> _stato.update { s ->
                    s.copy(
                        inCorso = false,
                        dati = s.dati?.copy(aspettiTu = s.dati.aspettiTu.filterNot { it.inviata.id == inviataId }),
                        messaggio = "Tolta dalla lista.",
                    )
                }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    /** Per "Aspettano una tua risposta": la mail non chiede risposta, si toglie. */
    fun nonServeRisposta(mailId: Long) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.daRispondere(mailId, false)) {
                is Esito.Ok -> _stato.update { s ->
                    s.copy(
                        inCorso = false,
                        dati = s.dati?.copy(aspettanoTe = s.dati.aspettanoTe.filterNot { it.mail.id == mailId }),
                        messaggio = "Tolta dalla lista: non serve rispondere.",
                    )
                }
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    fun consumaMessaggio() = _stato.update { it.copy(messaggio = null) }
    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }
}
