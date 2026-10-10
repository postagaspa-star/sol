package eu.stgm.sol.ui.posta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.Conteggi
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.Mail
import eu.stgm.sol.dati.PaginaMail
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.FiltroMail
import eu.stgm.sol.rete.FiltroRapido
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** Le piastrelle del riquadro "Da fare" (docs/aspetto.md §2). [filtro] null: apre la scheda Scadenze. */
enum class VoceDaFare(val filtro: FiltroRapido?) {
    DA_RISPONDERE(FiltroRapido.DA_RISPONDERE),
    TRUFFE(FiltroRapido.SOSPETTE),
    DA_CONTROLLARE(FiltroRapido.DA_CONTROLLARE),
    SCADENZE(null),
}

data class Piastrella(val voce: VoceDaFare, val numero: Int) {
    /** Per intero: la legge il lettore dello schermo ("5 da rispondere"). */
    val etichetta: String
        get() = when (voce) {
            VoceDaFare.DA_RISPONDERE -> "da rispondere"
            VoceDaFare.TRUFFE -> if (numero == 1) "possibile truffa" else "possibili truffe"
            VoceDaFare.DA_CONTROLLARE -> "da controllare"
            VoceDaFare.SCADENZE -> if (numero == 1) "scadenza entro 7 giorni" else "scadenze entro 7 giorni"
        }

    /**
     * Sotto il numero, sempre su due righe decise qui: le quattro piastrelle stanno in
     * una riga sola anche su un telefono da 360 dp, e sono tutte alte uguali.
     */
    val etichettaCorta: String
        get() = when (voce) {
            VoceDaFare.DA_RISPONDERE -> "da\nrispondere"
            VoceDaFare.TRUFFE -> if (numero == 1) "possibile\ntruffa" else "possibili\ntruffe"
            VoceDaFare.DA_CONTROLLARE -> "da\ncontrollare"
            VoceDaFare.SCADENZE -> if (numero == 1) "scadenza\n7 giorni" else "scadenze\n7 giorni"
        }
}

/** Solo le voci con almeno una cosa da fare; tutto a zero (o conteggi non letti) = nessuna. */
fun piastrelleDaFare(conteggi: Conteggi?): List<Piastrella> {
    if (conteggi == null) return emptyList()
    return listOf(
        Piastrella(VoceDaFare.DA_RISPONDERE, conteggi.daRispondere),
        Piastrella(VoceDaFare.TRUFFE, conteggi.sospette),
        Piastrella(VoceDaFare.DA_CONTROLLARE, conteggi.daControllare),
        Piastrella(VoceDaFare.SCADENZE, conteggi.scadenzeProssime),
    ).filter { it.numero > 0 }
}

/** Il nome del filtro nella riga "Da rispondere · togli filtro". */
fun FiltroRapido.nome(): String = when (this) {
    FiltroRapido.TUTTE -> "Tutte"
    FiltroRapido.DA_RISPONDERE -> "Da rispondere"
    FiltroRapido.URGENTI -> "Urgenti"
    FiltroRapido.DA_CONTROLLARE -> "Da controllare"
    FiltroRapido.SOSPETTE -> "Possibili truffe"
}

/** Una mail fa ancora parte della vista (gruppo + filtro) dopo un gesto? */
fun apparteneAllaVista(mail: Mail, gruppoId: Long?, filtro: FiltroRapido): Boolean {
    if (gruppoId != null && mail.gruppoId != gruppoId) return false
    return when (filtro) {
        FiltroRapido.TUTTE -> true
        FiltroRapido.DA_RISPONDERE -> mail.daRispondere
        FiltroRapido.URGENTI -> mail.urgente
        FiltroRapido.DA_CONTROLLARE -> mail.daControllare
        FiltroRapido.SOSPETTE -> mail.sospetta
    }
}

/**
 * Uno spostamento appena fatto col gesto: per "Spostata in Clienti · Annulla".
 * [prima] è la mail com'era, [indice] dov'era nell'elenco.
 */
