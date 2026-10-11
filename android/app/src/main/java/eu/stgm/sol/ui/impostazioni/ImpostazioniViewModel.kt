package eu.stgm.sol.ui.impostazioni

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.Casella
import eu.stgm.sol.dati.CollegamentoOutlook
import eu.stgm.sol.dati.DepositoConfigurazione
import eu.stgm.sol.dati.Dispositivo
import eu.stgm.sol.dati.Gruppo
import eu.stgm.sol.dati.ImpostazioniSol
import eu.stgm.sol.dati.Regola
import eu.stgm.sol.dati.Versioni
import eu.stgm.sol.dati.leggiConCache
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.IndirizzoServer
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.Testi
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Il collegamento (o ricollegamento) di una casella Outlook in corso: codice da scrivere e indirizzo da aprire. */
data class CollegamentoInCorso(
    val casellaId: Long,
    val codice: String,
    val url: String,
    val stato: String = "in_collegamento",
    val errore: String? = null,
    val ricollegamento: Boolean = false,
) {
    val finito: Boolean get() = stato != "in_collegamento"
}

data class StatoImpostazioni(
    val caselle: List<Casella> = emptyList(),
    val gruppi: List<Gruppo> = emptyList(),
    val regole: List<Regola> = emptyList(),
    val impostazioni: ImpostazioniSol? = null,
    val dispositivi: List<Dispositivo> = emptyList(),
    val versioni: Versioni? = null,
    val questoDispositivo: Long? = null,
    val server: String? = null,
    val caricamento: Boolean = false,
    val inCorso: Boolean = false,
    val errore: String? = null,
    val daCacheDel: Long? = null,
    val messaggio: String? = null,
    /** Un gesto non riuscito o un dato da correggere: si dice dove Manuel sta guardando. */
    val erroreAzione: String? = null,
    val outlook: CollegamentoInCorso? = null,
    /** Una casella appena aggiunta o ricollegata: la finestra si chiude. */
    val casellaAggiunta: Boolean = false,
    /** Questo telefono è stato scollegato: si torna all'abbinamento. */
    val scollegato: Boolean = false,
    /** 15.2: spenta la modalità prova, le mail già smistate rimaste in Posta in arrivo. */
    val arretratoDaApplicare: Int? = null,
) {
    /** 15.1: al massimo 16 gruppi. */
    val puoAggiungereGruppo: Boolean get() = gruppi.size < Gruppo.MASSIMO

    /** Le password Gmail viaggiano solo sul collegamento protetto (15.13). */
    val passwordConsentita: Boolean get() = IndirizzoServer.passwordConsentita(server)

    val collegamentoNonProtetto: Boolean get() = IndirizzoServer.nonProtetto(server)
}

