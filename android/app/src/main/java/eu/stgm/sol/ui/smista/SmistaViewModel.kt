package eu.stgm.sol.ui.smista

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.FiltroMail
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "abbastanza sicuro" da 0,7 in su, altrimenti "poco sicuro" (docs/aspetto.md §6). */
fun testoSicurezza(sicurezza: Double?): String = if ((sicurezza ?: 0.0) >= 0.7) "abbastanza sicuro" else "poco sicuro"

data class StatoSmista(
    val mail: List<Mail> = emptyList(),
    val gruppi: List<Gruppo> = emptyList(),
    /** La scheda in vista. */
    val indice: Int = 0,
    val caricamento: Boolean = true,
    val errore: String? = null,
    val inCorso: Boolean = false,
    /** "Cambia": si vede la griglia dei gruppi. */
    val sceltaGruppo: Boolean = false,
    val erroreAzione: String? = null,
    /** Quante sono state spostate in un altro gruppo (con "Ricorda il mittente"). */
    val spostate: Int = 0,
) {
    val attuale: Mail? get() = mail.getOrNull(indice)
    val finito: Boolean get() = !caricamento && errore == null && indice >= mail.size
    val totale: Int get() = mail.size
    val frazione: Float get() = if (mail.isEmpty()) 1f else indice.coerceAtMost(mail.size).toFloat() / mail.size
}

/**
 * Le mail incerte, una alla volta (§6): "Va bene" conferma il gruppo proposto,
 * "Cambia" la sposta in un altro e Sol ricorda il mittente. Le possibili truffe non
 * passano da qui: hanno la loro scheda.
 */
class SmistaViewModel(private val client: SolClient) : ViewModel() {

    private val _stato = MutableStateFlow(StatoSmista())
    val stato: StateFlow<StatoSmista> = _stato.asStateFlow()

    fun carica() {
        _stato.value = StatoSmista(caricamento = true)
        viewModelScope.launch {
            val gruppi = async { client.gruppi() }
            val esito = client.mail(FiltroMail(filtro = FiltroRapido.DA_CONTROLLARE))
            val g = (gruppi.await() as? Esito.Ok)?.dato.orEmpty().sortedBy { it.ordine }
            _stato.update {
                when (esito) {
                    is Esito.Ok -> it.copy(mail = esito.dato.mail.filterNot { m -> m.sospetta }, gruppi = g, caricamento = false)
                    else -> it.copy(caricamento = false, errore = esito.messaggio, gruppi = g)
                }
            }
        }
    }

    fun vaBene() {
        val s = _stato.value
        val mail = s.attuale ?: return
        if (s.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.confermaGruppo(mail.id)) {
                is Esito.Ok -> avanti(spostata = false)
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    fun cambia() = _stato.update { if (it.attuale != null) it.copy(sceltaGruppo = true) else it }

    fun annullaCambio() = _stato.update { it.copy(sceltaGruppo = false) }

    /** Il gruppo scelto nella griglia: la mail si sposta e Sol ricorda il mittente. */
    fun scegliGruppo(gruppoId: Long) {
        val s = _stato.value
        val mail = s.attuale ?: return
        if (s.inCorso) return
        if (gruppoId == mail.gruppoId) {
            vaBene()
            return
        }
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.sposta(mail.id, gruppoId, impara = true)) {
                is Esito.Ok -> avanti(spostata = true)
                else -> _stato.update { it.copy(inCorso = false, erroreAzione = esito.messaggio) }
            }
        }
    }

    private fun avanti(spostata: Boolean) = _stato.update {
        it.copy(indice = it.indice + 1, inCorso = false, sceltaGruppo = false, spostate = it.spostate + if (spostata) 1 else 0)
    }

    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }
}