data class Spostamento(
    val prima: Mail,
    val indice: Int,
    val nomeNuovo: String?,
    val impara: Boolean,
) {
    /** Senza un gruppo di prima non c'è niente a cui tornare. */
    val annullabile: Boolean get() = prima.gruppoId != null

    /** Il messaggio in basso: "Spostata in Clienti". */
    val frase: String get() = nomeNuovo?.let { "Spostata in $it" } ?: "Spostata in un altro gruppo"
}

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
    /** I numeri dei gruppi e del riquadro "Da fare" (15.4, 17); null = non ancora letti. */
    val conteggi: Conteggi? = null,
    /** Un gesto (letta, sposta, annulla) non è riuscito: si dice in una finestra. */
    val erroreAzione: String? = null,
    /** La mail che si sta spostando: la sua riga resta scivolata via finché il server risponde. */
    val inSpostamento: Long? = null,
    /** L'ultimo spostamento col gesto, finché il messaggio con "Annulla" è in vista. */
    val spostamento: Spostamento? = null,
) {
    /** Il riquadro "Da fare" c'è solo in "Tutte", e si nasconde se è tutto a zero. */
    val piastrelle: List<Piastrella> get() = if (gruppoId == null) piastrelleDaFare(conteggi) else emptyList()

    /** Le mail da leggere di un gruppo: dai conteggi se ci sono, altrimenti dall'elenco dei gruppi. */
    fun daLeggere(gruppo: Gruppo): Int = conteggi?.gruppi?.get(gruppo.id.toString())?.daLeggere ?: gruppo.daLeggere

    fun gruppo(id: Long?): Gruppo? = id?.let { g -> gruppi.firstOrNull { it.id == g } }
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

    // --- Gesti sulle righe (docs/aspetto.md §4) ------------------------------------------

    /**
     * Scorrere a destra: letta ↔ da leggere. Si vede subito; se il server dice di no
     * la riga torna com'era e lo si dice.
     */
    fun cambiaLetta(mail: Mail) {
        val letta = !mail.letta
        sostituisci(mail.id) { it.copy(letta = letta) }
        viewModelScope.launch {
            when (val esito = client.segnaLetta(mail.id, letta)) {
                is Esito.Ok -> aggiornaConteggi()
                else -> {
                    sostituisci(mail.id) { it.copy(letta = mail.letta) }
                    _stato.update { it.copy(erroreAzione = esito.messaggio) }
                }
            }
        }
    }

    /** Scorrere a sinistra e scegliere il gruppo. Una mail alla volta: false = non è partito. */
    fun sposta(mail: Mail, gruppoId: Long, impara: Boolean): Boolean {
        val stato = _stato.value
        if (stato.inSpostamento != null || gruppoId == mail.gruppoId) return false
        val indice = stato.mail.indexOfFirst { it.id == mail.id }.coerceAtLeast(0)
        _stato.update { it.copy(inSpostamento = mail.id, erroreAzione = null, spostamento = null) }
        viewModelScope.launch {
            when (val esito = client.sposta(mail.id, gruppoId, impara)) {
                is Esito.Ok -> {
                    val nuova = unisci(esito.dato, gruppoId)
                    _stato.update { s ->
                        s.copy(
                            mail = rimettiNellaVista(s, nuova, indice = null),
                            inSpostamento = null,
                            spostamento = Spostamento(mail, indice, nuova.gruppo, impara),
                        )
                    }
                    aggiornaConteggi()
                }
                else -> _stato.update { it.copy(inSpostamento = null, erroreAzione = esito.messaggio) }
            }
        }
        return true
    }

    /**
     * "Annulla" (5 secondi dopo lo spostamento): la mail torna nel gruppo di prima. Con
     * "Ricorda questo mittente" acceso anche la regola torna indietro (contratto 17).
     */
    fun annullaSpostamento() {
        val s = _stato.value.spostamento ?: return
        val prima = s.prima.gruppoId ?: return
        if (_stato.value.inSpostamento != null) return
        _stato.update { it.copy(spostamento = null, inSpostamento = s.prima.id, erroreAzione = null) }
        viewModelScope.launch {
            when (val esito = client.sposta(s.prima.id, prima, s.impara)) {
                is Esito.Ok -> {
                    val tornata = unisci(esito.dato, prima)
                    _stato.update { st -> st.copy(mail = rimettiNellaVista(st, tornata, s.indice), inSpostamento = null) }
                    aggiornaConteggi()
                }
                else -> _stato.update {
                    it.copy(
                        inSpostamento = null,
                        erroreAzione = "Non sono riuscito a rimetterla in ${s.prima.gruppo ?: "quel gruppo"}. ${esito.messaggio.orEmpty()}".trim(),
                    )
                }
            }
        }
    }

    /** Il messaggio con "Annulla" è sparito (5 secondi, o un'altra pagina). */
    fun consumaSpostamento() = _stato.update { it.copy(spostamento = null) }

    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }

    /** La mail dopo un gesto: resta (o torna, al suo posto) se è ancora della vista, altrimenti esce. */
    private fun rimettiNellaVista(s: StatoPosta, mail: Mail, indice: Int?): List<Mail> {
        val presente = s.mail.any { it.id == mail.id }
        return when {
            !apparteneAllaVista(mail, s.gruppoId, s.filtro) -> s.mail.filterNot { it.id == mail.id }
            presente -> s.mail.map { if (it.id == mail.id) mail else it }
            else -> s.mail.toMutableList().apply { add((indice ?: 0).coerceIn(0, size), mail) }
        }
    }

    /** La mail tornata dal server, col nome del gruppo anche se il server non lo scrive. */
    private fun unisci(dalServer: Mail, gruppoId: Long): Mail {
        val id = dalServer.gruppoId ?: gruppoId
        return dalServer.copy(gruppoId = id, gruppo = dalServer.gruppo ?: _stato.value.gruppo(id)?.nome)
    }

    private fun sostituisci(id: Long, cambia: (Mail) -> Mail) {
        _stato.update { s -> s.copy(mail = s.mail.map { if (it.id == id) cambia(it) else it }) }
    }

    /** Dopo un gesto i numeri dei gruppi e del riquadro "Da fare" cambiano: si rileggono. */
    private fun aggiornaConteggi() {
        viewModelScope.launch {
            val nuovi = (client.conteggi() as? Esito.Ok)?.dato ?: return@launch
            _stato.update { it.copy(conteggi = nuovi) }
            withContext(Dispatchers.IO) { cache.scrivi("conteggi", JsonSol.encodeToString(Conteggi.serializer(), nuovi)) }
        }
    }
}
