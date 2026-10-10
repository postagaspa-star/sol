package eu.stgm.sol.ui

import eu.stgm.sol.dati.Bozza
import eu.stgm.sol.dati.Cache
import eu.stgm.sol.dati.CacheMemoria
import eu.stgm.sol.dati.JsonSol
import eu.stgm.sol.dati.ModificaBozza
import eu.stgm.sol.rete.Esito
import eu.stgm.sol.rete.SolClient
import eu.stgm.sol.rete.messaggio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Cosa mostra la finestra di conferma prima dell'invio: i dati RILETTI dal server
 * della [versione] che parte (15.11), non quelli visti prima sullo schermo.
 */
data class RichiestaConferma(
    val bozzaId: Long,
    val versione: Int,
    val a: List<String>,
    val cc: List<String>,
    val oggetto: String,
    val primaRiga: String,
    /** A quella mail Manuel ha già risposto da un altro programma (15.13). */
    val giaRisposto: Boolean = false,
    /** Il server ha detto che nel frattempo era cambiata: questa è la versione nuova. */
    val cambiata: Boolean = false,
)

/** I campi della bozza mentre Manuel la modifica (testo libero, come li scrive). */
@Serializable
data class CampiBozza(val a: String, val cc: String, val oggetto: String, val testo: String) {
    companion object {
        fun da(bozza: Bozza) = CampiBozza(bozza.a.joinToString(", "), bozza.cc.joinToString(", "), bozza.oggetto, bozza.testo)
    }
}

data class StatoBozza(
    val bozza: Bozza? = null,
    val caricamento: Boolean = false,
    /** Sol sta (ri)scrivendo la bozza: si ricarica da sola. */
    val inAttesa: Boolean = false,
    val campi: CampiBozza? = null,
    val confermaInvio: RichiestaConferma? = null,
    /** Si sta rileggendo la bozza per costruire la conferma. */
    val preparazioneConferma: Boolean = false,
    val inCorso: Boolean = false,
    /**
     * Dopo un invio senza esito certo "Invia" resta spento: la bozza si ricontrolla
     * per un paio di minuti e il suo stato dice se la mail è partita.
     */
    val verificaInvio: Boolean = false,
    /** Rifare senza istruzioni una bozza modificata a mano: prima si chiede se buttare le modifiche. */
    val chiediDaZero: Boolean = false,
    val messaggio: String? = null,
    val errore: String? = null,
) {
    val inModifica: Boolean get() = campi != null

    /** Durante la conferma e l'invio i campi non si toccano e niente si salva. */
    val campiBloccati: Boolean get() = confermaInvio != null || preparazioneConferma || inCorso || verificaInvio

    val invioPossibile: Boolean get() = !campiBloccati && !inModifica && bozza?.inviabile == true
}

/**
 * La bozza di una risposta o di un sollecito: leggere, modificare, rifare con
 * istruzioni, scartare, inviare. Sta qui e non nella schermata perché la regola
 * più importante di Sol vive qui: **nessuna mail parte senza un sì esplicito, e
 * parte solo la versione che Manuel ha visto**.
 *
 * L'invio è in due tempi: [chiediInvio] RILEGGE la bozza dal server e prepara la
 * conferma con destinatari, Cc, oggetto e prima riga di quella versione; solo
 * [confermaInvio] chiama il server, con quella versione. Se intanto la bozza è
 * cambiata il server non invia (409) e la conferma si ripresenta coi dati nuovi.
 *
 * Il testo in modifica è salvato per bozza in [memoria] (sopravvive al cambio di
 * mail e alla chiusura dell'app), insieme alla conferma aperta, che dopo una
 * rotazione o un riavvio si ricostruisce rileggendo la bozza.
 */
