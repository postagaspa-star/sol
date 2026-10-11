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
import eu.stgm.sol.dati.UltimaMail
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

/** I due segmenti della Posta (docs/aspetto.md §4). */
enum class ModoPosta { IMPORTANTI, TUTTE }

/** Una sezione di "Importanti": le mail di un gruppo (null = senza gruppo). */
data class SezionePosta(val gruppo: Gruppo?, val mail: List<Mail>)

/** La riga chiusa di una raccolta: "Newsletter · 6 mail · Webinar...". */
data class RigaRaccolta(val gruppo: Gruppo, val totale: Int, val daLeggere: Int, val ultima: UltimaMail?) {
    val sotto: String get() = "$totale mail" + (ultima?.oggetto?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

/**
 * "Importanti": i gruppi che non sono raccolte, nell'ordine dei gruppi, ognuno con
 * le sue mail (§4). Le mail senza gruppo noto vanno in fondo.
 */
fun sezioniImportanti(mail: List<Mail>, gruppi: List<Gruppo>): List<SezionePosta> {
    val raccolte = gruppi.filter { it.raccolta }.map { it.id }.toSet()
    val noti = gruppi.map { it.id }.toSet()
    val perGruppo = mail.filterNot { it.gruppoId in raccolte }.groupBy { it.gruppoId }
    val sezioni = gruppi.sortedBy { it.ordine }.filterNot { it.raccolta }.mapNotNull { g ->
        perGruppo[g.id]?.let { SezionePosta(g, it) }
    }
    val senza = perGruppo.filterKeys { it == null || it !in noti }.values.flatten()
    return if (senza.isEmpty()) sezioni else sezioni + SezionePosta(null, senza)
}

/** Le raccolte con almeno una mail, nell'ordine dei gruppi (18.2). */
fun righeRaccolte(gruppi: List<Gruppo>, conteggi: Conteggi?): List<RigaRaccolta> =
    gruppi.sortedBy { it.ordine }.filter { it.raccolta }.map { g ->
        val c = conteggi?.gruppi?.get(g.id.toString())
        RigaRaccolta(g, c?.totale ?: g.totale, c?.daLeggere ?: g.daLeggere, c?.ultima)
    }.filter { it.totale > 0 }

/** Il numero sulla voce Posta della barra: da leggere, solo nei gruppi importanti (§2). */
fun daLeggereImportanti(gruppi: List<Gruppo>, conteggi: Conteggi?): Int =
    gruppi.filterNot { it.raccolta }.sumOf { g -> conteggi?.gruppi?.get(g.id.toString())?.daLeggere ?: g.daLeggere }

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
    /** null = tutti i gruppi; un gruppo = la pagina di una raccolta. */
    val gruppoId: Long? = null,
    val filtro: FiltroRapido = FiltroRapido.TUTTE,
    /** La ricerca: null = nessuna. */
    val cerca: String? = null,
    val modo: ModoPosta = ModoPosta.IMPORTANTI,
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
    /** I numeri dei gruppi (15.4, 18.2); null = non ancora letti. */
    val conteggi: Conteggi? = null,
    /** Le mail incerte da smistare (non le possibili truffe): la scheda "N mail da sistemare". */
    val daSistemare: Int = 0,
    /** Un gesto (letta, sposta, annulla) non è riuscito: si dice in una finestra. */
    val erroreAzione: String? = null,
    /** La mail che si sta spostando: la sua riga resta scivolata via finché il server risponde. */
    val inSpostamento: Long? = null,
    /** L'ultimo spostamento col gesto, finché il messaggio con "Annulla" è in vista. */
    val spostamento: Spostamento? = null,
) {
    val sezioni: List<SezionePosta> get() = sezioniImportanti(mail, gruppi)
    val raccolte: List<RigaRaccolta> get() = righeRaccolte(gruppi, conteggi)
    val daLeggere: Int get() = daLeggereImportanti(gruppi, conteggi)

    /** Le mail da leggere di un gruppo: dai conteggi se ci sono, altrimenti dall'elenco dei gruppi. */
    fun daLeggere(gruppo: Gruppo): Int = conteggi?.gruppi?.get(gruppo.id.toString())?.daLeggere ?: gruppo.daLeggere

    fun gruppo(id: Long?): Gruppo? = id?.let { g -> gruppi.firstOrNull { it.id == g } }
}

/** Chiave della cache per la combinazione gruppo + filtro: ogni vista ha i suoi ultimi dati. */
internal fun chiaveCacheMail(gruppoId: Long?, filtro: FiltroRapido) = "mail-${gruppoId ?: "tutte"}-${filtro.name}"

/**
 * La Posta, la pagina di una raccolta ([scegliGruppo]) e la ricerca ([cerca]): lo
 * stesso elenco con gli stessi gesti.
 */
class PostaViewModel(private val client: SolClient, private val cache: Cache) : ViewModel() {

    private val _stato = MutableStateFlow(StatoPosta())
    val stato: StateFlow<StatoPosta> = _stato.asStateFlow()

    private var lettura: Job? = null

    // "Mostra mail più vecchie" ancora in arrivo quando si cambia vista: va annullato,
    // altrimenti una pagina della vista di prima finisce dentro quella nuova.
    private var letturaAltre: Job? = null