class ImpostazioniViewModel(
    private val client: SolClient,
    private val cache: Cache,
    private val deposito: DepositoConfigurazione,
    private val intervalloOutlookMs: Long = 5_000,
    private val tentativiOutlook: Int = 180,
    /** Via i dati salvati sul telefono (ultimi dati visti e bozze in modifica). */
    private val svuotaDati: () -> Unit = { cache.svuota() },
) : ViewModel() {

    private val _stato = MutableStateFlow(StatoImpostazioni())
    val stato: StateFlow<StatoImpostazioni> = _stato.asStateFlow()

    private var attesaOutlook: Job? = null

    fun carica() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val conf = deposito.leggi()
            val caselle = async { leggiConCache(cache, "caselle", ListSerializer(Casella.serializer())) { client.caselle() } }
            val gruppi = async { leggiConCache(cache, "gruppi", ListSerializer(Gruppo.serializer())) { client.gruppi() } }
            val regole = async { leggiConCache(cache, "regole", ListSerializer(Regola.serializer())) { client.regole() } }
            val dispositivi = async {
                leggiConCache(cache, "dispositivi", ListSerializer(Dispositivo.serializer())) { client.dispositivi() }
            }
            val versioni = async { client.versione() }
            val imp = leggiConCache(cache, "impostazioni", ImpostazioniSol.serializer()) { client.impostazioni() }
            val c = caselle.await()
            val g = gruppi.await()
            val r = regole.await()
            val d = dispositivi.await()
            val v = versioni.await()
            _stato.update {
                it.copy(
                    caselle = c.dato ?: it.caselle,
                    gruppi = (g.dato ?: it.gruppi).sortedBy { gruppo -> gruppo.ordine },
                    regole = r.dato ?: it.regole,
                    dispositivi = d.dato ?: it.dispositivi,
                    impostazioni = imp.dato ?: it.impostazioni,
                    versioni = (v as? Esito.Ok)?.dato ?: it.versioni,
                    questoDispositivo = conf?.dispositivoId,
                    server = conf?.server,
                    caricamento = false,
                    errore = imp.errore ?: c.errore,
                    daCacheDel = imp.daCacheDel ?: c.daCacheDel,
                )
            }
        }
    }

    // --- Caselle -----------------------------------------------------------------

    /** Prima di toccare una password: solo sul collegamento protetto (15.13). */
    private suspend fun passwordVietata(): Boolean {
        if (IndirizzoServer.passwordConsentita(deposito.leggi()?.server)) return false
        _stato.update { it.copy(erroreAzione = Testi.PASSWORD_SU_HTTP) }
        return true
    }

    fun aggiungiGmail(indirizzo: String, nome: String, password: String) {
        val i = indirizzo.trim()
        val p = password.replace(" ", "")
        val problema = when {
            !i.contains('@') -> "Scrivi l'indirizzo Gmail completo, per esempio studio.rossi@gmail.com."
            p.length != 16 -> "La password per le app è fatta di 16 lettere (Google la mostra in 4 gruppi da 4)."
            else -> null
        }
        if (problema != null) {
            _stato.update { it.copy(erroreAzione = problema) }
            return
        }
        viewModelScope.launch {
            if (passwordVietata()) return@launch
            lavora {
                when (val e = client.aggiungiGmail(i, nome.trim().ifEmpty { i }, password.trim())) {
                    is Esito.Ok -> {
                        _stato.update { it.copy(casellaAggiunta = true) }
                        ricaricaCaselle()
                        "Casella $i collegata."
                    }
                    else -> fallita(e)
                }
            }
        }
    }

    /** 15.3: una Gmail in errore si ricollega con la password nuova, senza perdere niente. */
    fun ricollegaGmail(casellaId: Long, password: String) {
        if (password.replace(" ", "").length != 16) {
            _stato.update {
                it.copy(erroreAzione = "La password per le app è fatta di 16 lettere (Google la mostra in 4 gruppi da 4).")
            }
            return
        }
        viewModelScope.launch {
            if (passwordVietata()) return@launch
            lavora {
                when (val e = client.ricollegaGmail(casellaId, password.trim())) {
                    is Esito.Ok -> {
                        _stato.update { it.copy(casellaAggiunta = true) }
                        ricaricaCaselle()
                        "Casella ricollegata: Sol riprende da dove era rimasto."
                    }
                    else -> fallita(e)
                }
            }
        }
    }

    fun aggiungiFinta(nome: String) = lavora {
        when (val e = client.aggiungiFinta(nome.trim().ifEmpty { "Prova" })) {
            is Esito.Ok -> {
                _stato.update { it.copy(casellaAggiunta = true) }
                ricaricaCaselle()
                "Casella di prova aggiunta: contiene solo mail inventate."
            }
            else -> fallita(e)
        }
    }

    fun aggiungiOutlook(nome: String) = lavora {
        when (val e = client.aggiungiOutlook(nome.trim().ifEmpty { "Studio" })) {
            is Esito.Ok -> {
                avviaOutlook(e.dato, null, ricollegamento = false)
                null
            }
            else -> fallita(e)
        }
    }

    /** 15.3: un Outlook in errore si ricollega col codice, come la prima volta. */
    fun ricollegaOutlook(casellaId: Long) = lavora {
        when (val e = client.ricollegaOutlook(casellaId)) {
            is Esito.Ok -> {
                avviaOutlook(e.dato, casellaId, ricollegamento = true)
                null
            }
            else -> fallita(e)
        }
    }

    private fun avviaOutlook(c: CollegamentoOutlook, casellaId: Long?, ricollegamento: Boolean) {
        val id = c.casella?.id ?: casellaId ?: return
        _stato.update {
            it.copy(
                outlook = CollegamentoInCorso(
                    id,
                    c.codiceUtente,
                    c.urlVerifica,
                    c.casella?.stato ?: "in_collegamento",
                    ricollegamento = ricollegamento,
                ),
            )
        }
        seguiOutlook(id)
    }

    /** Ricarica la casella ogni 5 secondi finché il collegamento è finito (contratto §4). */
    private fun seguiOutlook(id: Long) {
        attesaOutlook?.cancel()
        attesaOutlook = viewModelScope.launch {
            repeat(tentativiOutlook) {
                delay(intervalloOutlookMs)
                val e = client.casella(id)
                if (e is Esito.Ok && e.dato.stato != "in_collegamento") {
                    _stato.update {
                        it.copy(
                            outlook = it.outlook?.copy(stato = e.dato.stato, errore = e.dato.errore),
                            messaggio = if (e.dato.stato == "collegata") "Casella Outlook collegata." else it.messaggio,
                        )
                    }
                    ricaricaCaselle()
                    return@launch
                }
            }
            _stato.update {
                it.copy(outlook = it.outlook?.copy(stato = "errore", errore = "Il codice è scaduto. Riprova da capo."))
            }
        }
    }

    fun chiudiOutlook() {
        attesaOutlook?.cancel()
        attesaOutlook = null
        _stato.update { it.copy(outlook = null) }
        ricaricaCaselleInSottofondo()
    }

    fun casellaAggiuntaVista() = _stato.update { it.copy(casellaAggiunta = false) }

    fun controllaCasella(id: Long) = lavora {
        when (val e = client.controllaCasella(id)) {
            is Esito.Ok -> "Controllo avviato: le mail nuove arrivano tra poco."
            else -> fallita(e)
        }
    }

    fun togliCasella(id: Long) = lavora {
        when (val e = client.togliCasella(id)) {
            is Esito.Ok -> {
                _stato.update { s -> s.copy(caselle = s.caselle.filterNot { it.id == id }) }
                "Casella scollegata. Nella casella vera non è cambiato niente."
            }
            else -> fallita(e)
        }
    }

    // --- Gruppi e regole -----------------------------------------------------------

    /** 15.14: con la frase che dice a Sol che mail ci vanno. */
    fun aggiungiGruppo(nome: String, descrizione: String) {
        val n = nome.trim()
        when {
            n.isEmpty() -> {
                _stato.update { it.copy(erroreAzione = "Scrivi il nome del gruppo.") }
                return
            }
            !_stato.value.puoAggiungereGruppo -> {
                _stato.update { it.copy(erroreAzione = TROPPI_GRUPPI) }
                return
            }
        }
        lavora {
            when (val e = client.creaGruppo(n, descrizione.trim().ifEmpty { null })) {
                is Esito.Ok -> {
                    ricaricaGruppi()
                    "Gruppo «$n» aggiunto."
                }
                else -> fallita(e)
            }
        }
    }

    fun rinominaGruppo(id: Long, nome: String) {
        val n = nome.trim()
        if (n.isEmpty()) {
            _stato.update { it.copy(erroreAzione = "Il nome non può essere vuoto.") }
            return
        }
        lavora {
            when (val e = client.rinominaGruppo(id, n)) {
                is Esito.Ok -> {
                    ricaricaGruppi()
                    "Gruppo rinominato."
                }
                else -> fallita(e)
            }
        }
    }

    fun cambiaDescrizioneGruppo(id: Long, descrizione: String) {
        val d = descrizione.trim()
        if (d.isEmpty()) {
            _stato.update { it.copy(erroreAzione = "Scrivi in una frase che mail vanno in questo gruppo.") }
            return
        }
        lavora {
            when (val e = client.cambiaDescrizioneGruppo(id, d)) {
                is Esito.Ok -> {
                    _stato.update { s -> s.copy(gruppi = s.gruppi.map { if (it.id == id) it.copy(descrizione = d) else it }) }
                    "Descrizione salvata: Sol la usa dalle prossime mail."
                }
                else -> fallita(e)
            }
        }
    }

    /** 17: [colore] è uno dei 12 nomi della guida (blu, verde, ...). */
    fun cambiaColoreGruppo(id: Long, colore: String) = lavora {
        when (val e = client.cambiaColoreGruppo(id, colore)) {
            is Esito.Ok -> {
                _stato.update { s -> s.copy(gruppi = s.gruppi.map { if (it.id == id) it.copy(colore = colore) else it }) }
                "Colore cambiato."
            }
            else -> fallita(e)
        }
    }

    /** 18.2: una raccolta sta chiusa in una riga sola nella Posta "Importanti". */
    fun cambiaRaccoltaGruppo(id: Long, raccolta: Boolean) = lavora {
        when (val e = client.cambiaRaccoltaGruppo(id, raccolta)) {
            is Esito.Ok -> {
                _stato.update { s -> s.copy(gruppi = s.gruppi.map { if (it.id == id) it.copy(raccolta = raccolta) else it }) }
                null
            }
            else -> fallita(e)
        }
    }

    /**
     * Sposta un gruppo di un posto. Il contratto ha solo `ordine` per gruppo: si
     * rinumerano da 1 quelli che cambiano, così l'ordine vale anche se prima erano
     * tutti uguali.
     */
    fun spostaGruppo(id: Long, su: Boolean) = lavora {
        val lista = _stato.value.gruppi
        val i = lista.indexOfFirst { it.id == id }
        val j = if (su) i - 1 else i + 1
        if (i < 0 || j !in lista.indices) return@lavora null
        val nuova = lista.toMutableList().apply { add(j, removeAt(i)) }
        nuova.forEachIndexed { k, g ->
            if (g.ordine != k + 1) {
                val e = client.cambiaOrdineGruppo(g.id, k + 1)
                if (e !is Esito.Ok) {
                    ricaricaGruppi()
                    fallita(e)
                }
            }
        }
        _stato.update { it.copy(gruppi = nuova.mapIndexed { k, g -> g.copy(ordine = k + 1) }) }
        null
    }

    fun togliGruppo(id: Long) = lavora {
        when (val e = client.togliGruppo(id)) {
            is Esito.Ok -> {
                _stato.update { s -> s.copy(gruppi = s.gruppi.filterNot { it.id == id }) }
                "Gruppo tolto: le sue mail sono passate in Altro."
            }
            else -> fallita(e)
        }
    }

    fun togliRegola(id: Long) = lavora {
        when (val e = client.togliRegola(id)) {
            is Esito.Ok -> {
                _stato.update { s -> s.copy(regole = s.regole.filterNot { it.id == id }) }
                "Regola tolta."
            }
            else -> fallita(e)
        }
    }

    // --- Come lavora Sol -------------------------------------------------------------

    /**
     * Spegnere la modalità prova vale per le mail nuove (15.2). Se ci sono mail già
     * smistate rimaste in Posta in arrivo, poi si chiede se spostare anche quelle.
     */
    fun cambiaModalitaProva(attiva: Boolean) = lavora {
        when (val e = client.modificaImpostazioni(buildJsonObject { put("modalita_prova", attiva) })) {
            is Esito.Ok -> {
                _stato.update { it.copy(impostazioni = e.dato) }
                if (attiva) {
                    "Modalità prova accesa: Sol suggerisce e non sposta niente."
                } else {
                    val arretrato = (client.arretrato() as? Esito.Ok)?.dato?.daApplicare ?: 0
                    if (arretrato > 0) _stato.update { it.copy(arretratoDaApplicare = arretrato) }
                    "Modalità prova spenta: da adesso Sol sposta nelle caselle le mail nuove che smista."
                }
            }
            else -> fallita(e)
        }
    }

    /** 15.2: il sì di Manuel per spostare anche le mail già smistate. */
    fun applicaArretrato() {
        val quante = _stato.value.arretratoDaApplicare ?: return
        _stato.update { it.copy(arretratoDaApplicare = null) }
        lavora {
            when (val e = client.applicaArretrato()) {
                is Esito.Ok -> {
                    val n = e.dato.daApplicare.takeIf { it > 0 } ?: quante
                    "Sposto $n mail nella casella: ci vorrà qualche minuto. Quelle che hai già spostato tu restano dove sono."
                }
                else -> fallita(e)
            }
        }
    }

    fun lasciaArretrato() = _stato.update { it.copy(arretratoDaApplicare = null) }

    fun cambiaSoglia(valore: Double) =
        cambia(buildJsonObject { put("soglia_sicurezza", (valore * 100).toInt() / 100.0) }) { "Soglia salvata." }

    /** 16.2: tra 0,2 e 0,9; più bassa = più mail segnalate. */
    fun cambiaSogliaTruffa(valore: Double) {
        val v = (valore.coerceIn(ImpostazioniSol.SOGLIA_TRUFFA_MINIMA, ImpostazioniSol.SOGLIA_TRUFFA_MASSIMA) * 100).toInt() / 100.0
        cambia(buildJsonObject { put("soglia_truffa", v) }) { "Controllo truffe salvato." }
    }

    fun cambiaGiorniRisposta(giorni: Int) =
        cambia(buildJsonObject { put("giorni_risposta", giorni.coerceIn(1, 30)) }) { "Salvato." }

    fun cambiaGiorniSollecito(giorni: Int) =
        cambia(buildJsonObject { put("giorni_sollecito", giorni.coerceIn(1, 60)) }) { "Salvato." }

    /** 18.3: come salutare nella pagina Oggi; al massimo 40 caratteri. */
    fun cambiaNome(nome: String) = cambia(buildJsonObject { put("nome", nome.trim().take(40)) }) { "Nome salvato." }

    fun cambiaFirma(firma: String) = cambia(buildJsonObject { put("firma", firma) }) { "Firma salvata." }

    fun cambiaTono(tono: String) = cambia(buildJsonObject { put("tono", tono.trim()) }) { "Tono salvato." }

    private fun cambia(cambi: JsonObject, frase: () -> String) = lavora {
        when (val e = client.modificaImpostazioni(cambi)) {
            is Esito.Ok -> {
                _stato.update { it.copy(impostazioni = e.dato) }
                frase()
            }
            else -> fallita(e)
        }
    }

    // --- Dispositivi -----------------------------------------------------------------

    fun revocaDispositivo(id: Long) {
        if (id == _stato.value.questoDispositivo) {
            scollegaQuesto()
            return
        }
        lavora {
            when (val e = client.revocaDispositivo(id)) {
                is Esito.Ok -> {
                    _stato.update { s -> s.copy(dispositivi = s.dispositivi.filterNot { it.id == id }) }
                    "Dispositivo scollegato."
                }
                else -> fallita(e)
            }
        }
    }

    /**
     * Scollega questo telefono: prima lo revoca sul server (così il token non vale
     * più da nessuna parte), poi lo dimentica qui. Se il server non risponde lo si
     * dice e non si dimentica niente: un token buono resterebbe valido sul server.
     */
    fun scollegaQuesto() {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            val id = deposito.leggi()?.dispositivoId
            val esito = if (id != null) client.revocaDispositivo(id) else Esito.Ok(Unit)
            // 404/401: il server non lo conosce già più, il risultato è lo stesso.
            val revocato = esito is Esito.Ok || (esito is Esito.Errore && esito.stato in setOf(401, 404))
            if (!revocato) {
                _stato.update {
                    it.copy(
                        inCorso = false,
                        erroreAzione = "Non sono riuscito a scollegare questo telefono dal server: " +
                            "${esito.messaggio.orEmpty()} Riprova quando il server di casa risponde.",
                    )
                }
                return@launch
            }
            deposito.dimentica()
            svuotaDati()
            _stato.update { it.copy(inCorso = false, scollegato = true) }
        }
    }

    fun mostraMessaggio(testo: String) = _stato.update { it.copy(messaggio = testo) }
    fun consumaMessaggio() = _stato.update { it.copy(messaggio = null) }
    fun consumaErroreAzione() = _stato.update { it.copy(erroreAzione = null) }

    // --- Meccanica -------------------------------------------------------------------

    private class Fallita(val motivo: String?) : Exception()

    private fun fallita(e: Esito<*>): Nothing = throw Fallita(e.messaggio)

    /** Un'azione alla volta; il blocco torna la frase da mostrare (null = niente). */
    private fun lavora(blocco: suspend () -> String?) {
        if (_stato.value.inCorso) return
        _stato.update { it.copy(inCorso = true, erroreAzione = null) }
        viewModelScope.launch {
            try {
                val frase = blocco()
                _stato.update { it.copy(inCorso = false, messaggio = frase ?: it.messaggio) }
            } catch (f: Fallita) {
                _stato.update { it.copy(inCorso = false, erroreAzione = f.motivo) }
            }
        }
    }

    private suspend fun ricaricaCaselle() {
        val c = leggiConCache(cache, "caselle", ListSerializer(Casella.serializer())) { client.caselle() }
        c.dato?.let { lista -> _stato.update { it.copy(caselle = lista) } }
    }

    private fun ricaricaCaselleInSottofondo() {
        viewModelScope.launch { ricaricaCaselle() }
    }

    private suspend fun ricaricaGruppi() {
        val g = leggiConCache(cache, "gruppi", ListSerializer(Gruppo.serializer())) { client.gruppi() }
        g.dato?.let { lista -> _stato.update { it.copy(gruppi = lista.sortedBy { gruppo -> gruppo.ordine }) } }
    }

    companion object {
        const val TROPPI_GRUPPI = "Puoi avere al massimo 16 gruppi: togline uno prima di aggiungerne un altro."
    }
}