class GestoreBozza(
    private val client: SolClient,
    private val ambito: CoroutineScope,
    private val intervalloMs: Long = 2_000,
    private val tentativiMassimi: Int = 90,
    private val memoria: Cache = CacheMemoria(),
    private val verificaOgniMs: Long = 10_000,
    private val verificaPerMs: Long = 120_000,
    private val dopoInvio: (Bozza) -> Unit = {},
) {
    private val _stato = MutableStateFlow(StatoBozza())
    val stato: StateFlow<StatoBozza> = _stato.asStateFlow()

    private var attesa: Job? = null
    private var verifica: Job? = null

    /** Mostra la bozza [id] (null = nessuna bozza). */
    fun imposta(id: Long?) {
        if (id == null) {
            ferma()
            _stato.value = StatoBozza()
            return
        }
        val attuale = _stato.value.bozza
        if (attuale?.id == id) {
            // La stessa bozza: si rilegge in silenzio (può essere cambiata dal PC).
            if (attuale.inPreparazione) riprendi() else ambito.launch { leggi(id, mostraErrore = false) }
            return
        }
        ferma()
        _stato.value = StatoBozza(caricamento = true)
        ambito.launch { leggi(id) }
    }

    fun ricarica() {
        val id = _stato.value.bozza?.id ?: return
        ambito.launch { leggi(id) }
    }

    private suspend fun leggi(id: Long, mostraErrore: Boolean = true) {
        when (val esito = client.bozza(id)) {
            is Esito.Ok -> {
                val bozza = esito.dato
                _stato.update { s ->
                    if (s.bozza != null && s.bozza.id != id) return@update s
                    s.copy(bozza = bozza, caricamento = false, campi = s.campi ?: campiSalvati(bozza))
                }
                if (bozza.chiusa) memoria.cancella(chiaveCampi(id))
                if (bozza.inPreparazione) aspettaChePronta(id)
                // Una conferma aperta prima di una rotazione o di un riavvio: si ricostruisce coi dati riletti.
                if (memoria.leggi(CHIAVE_CONFERMA)?.json == id.toString() && _stato.value.confermaInvio == null) {
                    preparaConferma(id, cambiata = false)
                }
            }
            else -> _stato.update { it.copy(caricamento = false, errore = if (mostraErrore) esito.messaggio else it.errore) }
        }
    }

    /** I campi in modifica salvati per questa bozza, se si può ancora modificarla. */
    private fun campiSalvati(bozza: Bozza): CampiBozza? {
        if (bozza.chiusa || bozza.inPreparazione) return null
        val json = memoria.leggi(chiaveCampi(bozza.id))?.json ?: return null
        return try {
            JsonSol.decodeFromString(CampiBozza.serializer(), json)
        } catch (e: Exception) {
            null
        }
    }

    /** Se la bozza è in preparazione la ricarica ogni [intervalloMs] finché è pronta. */
    private fun aspettaChePronta(id: Long) {
        if (attesa?.isActive == true) return
        _stato.update { it.copy(inAttesa = true) }
        attesa = ambito.launch {
            repeat(tentativiMassimi) {
                delay(intervalloMs)
                val esito = client.bozza(id)
                if (esito is Esito.Ok) {
                    _stato.update { it.copy(bozza = esito.dato) }
                    if (!esito.dato.inPreparazione) {
                        _stato.update { it.copy(inAttesa = false) }
                        return@launch
                    }
                }
            }
            _stato.update {
                it.copy(inAttesa = false, errore = "Sol ci sta mettendo più del solito. Riprova ad aprire la bozza tra poco.")
            }
        }
    }

    /** La schermata è andata via: niente ricariche a vuoto (la verifica di un invio continua). */
    fun ferma() {
        attesa?.cancel()
        attesa = null
        _stato.update { it.copy(inAttesa = false) }
    }

    fun riprendi() {
        val bozza = _stato.value.bozza ?: return
        if (bozza.inPreparazione) aspettaChePronta(bozza.id)
    }

    // --- Modifica ----------------------------------------------------------------

    fun avviaModifica() {
        val stato = _stato.value
        val bozza = stato.bozza ?: return
        if (stato.campiBloccati) return
        val campi = campiSalvati(bozza) ?: CampiBozza.da(bozza)
        _stato.update { it.copy(campi = campi) }
        ricordaCampi(bozza.id, campi)
    }

    fun cambiaCampi(campi: CampiBozza) {
        val stato = _stato.value
        val bozza = stato.bozza ?: return
        if (!stato.inModifica || stato.campiBloccati) return
        _stato.update { it.copy(campi = campi) }
        ricordaCampi(bozza.id, campi)
    }

    fun annullaModifica() {
        val id = _stato.value.bozza?.id
        _stato.update { it.copy(campi = null) }
        id?.let { memoria.cancella(chiaveCampi(it)) }
    }

    /** Ci sono modifiche scritte e non ancora salvate sul server. */
    fun haModificheNonSalvate(): Boolean {
        val stato = _stato.value
        val bozza = stato.bozza ?: return false
        val campi = stato.campi ?: return false
        return campi != CampiBozza.da(bozza)
    }

    private fun ricordaCampi(id: Long, campi: CampiBozza) {
        memoria.scrivi(chiaveCampi(id), JsonSol.encodeToString(CampiBozza.serializer(), campi))
    }

    /** [poi]: cosa fare dopo un salvataggio riuscito (per esempio aprire un'altra mail). */
    fun salvaModifica(poi: () -> Unit = {}) {
        val stato = _stato.value
        val bozza = stato.bozza ?: return
        val campi = stato.campi ?: return
        if (stato.campiBloccati) return
        val (a, aSbagliato) = leggiIndirizzi(campi.a)
        val (cc, ccSbagliato) = leggiIndirizzi(campi.cc)
        val sbagliato = aSbagliato ?: ccSbagliato
        when {
            sbagliato != null -> {
                _stato.update { it.copy(errore = "«$sbagliato» non sembra un indirizzo di posta. Controllalo.") }
                return
            }
            a.isEmpty() -> {
                _stato.update { it.copy(errore = "Scrivi almeno un destinatario.") }
                return
            }
        }
        _stato.update { it.copy(inCorso = true, errore = null) }
        ambito.launch {
            when (val esito = client.salvaBozza(bozza.id, ModificaBozza(a, cc, campi.oggetto.trim(), campi.testo))) {
                is Esito.Ok -> {
                    memoria.cancella(chiaveCampi(bozza.id))
                    _stato.update {
                        it.copy(bozza = esito.dato, campi = null, inCorso = false, messaggio = "Modifiche salvate.")
                    }
                    poi()
                }
                else -> _stato.update { it.copy(inCorso = false, errore = esito.messaggio) }
            }
        }
    }

    // --- Rifare, scartare ----------------------------------------------------------

    /**
     * Rifà la bozza partendo da quella attuale (15.5, 15.13). Senza istruzioni, su
     * una bozza modificata a mano, prima chiede se buttare le modifiche ([chiediDaZero]).
     */
    fun rifai(istruzioni: String?) {
        val stato = _stato.value
        val bozza = stato.bozza ?: return
        if (stato.campiBloccati || stato.inModifica) return
        if (istruzioni.isNullOrBlank() && bozza.modificata) {
            _stato.update { it.copy(chiediDaZero = true) }
            return
        }
        avviaRifai(bozza.id, istruzioni, daZero = false)
    }

    fun confermaDaZero() {
        val bozza = _stato.value.bozza ?: return
        _stato.update { it.copy(chiediDaZero = false) }
        avviaRifai(bozza.id, null, daZero = true)
    }

    fun annullaDaZero() = _stato.update { it.copy(chiediDaZero = false) }

    private fun avviaRifai(bozzaId: Long, istruzioni: String?, daZero: Boolean) {
        _stato.update { it.copy(inCorso = true, errore = null, campi = null, confermaInvio = null) }
        ambito.launch {
            dopoRifai(client.rifaiBozza(bozzaId, istruzioni?.trim(), daZero))
        }
    }

    /** Una risposta per una mail che non ne ha (o ne ha solo di scartate). */
    fun preparaPerMail(mailId: Long, istruzioni: String?) {
        _stato.update { it.copy(inCorso = true, errore = null, campi = null, confermaInvio = null) }
        ambito.launch {
            dopoRifai(client.preparaRisposta(mailId, istruzioni?.trim()))
        }
    }

    private fun dopoRifai(esito: Esito<Bozza>) {
        when (esito) {
            is Esito.Ok -> {
                ferma()
                memoria.cancella(chiaveCampi(esito.dato.id))
                _stato.update { it.copy(bozza = esito.dato, inCorso = false, messaggio = "Sol sta riscrivendo la bozza.") }
                if (esito.dato.inPreparazione) aspettaChePronta(esito.dato.id)
            }
            else -> _stato.update { it.copy(inCorso = false, errore = esito.messaggio) }
        }
    }

    /** Per un sollecito appena chiesto: la bozza arriva già dal server, in preparazione. */
    fun mostra(bozza: Bozza) {
        ferma()
        _stato.value = StatoBozza(bozza = bozza)
        if (bozza.inPreparazione) aspettaChePronta(bozza.id)
    }

    fun scarta() {
        val stato = _stato.value
        val bozza = stato.bozza ?: return
        if (stato.campiBloccati) return
        _stato.update { it.copy(inCorso = true, errore = null) }
        ambito.launch {
            when (val esito = client.scartaBozza(bozza.id)) {
                is Esito.Ok -> {
                    memoria.cancella(chiaveCampi(bozza.id))
                    _stato.update {
                        it.copy(
                            bozza = bozza.copy(stato = Bozza.SCARTATA),
                            campi = null,
                            inCorso = false,
                            messaggio = "Bozza scartata.",
                        )
                    }
                }
                else -> _stato.update { it.copy(inCorso = false, errore = esito.messaggio) }
            }
        }
    }

    // --- Invio ---------------------------------------------------------------------

    /** Primo tempo: rilegge la bozza e prepara la conferma. Non invia niente. */
    fun chiediInvio() {
        val stato = _stato.value
        val bozza = stato.bozza ?: return
        val problema = when {
            stato.inModifica -> "Prima salva o annulla le modifiche."
            stato.verificaInvio -> "Sto ancora controllando se la mail di prima è partita: aspetta un momento."
            stato.campiBloccati -> return
            else -> null
        }
        if (problema != null) {
            _stato.update { it.copy(errore = problema) }
            return
        }
        preparaConferma(bozza.id, cambiata = false)
    }

    /** Dopo una rotazione con la finestra aperta: la si ricostruisce rileggendo la bozza. */
    fun ricostruisciConferma() {
        val stato = _stato.value
        val aperta = stato.confermaInvio ?: return
        if (stato.preparazioneConferma || stato.inCorso) return
        preparaConferma(aperta.bozzaId, cambiata = false, precedente = aperta)
    }

    private fun preparaConferma(bozzaId: Long, cambiata: Boolean, precedente: RichiestaConferma? = null) {
        _stato.update { it.copy(preparazioneConferma = true, errore = null) }
        ambito.launch {
            val fresca = when (val e = client.bozza(bozzaId)) {
                is Esito.Ok -> e.dato
                else -> {
                    memoria.cancella(CHIAVE_CONFERMA)
                    _stato.update {
                        it.copy(
                            preparazioneConferma = false,
                            confermaInvio = null,
                            errore = "Non riesco a rileggere la risposta prima di inviarla. ${e.messaggio.orEmpty()}".trim(),
                        )
                    }
                    return@launch
                }
            }
            _stato.update { it.copy(bozza = fresca) }
            val problema = problemaPerInvio(fresca)
            if (problema != null) {
                memoria.cancella(CHIAVE_CONFERMA)
                _stato.update { it.copy(preparazioneConferma = false, confermaInvio = null, errore = problema) }
                return@launch
            }
            val giaRisposto = fresca.tipo == "risposta" && fresca.mailId?.let { id ->
                (client.dettaglioMail(id) as? Esito.Ok)?.dato?.rispostaData
            } == true
            val conferma = confermaDa(fresca, giaRisposto, cambiata || (precedente != null && precedente.versione != fresca.versione))
            memoria.scrivi(CHIAVE_CONFERMA, bozzaId.toString())
            _stato.update { it.copy(preparazioneConferma = false, confermaInvio = conferma) }
        }
    }

    private fun confermaDa(b: Bozza, giaRisposto: Boolean, cambiata: Boolean) = RichiestaConferma(
        bozzaId = b.id,
        versione = b.versione ?: 0,
        a = b.a,
        cc = b.cc,
        oggetto = b.oggetto,
        primaRiga = b.primaRiga,
        giaRisposto = giaRisposto,
        cambiata = cambiata,
    )

    /** Perché questa bozza non si può inviare, o null. Testo vuoto: niente conferma (15.11). */
    private fun problemaPerInvio(b: Bozza): String? = when {
        b.stato == Bozza.INVIATA -> "Questa risposta è già partita."
        b.stato == Bozza.SCARTATA -> "Questa bozza è stata scartata."
        b.inPreparazione -> "Sol sta ancora scrivendo la risposta."
        b.senzaTesto -> SENZA_TESTO
        b.a.isEmpty() -> "Manca il destinatario: modifica la bozza."
        b.versione == null -> "Il server di casa va aggiornato: senza la versione della risposta non posso inviarla in sicurezza."
        !b.inviabile -> "Questa bozza non si può inviare adesso."
        else -> null
    }

    fun annullaInvio() {
        memoria.cancella(CHIAVE_CONFERMA)
        _stato.update { it.copy(confermaInvio = null) }
    }

    /** Secondo tempo: solo dopo [chiediInvio], e solo con la versione mostrata nella conferma. */
    fun confermaInvio() {
        val stato = _stato.value
        val conferma = stato.confermaInvio ?: return
        val bozza = stato.bozza ?: return
        if (conferma.bozzaId != bozza.id || stato.inCorso || stato.preparazioneConferma) return
        memoria.cancella(CHIAVE_CONFERMA)
        _stato.update { it.copy(confermaInvio = null, inCorso = true, errore = null) }
        ambito.launch {
            when (val esito = client.inviaBozza(conferma.bozzaId, conferma.versione)) {
                is Esito.Ok -> {
                    val inviata = esito.dato.bozza?.takeIf { it.id == bozza.id } ?: bozza.copy(stato = Bozza.INVIATA)
                    memoria.cancella(chiaveCampi(bozza.id))
                    val frase = if (esito.dato.giaInviata) GIA_INVIATA else "Mail inviata a ${conferma.a.joinToString(", ")}."
                    _stato.update { it.copy(bozza = inviata, inCorso = false, messaggio = frase) }
                    dopoInvio(inviata)
                }
                is Esito.Errore -> dopoRifiuto(esito, conferma)
                // Nessuna risposta: il server può averla ricevuta e inviata lo stesso.
                Esito.Irraggiungibile -> {
                    _stato.update { it.copy(inCorso = false, errore = INVIO_INCERTO) }
                    avviaVerifica(bozza.id)
                }
            }
        }
    }

    private suspend fun dopoRifiuto(esito: Esito.Errore, conferma: RichiestaConferma) {
        val nuova = esito.dettagli?.get("bozza")?.let {
            try {
                JsonSol.decodeFromJsonElement(Bozza.serializer(), it)
            } catch (e: Exception) {
                null
            }
        }
        when {
            // 15.11: la bozza è cambiata dopo il controllo. Niente è partito: si mostra
            // quella nuova e si richiede il sì.
            esito.stato == 409 && nuova != null && nuova.inviabile && nuova.versione != null -> {
                _stato.update {
                    it.copy(
                        bozza = nuova,
                        inCorso = false,
                        confermaInvio = confermaDa(nuova, conferma.giaRisposto, cambiata = true),
                    )
                }
                memoria.scrivi(CHIAVE_CONFERMA, nuova.id.toString())
            }
            // Rifiuti prima di provare: la mail di sicuro non è partita.
            esito.stato in RIFIUTI_CERTI -> {
                val riletta = (client.bozza(conferma.bozzaId) as? Esito.Ok)?.dato
                val frase = if (riletta?.stato == Bozza.INVIATA) {
                    "Questa risposta era già partita."
                } else {
                    "La mail NON è partita. ${esito.messaggio}"
                }
                _stato.update { s -> s.copy(bozza = riletta ?: nuova ?: s.bozza, inCorso = false, errore = frase) }
            }
            // 5xx, casella che non risponde: forse è partita. Si dice solo la frase del
            // server e si ricontrolla la bozza prima di lasciar riprovare.
            else -> {
                _stato.update { s -> s.copy(bozza = nuova ?: s.bozza, inCorso = false, errore = esito.messaggio) }
                avviaVerifica(conferma.bozzaId)
            }
        }
    }

    /**
     * Per [verificaPerMs] "Invia" resta spento e la bozza si rilegge ogni
     * [verificaOgniMs] (la prima volta subito): se risulta inviata, è partita. Poi
     * si può riprovare: il server controlla Posta inviata prima di rimandarla (15.7).
     */
    private fun avviaVerifica(bozzaId: Long) {
        verifica?.cancel()
        _stato.update { it.copy(verificaInvio = true) }
        verifica = ambito.launch {
            val fine = System.currentTimeMillis() + verificaPerMs
            while (true) {
                val riletta = (client.bozza(bozzaId) as? Esito.Ok)?.dato
                if (riletta != null) {
                    _stato.update { it.copy(bozza = riletta) }
                    if (riletta.stato == Bozza.INVIATA) {
                        memoria.cancella(chiaveCampi(bozzaId))
                        _stato.update { it.copy(verificaInvio = false, errore = null, messaggio = VERIFICATA_PARTITA) }
                        dopoInvio(riletta)
                        return@launch
                    }
                }
                if (System.currentTimeMillis() >= fine) break
                delay(verificaOgniMs)
            }
            _stato.update { it.copy(verificaInvio = false, errore = NON_RISULTA_PARTITA) }
        }
    }

    fun consumaMessaggio() {
        _stato.update { it.copy(messaggio = null) }
    }

    fun consumaErrore() {
        _stato.update { it.copy(errore = null) }
    }

    private fun chiaveCampi(id: Long) = "campi-$id"

    companion object {
        private const val CHIAVE_CONFERMA = "conferma-aperta"
        private val RIFIUTI_CERTI = setOf(401, 404, 409, 422)

        const val INVIO_INCERTO =
            "Il server di casa non ha risposto: non so se la mail è partita. Controllo lo stato della bozza " +
                "prima di lasciarti riprovare."
        const val GIA_INVIATA = "Era già partita: non l'ho rimandata."
        const val VERIFICATA_PARTITA = "Ho controllato: la mail è partita."
        const val NON_RISULTA_PARTITA =
            "Non risulta partita. Puoi riprovare: prima di rimandarla il server controlla che non sia già partita."
        const val SENZA_TESTO = "Sol non è riuscito a scrivere la risposta: il testo è vuoto."
    }
}
