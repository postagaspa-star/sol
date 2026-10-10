package eu.stgm.sol.ui.posta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.Conteggi
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.PaginaMail
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.FiltroMail
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

data class StatoPosta(
    val gruppi: List<Gruppo> = emptyList(),
    /** null = tutti i gruppi. */
    val gruppoId: Long? = null,
    val filtro: FiltroRapido = FiltroRapido.TUTTE,
    val mail: List<Mail> = emptyList(),
    val altre: Boolean = false,
    val caricamento: Boolean = false,
    val caricamentoAltre: Boolean = false,
    val errore: String? = null,
    val codiceErrore: String? = null,
    /** I dati mostrati sono quelli salvati in quel momento (epoch ms), non freschi. */
    val daCacheDel: Long? = null,
    val modalitaProva: Boolean = false,
    val caricataAlmenoUnaVolta: Boolean = false,
    /** I numeri dei filtri e dei gruppi (15.4); null = non ancora letti. */
    val conteggi: Conteggi? = null,
) {
    /** Il numero da mostrare sul filtro, null se non si sa. */
    fun numero(filtro: FiltroRapido): Int? = conteggi?.let {
        when (filtro) {
            FiltroRapido.TUTTE -> null
            FiltroRapido.DA_RISPONDERE -> it.daRispondere
            FiltroRapido.URGENTI -> it.urgenti
            FiltroRapido.DA_CONTROLLARE -> it.daControllare
            FiltroRapido.SOSPETTE -> it.sospette
        }
    }

    /**
     * 16.3: il filtro "Possibili truffe" si vede solo quando ce n'è almeno una da
     * chiarire (o se è già scelto, per poterlo togliere).
     */
    val filtroTruffeVisibile: Boolean get() = (conteggi?.sospette ?: 0) > 0 || filtro == FiltroRapido.SOSPETTE

    /** Le mail da leggere di un gruppo: dai conteggi se ci sono, altrimenti dall'elenco dei gruppi. */
    fun daLeggere(gruppo: Gruppo): Int = conteggi?.gruppi?.get(gruppo.id.toString())?.daLeggere ?: gruppo.daLeggere
}

/** Chiave della cache per la combinazione gruppo + filtro: ogni vista ha i suoi ultimi dati. */
internal fun chiaveCacheMail(gruppoId: Long?, filtro: FiltroRapido) = "mail-${gruppoId ?: "tutte"}-${filtro.name}"

class PostaViewModel(private val client: SolClient, private val cache: Cache) : ViewModel() {

    private val _stato = MutableStateFlow(StatoPosta())
    val stato: StateFlow<StatoPosta> = _stato.asStateFlow()

    private var lettura: Job? = null

    // "Mostra mail più vecchie" ancora in arrivo quando si cambia filtro: va annullato,
    // altrimenti una pagina della vista di prima finisce dentro quella nuova.
    private var letturaAltre: Job? = null

    fun carica() {
        lettura?.cancel()
        letturaAltre?.cancel()
        val gruppoId = _stato.value.gruppoId
        val filtro = _stato.value.filtro
        _stato.update { it.copy(caricamento = true, caricamentoAltre = false) }
        lettura = viewModelScope.launch {
            val gruppi = async { leggiConCache(cache, "gruppi", ListSerializer(Gruppo.serializer())) { client.gruppi() } }
            val impostazioni = async { leggiConCache(cache, "impostazioni", ImpostazioniSol.serializer()) { client.impostazioni() } }
            val conteggi = async { leggiConCache(cache, "conteggi", Conteggi.serializer()) { client.conteggi() } }
            val mail = leggiConCache(cache, chiaveCacheMail(gruppoId, filtro), PaginaMail.serializer()) {
                client.mail(FiltroMail(gruppoId = gruppoId, filtro = filtro))
            }
            val g = gruppi.await()
            val i = impostazioni.await()
            val c = conteggi.await()
            _stato.update {
                if (it.gruppoId != gruppoId || it.filtro != filtro) return@update it
                it.copy(
                    gruppi = (g.dato ?: it.gruppi).sortedBy { gruppo -> gruppo.ordine },
                    modalitaProva = i.dato?.modalitaProva ?: it.modalitaProva,
                    conteggi = c.dato ?: it.conteggi,
                    mail = mail.dato?.mail ?: emptyList(),
                    altre = mail.dato?.altre ?: false,
                    caricamento = false,
                    errore = mail.errore,
                    codiceErrore = mail.codiceErrore,
                    daCacheDel = mail.daCacheDel,
                    caricataAlmenoUnaVolta = true,
                )
            }
        }
    }

    fun scegliGruppo(id: Long?) {
        if (id == _stato.value.gruppoId) return
        _stato.update { it.copy(gruppoId = id, mail = emptyList(), altre = false) }
        carica()
    }

    /** Toccare il filtro già scelto lo toglie. */
    fun scegliFiltro(filtro: FiltroRapido) {
        val nuovo = if (_stato.value.filtro == filtro) FiltroRapido.TUTTE else filtro
        _stato.update { it.copy(filtro = nuovo, mail = emptyList(), altre = false) }
        carica()
    }

    fun caricaAltre() {
        val stato = _stato.value
        val ultima = stato.mail.lastOrNull() ?: return
        if (!stato.altre || stato.caricamentoAltre || stato.caricamento) return
        _stato.update { it.copy(caricamentoAltre = true) }
        letturaAltre = viewModelScope.launch {
            val esito = client.mail(FiltroMail(gruppoId = stato.gruppoId, filtro = stato.filtro, primaDi = ultima.id))
            _stato.update {
                // Una pagina della vista di prima non entra in quella nuova.
                if (it.gruppoId != stato.gruppoId || it.filtro != stato.filtro) return@update it.copy(caricamentoAltre = false)
                when (esito) {
                    is Esito.Ok -> it.copy(
                        mail = (it.mail + esito.dato.mail).distinctBy { m -> m.id },
                        altre = esito.dato.altre,
                        caricamentoAltre = false,
                    )
                    else -> it.copy(caricamentoAltre = false, errore = esito.messaggio)
                }
            }
        }
    }
}