    fun carica() {
        lettura?.cancel()
        letturaAltre?.cancel()
        val gruppoId = _stato.value.gruppoId
        val filtro = _stato.value.filtro
        val cerca = _stato.value.cerca
        if (cerca != null && cerca.isBlank()) {
            _stato.update { it.copy(mail = emptyList(), altre = false, caricamento = false, errore = null, caricataAlmenoUnaVolta = true) }
            return
        }
        _stato.update { it.copy(caricamento = true, caricamentoAltre = false) }
        lettura = viewModelScope.launch {
            val gruppi = async { leggiConCache(cache, "gruppi", ListSerializer(Gruppo.serializer())) { client.gruppi() } }
            val impostazioni = async { leggiConCache(cache, "impostazioni", ImpostazioniSol.serializer()) { client.impostazioni() } }
            val conteggi = async { leggiConCache(cache, "conteggi", Conteggi.serializer()) { client.conteggi() } }
            // La scheda "N mail da sistemare" c'è solo nella Posta (non nelle raccolte né nella ricerca).
            val incerte = if (gruppoId == null && cerca == null) {
                async { (client.mail(FiltroMail(filtro = FiltroRapido.DA_CONTROLLARE)) as? Esito.Ok)?.dato }
            } else {
                null
            }
            val mail = if (cerca != null) {
                when (val e = client.mail(FiltroMail(gruppoId = gruppoId, filtro = filtro, cerca = cerca))) {
                    is Esito.Ok -> eu.stgm.sol.dati.Lettura(e.dato)
                    else -> eu.stgm.sol.dati.Lettura(null, e.messaggio)
                }
            } else {
                leggiConCache(cache, chiaveCacheMail(gruppoId, filtro), PaginaMail.serializer()) {
                    client.mail(FiltroMail(gruppoId = gruppoId, filtro = filtro))
                }
            }
            val g = gruppi.await()
            val i = impostazioni.await()
            val c = conteggi.await()
            val n = incerte?.await()
            _stato.update {
                if (it.gruppoId != gruppoId || it.filtro != filtro || it.cerca != cerca) return@update it
                it.copy(
                    gruppi = (g.dato ?: it.gruppi).sortedBy { gruppo -> gruppo.ordine },
                    modalitaProva = i.dato?.modalitaProva ?: it.modalitaProva,
                    conteggi = c.dato ?: it.conteggi,
                    daSistemare = n?.mail?.count { m -> m.daControllare && !m.sospetta } ?: it.daSistemare,
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

    fun scegliModo(modo: ModoPosta) = _stato.update { it.copy(modo = modo) }

    /** La pagina di una raccolta: le mail di quel gruppo. */
    fun scegliGruppo(id: Long?) {
        if (id == _stato.value.gruppoId && _stato.value.caricataAlmenoUnaVolta) return
        _stato.update { it.copy(gruppoId = id, mail = emptyList(), altre = false, caricataAlmenoUnaVolta = false) }
        carica()
    }

    /** Toccare il filtro già scelto lo toglie. */
    fun scegliFiltro(filtro: FiltroRapido) {
        val nuovo = if (_stato.value.filtro == filtro) FiltroRapido.TUTTE else filtro
        _stato.update { it.copy(filtro = nuovo, mail = emptyList(), altre = false) }
        carica()
    }

    /** La ricerca: vuota = nessun risultato e nessuna richiesta. */
    fun cerca(testo: String) {
        if (testo == _stato.value.cerca) return
        _stato.update { it.copy(cerca = testo, mail = emptyList(), altre = false) }
        carica()
    }

    fun caricaAltre() {
        val stato = _stato.value
        val ultima = stato.mail.lastOrNull() ?: return
        if (!stato.altre || stato.caricamentoAltre || stato.caricamento) return
        _stato.update { it.copy(caricamentoAltre = true) }
        letturaAltre = viewModelScope.launch {
            val esito = client.mail(FiltroMail(gruppoId = stato.gruppoId, filtro = stato.filtro, primaDi = ultima.id, cerca = stato.cerca))
            _stato.update {
                // Una pagina della vista di prima non entra in quella nuova.
                if (it.gruppoId != stato.gruppoId || it.filtro != stato.filtro || it.cerca != stato.cerca) {
                    return@update it.copy(caricamentoAltre = false)
                }
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

    /** "Segna tutte lette" nella pagina di una raccolta: quelle in elenco, una per una. */
    fun segnaTutteLette() {
        val daSegnare = _stato.value.mail.filterNot { it.letta }
        if (daSegnare.isEmpty()) return
        daSegnare.forEach { m -> sostituisci(m.id) { it.copy(letta = true) } }
        viewModelScope.launch {
            val rifiutate = daSegnare.mapNotNull { m ->
                val esito = client.segnaLetta(m.id, true)
                if (esito is Esito.Ok) null else m to esito.messaggio
            }
            rifiutate.forEach { (m, _) -> sostituisci(m.id) { it.copy(letta = false) } }
            rifiutate.firstOrNull()?.let { (_, motivo) -> _stato.update { it.copy(erroreAzione = motivo) } }
            aggiornaConteggi()
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

    /** Dopo un gesto i numeri dei gruppi cambiano: si rileggono. */
    private fun aggiornaConteggi() {
        viewModelScope.launch {
            val nuovi = (client.conteggi() as? Esito.Ok)?.dato ?: return@launch
            _stato.update { it.copy(conteggi = nuovi) }
            withContext(Dispatchers.IO) { cache.scrivi("conteggi", JsonSol.encodeToString(Conteggi.serializer(), nuovi)) }
        }
    }
}
