package eu.stgm.sol.ui.riassunti

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.Riassunto
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

data class StatoRiassunti(
    val oggi: Riassunto? = null,
    val recenti: List<Riassunto> = emptyList(),
    val richiesta: String = "",
    /** Il riassunto chiesto adesso (o riaperto dai recenti), mentre cresce. */
    val corrente: Riassunto? = null,
    val caricamento: Boolean = false,
    val invio: Boolean = false,
    /** Problema nel leggere il riassunto di oggi (con eventuali dati vecchi). */
    val errore: String? = null,
    val daCacheDel: Long? = null,
    /** Problema col riassunto chiesto adesso. */
    val erroreAzione: String? = null,
    /** L'app sta ricontrollando il riassunto corrente (solo con la schermata aperta). */
    val seguendo: Boolean = false,
) {
    val inCorso: Boolean get() = invio || seguendo

    /** Il riassunto non è finito ma l'app ha smesso di seguirlo (rete assente): si può riprendere. */
    val sospeso: Boolean get() = !seguendo && corrente?.inCorso == true
}

class RiassuntiViewModel(
    private val client: SolClient,
    private val cache: Cache,
    private val intervalloMs: Long = 2_000,
    private val tentativiMassimi: Int = 150,
    /** Dopo quanti controlli di fila senza server ci si ferma e lo si dice. */
    private val fallimentiMassimi: Int = 3,
) : ViewModel() {

    private val _stato = MutableStateFlow(StatoRiassunti())
    val stato: StateFlow<StatoRiassunti> = _stato.asStateFlow()

    private var attesa: Job? = null

    fun carica() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val recenti = async {
                leggiConCache(cache, "riassunti", ListSerializer(Riassunto.serializer())) { client.riassunti() }
            }
            val oggi = leggiConCache(cache, "riassunto-oggi", Riassunto.serializer()) { client.riassuntoOggi() }
            val r = recenti.await()
            _stato.update {
                it.copy(
                    oggi = oggi.dato ?: it.oggi,
                    recenti = r.dato ?: it.recenti,
                    caricamento = false,
                    errore = oggi.errore,
                    daCacheDel = oggi.daCacheDel,
                )
            }
        }
    }

    fun cambiaRichiesta(testo: String) = _stato.update { it.copy(richiesta = testo) }

    fun chiedi() {
        val richiesta = _stato.value.richiesta.trim()
        if (richiesta.isEmpty()) {
            _stato.update { it.copy(erroreAzione = "Scrivi cosa vuoi riassumere, per esempio: le mail di oggi di Rossi.") }
            return
        }
        if (_stato.value.inCorso) return
        attesa?.cancel()
        _stato.update { it.copy(invio = true, erroreAzione = null, corrente = null) }
        viewModelScope.launch {
            when (val esito = client.chiediRiassunto(richiesta)) {
                is Esito.Ok -> {
                    val partito = esito.dato.copy(richiesta = esito.dato.richiesta ?: richiesta)
                    _stato.update { it.copy(invio = false, corrente = partito, richiesta = "") }
                    partito.id?.let { segui(it) }
                }
                else -> _stato.update { it.copy(invio = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    /** Riapre un riassunto già fatto (e lo segue se non è finito). */
    fun apri(riassunto: Riassunto) {
        attesa?.cancel()
        _stato.update { it.copy(corrente = riassunto, seguendo = false, erroreAzione = null) }
        if (riassunto.inCorso) riassunto.id?.let { segui(it) }
    }

    fun chiudiCorrente() {
        attesa?.cancel()
        _stato.update { it.copy(corrente = null, seguendo = false) }
    }

    /** La schermata va via: niente controlli a vuoto (il riassunto continua sul server). */
    fun sospendi() {
        attesa?.cancel()
        attesa = null
        _stato.update { it.copy(seguendo = false) }
    }

    /** La schermata torna (o Manuel tocca "Riprova"): si riprende a seguire il riassunto non finito. */
    fun riprendi() {
        val corrente = _stato.value.corrente ?: return
        if (attesa?.isActive == true || !corrente.inCorso) return
        _stato.update { it.copy(erroreAzione = null) }
        corrente.id?.let { segui(it) }
    }

    /**
     * Ricarica ogni [intervalloMs] finché il testo è finito: si vede crescere. Senza
     * server per [fallimentiMassimi] volte di fila (con attese che si allungano) si
     * ferma e lo dice, invece di continuare in silenzio.
     */
    private fun segui(id: Long) {
        _stato.update { it.copy(seguendo = true) }
        attesa = viewModelScope.launch {
            var fallimenti = 0
            repeat(tentativiMassimi) {
                delay(intervalloMs shl fallimenti)
                when (val esito = client.riassunto(id)) {
                    is Esito.Ok -> {
                        fallimenti = 0
                        val r = esito.dato
                        _stato.update { s -> s.copy(corrente = r.copy(richiesta = r.richiesta ?: s.corrente?.richiesta)) }
                        if (!r.inCorso) {
                            _stato.update {
                                it.copy(
                                    seguendo = false,
                                    erroreAzione = if (r.stato == Riassunto.ERRORE) r.errore ?: "Il riassunto non è riuscito. Riprova." else null,
                                )
                            }
                            aggiornaRecenti()
                            return@launch
                        }
                    }
                    is Esito.Errore -> {
                        _stato.update { it.copy(seguendo = false, erroreAzione = esito.messaggio) }
                        return@launch
                    }
                    Esito.Irraggiungibile -> {
                        fallimenti++
                        if (fallimenti >= fallimentiMassimi) {
                            _stato.update { it.copy(seguendo = false, erroreAzione = SENZA_SERVER) }
                            return@launch
                        }
                    }
                }
            }
            _stato.update { it.copy(seguendo = false, erroreAzione = "Il riassunto ci sta mettendo troppo. Riprova tra poco.") }
        }
    }

    private suspend fun aggiornaRecenti() {
        val r = leggiConCache(cache, "riassunti", ListSerializer(Riassunto.serializer())) { client.riassunti() }
        r.dato?.let { lista -> _stato.update { it.copy(recenti = lista) } }
    }

    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }

    companion object {
        const val SENZA_SERVER =
            "Sol non riesce a raggiungere il server di casa. Il riassunto continua lì: tocca «Riprova» tra poco."
    }
}
